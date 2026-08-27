package com.sojourners.chess.automation;

/**
 * Fail-closed lifecycle for any operation that can emit an external input event.
 */
public enum AutomationState {
    DISABLED,
    ARMED,
    OBSERVING,
    RECOGNIZED,
    THINKING,
    READY,
    EXECUTING,
    CONFIRMING,
    PAUSED
}
