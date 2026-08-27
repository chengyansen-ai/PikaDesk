package com.sojourners.chess.linker;

import com.sojourners.chess.automation.AutomationSafetyKernel;
import com.sojourners.chess.automation.AutomationState;
import com.sojourners.chess.automation.BoardCoordinateMapper;
import com.sojourners.chess.recognition.RecognitionResult;
import com.sojourners.chess.util.XiangqiUtils;

import java.util.Objects;
import java.util.Optional;

/**
 * Owns the complete state transition for one Windows move: authorize, emit one
 * click pair, then observe until the exact move is visually confirmed or the
 * automation is paused. It never retries an execution.
 */
public final class WindowsMoveCoordinator {

    private static final long CONFIRMATION_POLL_MILLIS = 50;
    private static final int MIN_CONFIRMATION_TIMEOUT_MILLIS = 100;
    private static final int MAX_CONFIRMATION_TIMEOUT_MILLIS = 10_000;

    private final WindowsAutomationTarget target;
    private final FrameObserver frameObserver;
    private final MonotonicClock clock;
    private final Waiter waiter;
    private final AutomationSafetyKernel kernel = new AutomationSafetyKernel();
    private final BoardCoordinateMapper mapper = new BoardCoordinateMapper();

    public WindowsMoveCoordinator(WindowsAutomationTarget target,
                                  FrameObserver frameObserver) {
        this(target, frameObserver, System::nanoTime, Thread::sleep);
    }

    WindowsMoveCoordinator(WindowsAutomationTarget target,
                           FrameObserver frameObserver,
                           MonotonicClock clock,
                           Waiter waiter) {
        this.target = Objects.requireNonNull(target, "target");
        this.frameObserver = Objects.requireNonNull(frameObserver, "frameObserver");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.waiter = Objects.requireNonNull(waiter, "waiter");
    }

    public boolean arm() {
        return kernel.arm(target.authorization()) && kernel.beginObservation();
    }

