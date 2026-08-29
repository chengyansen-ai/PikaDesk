package com.sojourners.chess.linker;

import java.util.Objects;

/** A bounded, user-facing snapshot of the connection lifecycle. */
public record ConnectionStatus(State state, String message) {

    private static final int MAX_MESSAGE_LENGTH = 120;

    public enum State {
        IDLE,
        SELECTING_TARGET,
        CONFIGURING,
        SYNCHRONIZING,
        OBSERVING,
        MOVE_SYNCED,
        PAUSED,
        STOPPED
    }

    public ConnectionStatus {
        Objects.requireNonNull(state, "state");
        message = normalize(message);
    }

    public static ConnectionStatus of(State state, String message) {
        return new ConnectionStatus(state, message);
    }

    public static ConnectionStatus idle() {
        return of(State.IDLE, "未连接");
    }

    private static String normalize(String message) {
        if (message == null) {
            throw new IllegalArgumentException("connection status message is required");
        }
        String normalized = message.trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("connection status message is required");
        }
        if (normalized.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("connection status message is too long");
        }
        return normalized;
    }
}
