package com.sojourners.chess.automation;

import java.util.Optional;

/**
 * Serializes automation validation and external input through one fail-closed gate.
 * A sequence may wait between inputs without blocking an emergency stop. Each
 * individual event sender must be a short, bounded operating-system call.
 */
public final class AutomationSafetyKernel {

    private AutomationState state = AutomationState.DISABLED;
    private Authorization authorization;
    private String lastReason;
    private long executionVersion;
    private long activeExecutionVersion;
    private int activeEventCount;

    public synchronized AutomationState state() {
        return state;
    }

    public synchronized Optional<Authorization> authorization() {
        return Optional.ofNullable(authorization);
    }

    public synchronized Optional<String> lastReason() {
        return Optional.ofNullable(lastReason);
    }

    public synchronized boolean arm(Authorization requestedAuthorization) {
        if (state != AutomationState.DISABLED) {
            return rejectTransition(AutomationState.DISABLED);
        }
        if (requestedAuthorization == null) {
            lastReason = "authorization is required";
            return false;
        }
        authorization = requestedAuthorization;
        lastReason = null;
        state = AutomationState.ARMED;
        return true;
    }

    public synchronized boolean beginObservation() {
        return transition(AutomationState.ARMED, AutomationState.OBSERVING);
    }

    public synchronized boolean recognitionAccepted() {
        return transition(AutomationState.OBSERVING, AutomationState.RECOGNIZED);
    }

    public synchronized boolean beginThinking() {
        return transition(AutomationState.RECOGNIZED, AutomationState.THINKING);
    }

    public synchronized boolean readyToExecute() {
        return transition(AutomationState.THINKING, AutomationState.READY);
    }

    public synchronized boolean confirmationAccepted() {
        return transition(AutomationState.CONFIRMING, AutomationState.OBSERVING);
    }

    public synchronized void validationFailed(String reason) {
        failClosed(normalizeReason(reason, "validation failed"));
    }

    /**
     * Runs at most one authorized move sequence. Every low-level event in that
     * sequence must pass through the supplied permit. Emergency stop invalidates
     * the permit, including while the sequence is waiting between events.
     */
    public boolean executeOne(ExternalEventSequence sequence) {
        EventPermit permit;
        synchronized (this) {
            if (state != AutomationState.READY) {
                if (state != AutomationState.DISABLED && state != AutomationState.PAUSED) {
                    failClosed("external event rejected while state was " + state);
                }
                return false;
            }
            if (sequence == null) {
                failClosed("external event sequence is required");
                return false;
            }

            state = AutomationState.EXECUTING;
            activeExecutionVersion = ++executionVersion;
            activeEventCount = 0;
            permit = new EventPermit(this, activeExecutionVersion);
        }

        try {
            sequence.send(permit);
            synchronized (this) {
                if (!isActiveExecution(permit.executionVersion)) {
                    return false;
                }
                if (activeEventCount == 0) {
                    failClosed("external event sequence completed without an event");
                    return false;
                }
                state = AutomationState.CONFIRMING;
                activeExecutionVersion = 0;
                lastReason = null;
                return true;
            }
        } catch (Throwable failure) {
            synchronized (this) {
                if (isActiveExecution(permit.executionVersion)) {
                    failClosed("external event failed: "
                            + normalizeReason(failure.getMessage(), failure.getClass().getSimpleName()));
                }
            }
            if (failure instanceof Error error) {
                throw error;
            }
            return false;
        }
    }

    public synchronized void emergencyStop(String reason) {
        authorization = null;
        state = AutomationState.DISABLED;
        activeExecutionVersion = 0;
        activeEventCount = 0;
        lastReason = normalizeReason(reason, "emergency stop");
    }

    private synchronized boolean sendExternalEvent(long permitVersion,
                                                    ExternalEventSender sender) throws Exception {
        if (!isActiveExecution(permitVersion)) {
            return false;
        }
        if (sender == null) {
            failClosed("external event sender is required");
            return false;
        }
        try {
            sender.send();
            activeEventCount++;
            return true;
        } catch (Throwable failure) {
            if (isActiveExecution(permitVersion)) {
                failClosed("external event failed: "
                        + normalizeReason(failure.getMessage(), failure.getClass().getSimpleName()));
            }
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException(failure);
        }
    }

    private boolean isActiveExecution(long permitVersion) {
        return state == AutomationState.EXECUTING
                && activeExecutionVersion == permitVersion;
    }

    private boolean transition(AutomationState expected, AutomationState next) {
        if (state != expected) {
            return rejectTransition(expected);
        }
        state = next;
        lastReason = null;
        return true;
    }

    private boolean rejectTransition(AutomationState expected) {
        failClosed("invalid transition: expected " + expected + " but was " + state);
        return false;
    }

    private void failClosed(String reason) {
        lastReason = reason;
        activeExecutionVersion = 0;
        activeEventCount = 0;
        if (state != AutomationState.DISABLED) {
            state = AutomationState.PAUSED;
        }
    }

    private String normalizeReason(String reason, String fallback) {
        if (reason == null || reason.isBlank()) {
            return fallback;
        }
        return reason.trim();
    }

    public record Authorization(String targetId, long targetRevision) {
        public Authorization {
            if (targetId == null || targetId.isBlank()) {
                throw new IllegalArgumentException("targetId must not be blank");
            }
            if (targetRevision < 1) {
                throw new IllegalArgumentException("targetRevision must be positive");
            }
            targetId = targetId.trim();
        }
    }

    @FunctionalInterface
    public interface ExternalEventSender {
        void send() throws Exception;
    }

    @FunctionalInterface
    public interface ExternalEventSequence {
        void send(EventPermit permit) throws Exception;
    }

    public static final class EventPermit {
        private final AutomationSafetyKernel kernel;
        private final long executionVersion;

        private EventPermit(AutomationSafetyKernel kernel, long executionVersion) {
            this.kernel = kernel;
            this.executionVersion = executionVersion;
        }

        public boolean send(ExternalEventSender sender) throws Exception {
            return kernel.sendExternalEvent(executionVersion, sender);
        }
    }
}