    public ExecutionOutcome executeAndConfirm(
            BoardCoordinateMapper.Calibration calibration,
            char[][] canonicalPositionBeforeMove,
            char[][] visualPositionBeforeMove,
            BoardCoordinateMapper.Move canonicalMove,
            MoveConfirmationTracker.GridMove visualMove,
            int clickDelayMillis,
            int moveDelayMillis,
            int confirmationTimeoutMillis) {
        Objects.requireNonNull(calibration, "calibration");
        Objects.requireNonNull(canonicalPositionBeforeMove, "canonicalPositionBeforeMove");
        Objects.requireNonNull(visualPositionBeforeMove, "visualPositionBeforeMove");
        Objects.requireNonNull(canonicalMove, "canonicalMove");
        Objects.requireNonNull(visualMove, "visualMove");

        if (kernel.state() != AutomationState.OBSERVING) {
            return rejected(lastReason().orElse(
                    "automation is not observing: " + kernel.state()));
        }
        if (confirmationTimeoutMillis < MIN_CONFIRMATION_TIMEOUT_MILLIS
                || confirmationTimeoutMillis > MAX_CONFIRMATION_TIMEOUT_MILLIS) {
            kernel.validationFailed("confirmation timeout must be between 100 and 10000 ms");
            return rejected(kernel.lastReason().orElseThrow());
        }
        if (!validCanonicalMove(canonicalPositionBeforeMove, canonicalMove)
                || !validVisualMove(visualPositionBeforeMove, visualMove)) {
            kernel.validationFailed("expected canonical or visual move is not valid");
            return rejected(kernel.lastReason().orElseThrow());
        }
        Optional<BoardCoordinateMapper.TargetSnapshot> expectedTarget = target.currentTarget();
        if (expectedTarget.isEmpty()) {
            kernel.validationFailed("selected target is unavailable before execution");
            return rejected(kernel.lastReason().orElseThrow());
        }
        if (!kernel.recognitionAccepted()
                || !kernel.beginThinking()
                || !kernel.readyToExecute()) {
            return rejected(kernel.lastReason().orElse("safety transition failed"));
        }

        BoardCoordinateMapper.ActivitySnapshot activityAtReady;
        try {
            activityAtReady = target.currentActivity();
        } catch (RuntimeException failure) {
            kernel.validationFailed("could not read user activity: "
                    + normalize(failure.getMessage(), failure.getClass().getSimpleName()));
            return rejected(kernel.lastReason().orElseThrow());
        }
        boolean executed = target.executeOne(
                kernel, mapper, calibration, activityAtReady, canonicalMove,
                clickDelayMillis, moveDelayMillis);
        if (!executed) {
            return rejected(kernel.lastReason().orElse("authorized execution was rejected"));
        }

        long confirmationStarted = clock.nanoTime();
        MoveConfirmationTracker confirmation;
        try {
            confirmation = new MoveConfirmationTracker(
                    kernel,
                    visualPositionBeforeMove,
                    visualMove,
                    expectedTarget.orElseThrow(),
                    confirmationStarted,
                    confirmationTimeoutMillis
            );
        } catch (RuntimeException failure) {
            kernel.validationFailed("could not start visual confirmation: "
                    + normalize(failure.getMessage(), failure.getClass().getSimpleName()));
            return rejected(kernel.lastReason().orElseThrow());
        }

        while (true) {
            Optional<BoardCoordinateMapper.TargetSnapshot> currentTarget = target.currentTarget();
            if (currentTarget.isEmpty()) {
                kernel.validationFailed("selected target disappeared during confirmation");
                return new ExecutionOutcome(
                        false,
                        kernel.lastReason().orElseThrow(),
                        Optional.of(MoveConfirmationTracker.Status.TARGET_CHANGED),
                        Optional.empty(),
                        Optional.empty()
                );
            }
            RecognitionResult frame;
            try {
                frame = Objects.requireNonNull(
                        frameObserver.nextFrame(), "recognition frame");
            } catch (Exception failure) {
                kernel.validationFailed("visual confirmation failed: "
                        + normalize(failure.getMessage(), failure.getClass().getSimpleName()));
                return rejected(kernel.lastReason().orElseThrow());
            }
            MoveConfirmationTracker.Outcome outcome = confirmation.observe(
                    clock.nanoTime(), currentTarget.orElseThrow(), frame);
            if (outcome.terminal()) {
                MoveConfirmationTracker.ConfirmationEvent event = confirmation.lastEvent()
                        .orElseThrow();
                String detail = event.recognitionCode()
                        .map(code -> outcome.detail() + " [" + code + "]")
                        .orElse(outcome.detail());
                return new ExecutionOutcome(
                        outcome.status() == MoveConfirmationTracker.Status.CONFIRMED,
                        detail,
                        Optional.of(outcome.status()),
                        event.recognitionCode(),
                        event.modelVersion()
                );
            }
            try {
                waiter.waitFor(CONFIRMATION_POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                kernel.validationFailed("visual confirmation interrupted");
                return rejected(kernel.lastReason().orElseThrow());
            }
        }
    }

    public AutomationState state() {
        return kernel.state();
    }

    public Optional<String> lastReason() {
        return kernel.lastReason();
    }

    public void stop(String reason) {
        kernel.emergencyStop(reason);
    }

    private boolean validCanonicalMove(char[][] board,
                                       BoardCoordinateMapper.Move move) {
        if (!validBoardShape(board)) {
            return false;
        }
        return XiangqiUtils.canGo(board,
                9 - move.fromRank(), move.fromFile(),
                9 - move.toRank(), move.toFile());
    }

    private boolean validVisualMove(char[][] board,
                                    MoveConfirmationTracker.GridMove move) {
        if (!validBoardShape(board)) {
            return false;
        }
        char movingPiece = board[move.fromRow()][move.fromFile()];
        char targetPiece = board[move.toRow()][move.toFile()];
        return movingPiece != ' '
                && (targetPiece == ' '
                || Character.isUpperCase(movingPiece) != Character.isUpperCase(targetPiece));
    }

    private boolean validBoardShape(char[][] board) {
        if (board.length != 10) {
            return false;
        }
        for (char[] row : board) {
            if (row == null || row.length != 9) {
                return false;
            }
        }
        return true;
    }

    private ExecutionOutcome rejected(String detail) {
        return new ExecutionOutcome(false, detail, Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    private String normalize(String detail, String fallback) {
        return detail == null || detail.isBlank() ? fallback : detail.trim();
    }

    @FunctionalInterface
    public interface FrameObserver {
        RecognitionResult nextFrame() throws Exception;
    }

    @FunctionalInterface
    interface MonotonicClock {
        long nanoTime();
    }

    @FunctionalInterface
    interface Waiter {
        void waitFor(long millis) throws InterruptedException;
    }

    public record ExecutionOutcome(boolean confirmed,
                                   String detail,
                                   Optional<MoveConfirmationTracker.Status> confirmationStatus,
                                   Optional<String> recognitionCode,
                                   Optional<String> modelVersion) {
        public ExecutionOutcome {
            if (detail == null || detail.isBlank()) {
                throw new IllegalArgumentException("detail must not be blank");
            }
            Objects.requireNonNull(confirmationStatus, "confirmationStatus");
            Objects.requireNonNull(recognitionCode, "recognitionCode");
            Objects.requireNonNull(modelVersion, "modelVersion");
        }
    }
}
