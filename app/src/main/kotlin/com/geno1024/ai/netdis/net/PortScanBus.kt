package com.geno1024.ai.netdis.net

import com.geno1024.ai.netdis.model.PortScanState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One app-wide port scan at a time. The foreground service owns the work and
 * publishes progress here; the activity and the notification observe the same
 * state, so both stay in step while the app is in the background.
 */
object PortScanBus {

    private val _state = MutableStateFlow<PortScanState?>(null)
    val state: StateFlow<PortScanState?> = _state.asStateFlow()

    fun publish(state: PortScanState?) {
        _state.value = state
    }

    fun update(transform: (PortScanState) -> PortScanState) {
        _state.update { current -> current?.let(transform) }
    }

    fun isRunning(): Boolean = _state.value?.running == true
}
