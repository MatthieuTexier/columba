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
            private const val TAP_THRESHOLD = 15.0f // m/s² spike to count as a tap
            private const val TAP_WINDOW_MS = 1_500L // max time window for all taps
            private const val TAP_MIN_INTERVAL_MS = 80L // ignore taps closer than this (debounce)
            private const val TAP_COOLDOWN_MS = 5_000L
        }

        private val sensorManager by lazy {
            context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private var isListening = false
        private var currentMode: SosTriggerMode = SosTriggerMode.MANUAL
        private var shakeSensitivity = 2.5f
        private var requiredTapCount = 3
        private var settingsJob: Job? = null

        // Shake state
        private var shakeStartTime = 0L
        private var shakeAccumulatedMs = 0L
        private var lastShakeEventTime = 0L
        private var lastShakeTriggerTime = 0L

        // Tap state
        private val tapTimestamps = mutableListOf<Long>()
        private var lastTapTriggerTime = 0L

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

            sensorManager.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_UI,
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
            tapTimestamps.clear()
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
        private fun handleShake(netAcceleration: Float, now: Long) {
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
         * Tap detection: count sharp acceleration spikes (above [TAP_THRESHOLD])
         * that occur within [TAP_WINDOW_MS]. When [requiredTapCount] taps are detected,
         * trigger SOS.
         */
        private fun handleTap(netAcceleration: Float, now: Long) {
            if (now - lastTapTriggerTime < TAP_COOLDOWN_MS) return

            if (netAcceleration > TAP_THRESHOLD) {
                // Debounce: ignore taps too close together
                val lastTap = tapTimestamps.lastOrNull() ?: 0L
                if (now - lastTap < TAP_MIN_INTERVAL_MS) return

                tapTimestamps.add(now)

                // Remove taps outside the window
                tapTimestamps.removeAll { now - it > TAP_WINDOW_MS }

                if (tapTimestamps.size >= requiredTapCount) {
                    Log.d(TAG, "Tap pattern detected (${tapTimestamps.size} taps)! Triggering SOS")
                    lastTapTriggerTime = now
                    tapTimestamps.clear()
                    sosManager.trigger()
                }
            }
        }

        private fun resetShakeState() {
            shakeStartTime = 0L
            shakeAccumulatedMs = 0L
            lastShakeEventTime = 0L
        }
    }
