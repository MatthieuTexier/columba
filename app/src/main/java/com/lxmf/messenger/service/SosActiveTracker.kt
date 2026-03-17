package com.lxmf.messenger.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks which contacts currently have an active SOS (receiver side).
 * In-memory singleton shared between MessageCollector and UI ViewModels.
 */
@Singleton
class SosActiveTracker
    @Inject
    constructor() {
        private val _activeSenders = MutableStateFlow<Set<String>>(emptySet())
        val activeSenders: StateFlow<Set<String>> = _activeSenders.asStateFlow()

        fun addSender(hash: String) {
            _activeSenders.value = _activeSenders.value + hash
        }

        fun removeSender(hash: String) {
            _activeSenders.value = _activeSenders.value - hash
        }

        fun isActive(hash: String): Flow<Boolean> =
            _activeSenders.map { it.contains(hash) }
    }
