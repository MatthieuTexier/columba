package com.lxmf.messenger.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.util.Log
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.notifications.NotificationHelper
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Represents the current state of the SOS system.
 */
sealed class SosState {
    data object Idle : SosState()

    data class Countdown(
        val remainingSeconds: Int,
        val totalSeconds: Int,
    ) : SosState()

    data object Sending : SosState()

    data class Active(
        val sentCount: Int,
        val failedCount: Int,
    ) : SosState()
}

/**
 * Manages the SOS emergency messaging state machine.
 *
 * State flow: Idle -> Countdown -> Sending -> Active -> Idle
 *
 * When triggered, the manager reads SOS settings, optionally counts down,
 * sends emergency messages to all configured SOS contacts, and enters
 * an active state with optional periodic location updates.
 */
@Singleton
class SosManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val contactRepository: ContactRepository,
        private val settingsRepository: SettingsRepository,
        private val reticulumProtocol: ReticulumProtocol,
        private val notificationHelper: NotificationHelper,
    ) {
        companion object {
            private const val TAG = "SosManager"
        }

        /** Override in tests to use a test dispatcher. */
        internal var dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default

        private val scope by lazy { CoroutineScope(SupervisorJob() + dispatcher) }

        private val _state = MutableStateFlow<SosState>(SosState.Idle)
        val state: StateFlow<SosState> = _state.asStateFlow()

        private var countdownJob: Job? = null
        private var periodicUpdateJob: Job? = null

        /**
         * Restore persisted SOS active state after app/phone restart.
         * Should be called once at app startup (e.g., from Application.onCreate or service init).
         */
        fun restoreIfActive() {
            scope.launch {
                try {
                    val wasActive = settingsRepository.sosActive.first()
                    if (!wasActive) return@launch

                    val sentCount = settingsRepository.sosActiveSentCount.first()
                    val failedCount = settingsRepository.sosActiveFailedCount.first()
                    _state.value = SosState.Active(sentCount, failedCount)
                    notificationHelper.showSosActiveNotification(sentCount, failedCount)
                    Log.d(TAG, "Restored SOS active state: sent=$sentCount, failed=$failedCount")

                    val periodicUpdates = settingsRepository.sosPeriodicUpdates.first()
                    if (periodicUpdates) {
                        startPeriodicUpdates()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error restoring SOS state", e)
                }
            }
        }

        /**
         * Trigger the SOS sequence. Reads settings to determine countdown duration,
         * then proceeds to send emergency messages to all SOS contacts.
         */
        fun trigger() {
            scope.launch {
                try {
                    val enabled = settingsRepository.sosEnabled.first()
                    if (!enabled) {
                        Log.d(TAG, "SOS not enabled, ignoring trigger")
                        return@launch
                    }

                    val countdownSeconds = settingsRepository.sosCountdownSeconds.first()
                    if (countdownSeconds <= 0) {
                        sendSosMessages()
                    } else {
                        startCountdown(countdownSeconds)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error triggering SOS", e)
                    _state.value = SosState.Idle
                }
            }
        }

        /**
         * Cancel the SOS during the countdown phase. Returns to Idle.
         */
        fun cancel() {
            if (_state.value is SosState.Countdown) {
                countdownJob?.cancel()
                countdownJob = null
                _state.value = SosState.Idle
                Log.d(TAG, "SOS countdown cancelled")
            }
        }

        /**
         * Deactivate SOS from the Active state.
         *
         * @param pin Optional PIN for deactivation. If a deactivation PIN is configured
         *            in settings, the provided pin must match.
         * @return true if successfully deactivated, false if PIN mismatch or not in Active state.
         */
        fun deactivate(pin: String? = null): Boolean {
            if (_state.value !is SosState.Active) return false

            val requiredPin = runBlocking { settingsRepository.sosDeactivationPin.first() }
            if (!requiredPin.isNullOrBlank() && requiredPin != pin) {
                Log.d(TAG, "SOS deactivation PIN mismatch")
                return false
            }

            periodicUpdateJob?.cancel()
            periodicUpdateJob = null
            notificationHelper.cancelSosActiveNotification()
            _state.value = SosState.Idle
            scope.launch { settingsRepository.clearSosActiveState() }
            Log.d(TAG, "SOS deactivated")
            return true
        }

        /**
         * Check if incoming calls should be auto-answered due to active SOS.
         *
         * @return true if SOS is active and silent auto-answer is enabled in settings.
         */
        fun shouldAutoAnswer(): Boolean {
            if (_state.value !is SosState.Active) return false
            return runBlocking { settingsRepository.sosSilentAutoAnswer.first() }
        }

        private suspend fun startCountdown(totalSeconds: Int) {
            countdownJob = scope.launch {
                try {
                    for (remaining in totalSeconds downTo 1) {
                        _state.value = SosState.Countdown(remaining, totalSeconds)
                        delay(1_000L)
                    }
                    sendSosMessages()
                } catch (e: CancellationException) {
                    Log.d(TAG, "Countdown coroutine cancelled")
                    throw e
                }
            }
        }

        @SuppressLint("MissingPermission")
        private suspend fun sendSosMessages() {
            _state.value = SosState.Sending

            val template = settingsRepository.sosMessageTemplate.first()
            val includeLocation = settingsRepository.sosIncludeLocation.first()

            val messageContent = buildString {
                append(template)
                if (includeLocation) {
                    getLastKnownLocation()?.let { location ->
                        append("\nGPS: ${location.latitude}, ${location.longitude}")
                        append(" (accuracy: ${location.accuracy.toInt()}m)")
                    }
                }
            }

            val contacts = contactRepository.getSosContacts()
            if (contacts.isEmpty()) {
                Log.w(TAG, "No SOS contacts configured")
                _state.value = SosState.Active(sentCount = 0, failedCount = 0)
                notificationHelper.showSosActiveNotification(0, 0)
                return
            }

            val identity = loadIdentity()
            if (identity == null) {
                Log.e(TAG, "Failed to load identity, cannot send SOS messages")
                _state.value = SosState.Active(sentCount = 0, failedCount = contacts.size)
                notificationHelper.showSosActiveNotification(0, contacts.size)
                return
            }

            var sentCount = 0
            var failedCount = 0

            for (contact in contacts) {
                try {
                    val destHashBytes = contact.destinationHash.hexToByteArray()
                    val result = reticulumProtocol.sendLxmfMessageWithMethod(
                        destinationHash = destHashBytes,
                        content = messageContent,
                        sourceIdentity = identity,
                    )
                    if (result.isSuccess) {
                        sentCount++
                        Log.d(TAG, "SOS message sent to ${contact.destinationHash.take(8)}...")
                    } else {
                        failedCount++
                        Log.e(TAG, "SOS message failed for ${contact.destinationHash.take(8)}...: ${result.exceptionOrNull()?.message}")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failedCount++
                    Log.e(TAG, "Error sending SOS message to ${contact.destinationHash.take(8)}...", e)
                }
            }

            _state.value = SosState.Active(sentCount, failedCount)
            settingsRepository.persistSosActiveState(sentCount, failedCount)
            notificationHelper.showSosActiveNotification(sentCount, failedCount)
            Log.d(TAG, "SOS messages sent: $sentCount success, $failedCount failed")

            val periodicUpdates = settingsRepository.sosPeriodicUpdates.first()
            if (periodicUpdates) {
                startPeriodicUpdates()
            }
        }

        @SuppressLint("MissingPermission")
        private fun startPeriodicUpdates() {
            periodicUpdateJob = scope.launch {
                try {
                    val intervalSeconds = settingsRepository.sosUpdateIntervalSeconds.first()
                    val identity = loadIdentity() ?: return@launch

                    while (true) {
                        delay(intervalSeconds * 1_000L)

                        val updateMessage = buildString {
                            append("SOS Update")
                            getLastKnownLocation()?.let { location ->
                                append(" - GPS: ${location.latitude}, ${location.longitude}")
                                append(" (accuracy: ${location.accuracy.toInt()}m)")
                            }
                        }

                        val contacts = contactRepository.getSosContacts()
                        for (contact in contacts) {
                            try {
                                val destHashBytes = contact.destinationHash.hexToByteArray()
                                reticulumProtocol.sendLxmfMessageWithMethod(
                                    destinationHash = destHashBytes,
                                    content = updateMessage,
                                    sourceIdentity = identity,
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.e(TAG, "Error sending SOS update to ${contact.destinationHash.take(8)}...", e)
                            }
                        }

                        Log.d(TAG, "Periodic SOS update sent to ${contacts.size} contacts")
                    }
                } catch (e: CancellationException) {
                    Log.d(TAG, "Periodic updates cancelled")
                    throw e
                }
            }
        }

        @SuppressLint("MissingPermission")
        private fun getLastKnownLocation(): Location? =
            try {
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting last known location", e)
                null
            }

        private suspend fun loadIdentity(): com.lxmf.messenger.reticulum.model.Identity? =
            try {
                if (reticulumProtocol is ServiceReticulumProtocol) {
                    reticulumProtocol.getLxmfIdentity().getOrNull()
                } else {
                    reticulumProtocol.loadIdentity("default_identity").getOrNull()
                        ?: reticulumProtocol.createIdentity().getOrThrow().also {
                            reticulumProtocol.saveIdentity(it, "default_identity")
                        }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading identity", e)
                null
            }

        /**
         * Convert a hex string to a ByteArray.
         */
        private fun String.hexToByteArray(): ByteArray {
            check(length % 2 == 0) { "Hex string must have even length" }
            return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
    }
