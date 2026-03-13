package com.lxmf.messenger.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.lxmf.messenger.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Trigger mode for SOS activation.
 */
enum class SosTriggerMode(val key: String) {
    MANUAL("manual"),
    SHAKE("shake"),
    TAP_PATTERN("tap_pattern"),
    ;

    companion object {
        fun fromKey(key: String): SosTriggerMode =
            entries.find { it.key == key } ?: MANUAL
    }
}

/**
 * Detects SOS trigger gestures via the device accelerometer.
 *
 * Supports two detection modes:
 * - **Shake**: Sustained high acceleration (magnitude minus gravity exceeds threshold).
 *   Requires the threshold to be exceeded for [SHAKE_DURATION_MS] within a
 *   [SHAKE_WINDOW_MS] window to avoid false positives from single bumps.
 * - **Tap pattern**: A sequence of sharp acceleration spikes (taps) within a time window.
 *   The required number of taps is configurable (3-5).
 *
 * The detector registers/unregisters itself based on [start]/[stop] calls.
 * It should be started when SOS is enabled with a non-MANUAL trigger mode,
 * and stopped otherwise.
 */
@Singleton
class SosTriggerDetector
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settingsRepository: SettingsRepository,
        private val sosManager: SosManager,
    ) : SensorEventListener {
        companion object {
            private const val TAG = "SosTriggerDetector"

            // Shake detection constants
            private const val SHAKE_WINDOW_MS = 1_000L
            private const val SHAKE_DURATION_MS = 500L
            private const val SHAKE_COOLDOWN_MS = 5_000L

            // Tap detection constants
            // Spike-based detection: a tap is counted only when netAcceleration crosses above
            // TAP_THRESHOLD and returns below it within MAX_TAP_SPIKE_MS.
            // Walking/running steps last >100ms at the sensor → rejected by duration filter.
            // This lets us use a lower threshold without false positives from sustained motion.
            private const val TAP_THRESHOLD = 4.0f       // m/s² to enter a spike
            private const val MAX_TAP_SPIKE_MS = 100L    // valid tap spike must end within this
            private const val TAP_WINDOW_MS = 2_500L     // sliding window to accumulate taps
            private const val TAP_MIN_INTERVAL_MS = 150L // min time between two registered taps
            private const val TAP_COOLDOWN_MS = 5_000L
        }

        private val sensorManager by lazy {
            context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private var isListening = false

        /** Override in tests to set mode directly without sensor registration. */
        internal var currentMode: SosTriggerMode = SosTriggerMode.MANUAL

        /** Override in tests to control shake sensitivity. */
        internal var shakeSensitivity = 2.5f

        /** Override in tests to control required tap count. */
        internal var requiredTapCount = 3
        private var settingsJob: Job? = null

        // Shake state
        private var shakeStartTime = 0L
        private var shakeAccumulatedMs = 0L
        private var lastShakeEventTime = 0L
        private var lastShakeTriggerTime = 0L

        // Tap state
        private val tapTimestamps = mutableListOf<Long>()
        private var lastTapTriggerTime = 0L
        private var lastTapRegisteredTime = 0L
        private var inTapSpike = false
        private var tapSpikeStartTime = 0L

        /**
         * Start listening for trigger gestures. Reads current settings and
         * registers the accelerometer listener if the trigger mode is not MANUAL.
         */
        fun start() {
            if (isListening) return

            settingsJob = scope.launch {
                currentMode = SosTriggerMode.fromKey(settingsRepository.sosTriggerMode.first())
                shakeSensitivity = settingsRepository.sosShakeSensitivity.first()
                requiredTapCount = settingsRepository.sosTapCount.first()
            }

            // Wait for settings to load before deciding whether to register
            runBlocking { settingsJob?.join() }

            if (currentMode == SosTriggerMode.MANUAL) {
                Log.d(TAG, "Trigger mode is MANUAL, not registering sensor")
                return
            }

            val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (accelerometer == null) {
                Log.w(TAG, "No accelerometer available on this device")
                return
            }

            // SENSOR_DELAY_GAME (~20ms) needed for tap detection: brief taps last only 20-50ms
            // and SENSOR_DELAY_UI (~60ms) misses peaks between samples.
            sensorManager.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_GAME,
            )
            isListening = true
            Log.d(TAG, "Started listening for trigger mode: ${currentMode.key}")
        }

        /**
         * Stop listening for trigger gestures. Unregisters the sensor listener.
         */
        fun stop() {
            if (!isListening) return
            sensorManager.unregisterListener(this)
            isListening = false
            resetShakeState()
            resetTapState()
            settingsJob?.cancel()
            settingsJob = null
            Log.d(TAG, "Stopped listening")
        }

        /**
         * Reload settings (e.g., when user changes trigger mode or sensitivity).
         * Restarts the listener if needed.
         */
        fun reloadSettings() {
            stop()
            start()
        }

        /**
         * Start observing SOS settings and [SosManager] state to manage:
         * 1. The accelerometer sensor listener (shake/tap detection)
         * 2. The [SosTriggerService] foreground service lifecycle
         *
         * The foreground service runs whenever:
         * - Trigger detection is active (SOS enabled + non-MANUAL mode), OR
         * - SOS is in an active state (Countdown / Sending / Active) — keeps the
         *   process alive for periodic location updates and message sending,
         *   including after a device reboot.
         *
         * Should be called once at app startup (main process only).
         */
        fun startObserving() {
            scope.launch {
                combine(
                    settingsRepository.sosEnabled,
                    settingsRepository.sosTriggerMode,
                    settingsRepository.sosShakeSensitivity,
                    settingsRepository.sosTapCount,
                    sosManager.state,
                ) { enabled, mode, _, _, sosState ->
                    Triple(enabled, mode, sosState !is SosState.Idle)
                }
                    .distinctUntilChanged()
                    .collect { (enabled, mode, sosActive) ->
                        val triggerNeeded = enabled &&
                            SosTriggerMode.fromKey(mode) != SosTriggerMode.MANUAL

                        // Auto-deactivate SOS when the feature is disabled
                        if (!enabled && sosActive) {
                            sosManager.deactivate()
                        }

                        if (triggerNeeded) {
                            reloadSettings()
                        } else {
                            stop()
                        }

                        if (triggerNeeded || sosActive) {
                            SosTriggerService.start(context)
                        } else {
                            SosTriggerService.stop(context)
                        }
                    }
            }
        }

        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            // Acceleration magnitude minus gravity (~9.81 m/s²)
            val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
            val netAcceleration = Math.abs(magnitude - SensorManager.GRAVITY_EARTH)

            val now = System.currentTimeMillis()

            when (currentMode) {
                SosTriggerMode.SHAKE -> handleShake(netAcceleration, now)
                SosTriggerMode.TAP_PATTERN -> handleTap(netAcceleration, now)
                SosTriggerMode.MANUAL -> { /* should not happen */ }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // Not needed
        }

        /**
         * Shake detection: the net acceleration must exceed [shakeSensitivity] * GRAVITY
         * for a cumulative [SHAKE_DURATION_MS] within a [SHAKE_WINDOW_MS] sliding window.
         */
        internal fun handleShake(netAcceleration: Float, now: Long) {
            if (now - lastShakeTriggerTime < SHAKE_COOLDOWN_MS) return

            val threshold = shakeSensitivity * SensorManager.GRAVITY_EARTH

            if (netAcceleration > threshold) {
                if (shakeStartTime == 0L) {
                    shakeStartTime = now
                }
                if (now - shakeStartTime <= SHAKE_WINDOW_MS) {
                    shakeAccumulatedMs += now - (lastShakeEventTime.takeIf { it > 0L } ?: now)
                    lastShakeEventTime = now

                    if (shakeAccumulatedMs >= SHAKE_DURATION_MS) {
                        Log.d(TAG, "Shake detected! Triggering SOS")
                        lastShakeTriggerTime = now
                        resetShakeState()
                        sosManager.trigger()
                    }
                } else {
                    // Window expired, reset
                    resetShakeState()
                }
            } else {
                // Below threshold, allow small gaps but reset if too long
                if (lastShakeEventTime > 0L && now - lastShakeEventTime > 200L) {
                    resetShakeState()
                }
            }
        }

        /**
         * Tap detection using spike-based state machine.
         *
         * A tap is counted only when [netAcceleration] crosses above [TAP_THRESHOLD] and then
         * returns below it within [MAX_TAP_SPIKE_MS]. This rejects walking steps and sustained
         * vibrations (which create spikes lasting >100ms) while reliably catching brief finger
         * taps (typically 20–80ms). The lower threshold is safe because the duration filter
         * prevents false positives from continuous motion.
         */
        internal fun handleTap(netAcceleration: Float, now: Long) {
            if (now - lastTapTriggerTime < TAP_COOLDOWN_MS) return

            if (!inTapSpike) {
                if (netAcceleration > TAP_THRESHOLD) {
                    // Rising edge: spike starts
                    inTapSpike = true
                    tapSpikeStartTime = now
                }
            } else {
                if (netAcceleration > TAP_THRESHOLD) {
                    // Still in spike — abort if it lasts too long (walking, shake, sustained bump)
                    if (now - tapSpikeStartTime > MAX_TAP_SPIKE_MS) {
                        inTapSpike = false
                    }
                } else {
                    // Falling edge: spike ended
                    val spikeDuration = now - tapSpikeStartTime
                    inTapSpike = false

                    if (spikeDuration <= MAX_TAP_SPIKE_MS &&
                        now - lastTapRegisteredTime >= TAP_MIN_INTERVAL_MS
                    ) {
                        // Valid short spike with sufficient gap from previous tap
                        lastTapRegisteredTime = now
                        tapTimestamps.add(now)
                        tapTimestamps.removeAll { now - it > TAP_WINDOW_MS }

                        Log.d(TAG, "Tap registered (spike ${spikeDuration}ms), count=${tapTimestamps.size}/$requiredTapCount")

                        if (tapTimestamps.size >= requiredTapCount) {
                            Log.d(TAG, "Tap pattern detected! Triggering SOS")
                            lastTapTriggerTime = now
                            resetTapState()
                            sosManager.trigger()
                        }
                    }
                }
            }
        }

        internal fun resetShakeState() {
            shakeStartTime = 0L
            shakeAccumulatedMs = 0L
            lastShakeEventTime = 0L
        }

        internal fun resetTapState() {
            tapTimestamps.clear()
            inTapSpike = false
            tapSpikeStartTime = 0L
            lastTapRegisteredTime = 0L
        }
    }
