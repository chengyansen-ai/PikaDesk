package com.sojourners.chess.linker;

import java.util.Objects;

/**
 * User-visible connection capability. Read-only is deliberately the fallback
 * for missing, legacy, or unknown selections.
 */
public enum LinkMode {

    READ_ONLY_ADVISOR("只读陪练", false),
    AUTHORIZED_AUTOMATION("自动走棋", true);

    private final String displayName;
    private final boolean externalInputAllowed;

    LinkMode(String displayName, boolean externalInputAllowed) {
        this.displayName = displayName;
        this.externalInputAllowed = externalInputAllowed;
    }

    public String displayName() {
        return displayName;
    }

    public boolean externalInputAllowed() {
        return externalInputAllowed;
    }

    public static LinkMode safeDefault() {
        return READ_ONLY_ADVISOR;
    }

    public static LinkMode fromDisplayName(String value) {
        if (AUTHORIZED_AUTOMATION.displayName.equals(value)) {
            return AUTHORIZED_AUTOMATION;
        }
        return READ_ONLY_ADVISOR;
    }

    public boolean requiresReconnectFrom(LinkMode activeMode) {
        return this != Objects.requireNonNull(activeMode, "activeMode");
    }
}
