package com.lxmf.messenger.service

import android.content.Context
import android.util.Log

/**
 * Coordinates [LocationForegroundService] lifecycle between multiple consumers
 * (location sharing and telemetry collection).
 *
 * The service is started when the first consumer acquires and stopped when the
 * last consumer releases. Thread-safe via synchronized.
 */
object LocationServiceCoordinator {
    private const val TAG = "LocationServiceCoord"

    const val REASON_SHARING = "location_sharing"
    const val REASON_TELEMETRY = "telemetry_collection"

    private val activeReasons = mutableSetOf<String>()

    fun isAcquired(reason: String): Boolean = synchronized(activeReasons) { reason in activeReasons }

    fun acquire(context: Context, reason: String) {
        synchronized(activeReasons) {
            val wasEmpty = activeReasons.isEmpty()
            activeReasons.add(reason)
            if (wasEmpty) {
                Log.d(TAG, "Starting location foreground service (reason: $reason)")
                try {
                    LocationForegroundService.start(context)
                } catch (e: Exception) {
                    activeReasons.remove(reason)
                    Log.e(TAG, "Failed to start service, rolled back '$reason'", e)
                }
            } else {
                Log.d(TAG, "Location service already running, added reason: $reason (active: $activeReasons)")
            }
        }
    }

    /** Called by the service when it fails to start foreground and self-destructs. */
    fun clearAll() {
        synchronized(activeReasons) {
            Log.w(TAG, "Clearing all reasons due to service failure (was: $activeReasons)")
            activeReasons.clear()
        }
    }

    fun release(context: Context, reason: String) {
        synchronized(activeReasons) {
            if (!activeReasons.remove(reason)) {
                Log.d(TAG, "release() for '$reason' — not acquired, ignoring")
                return
            }
            if (activeReasons.isEmpty()) {
                Log.d(TAG, "Stopping location foreground service (released: $reason)")
                LocationForegroundService.stop(context)
            } else {
                Log.d(TAG, "Location service still needed (released: $reason, remaining: $activeReasons)")
            }
        }
    }
}
