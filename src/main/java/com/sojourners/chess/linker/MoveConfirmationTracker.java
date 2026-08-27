package com.sojourners.chess.linker;

import com.sojourners.chess.automation.AutomationSafetyKernel;
import com.sojourners.chess.automation.AutomationState;
import com.sojourners.chess.automation.BoardCoordinateMapper;
import com.sojourners.chess.recognition.RecognitionResult;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Confirms one already-executed move from stable recognition results. This
 * class has no input-sending API, so a failure cannot retry the click.
 */
public final class MoveConfirmationTracker {

    private static final long MIN_TIMEOUT_MILLIS = 100;
    private static final long MAX_TIMEOUT_MILLIS = 10_000;

    private final AutomationSafetyKernel kernel;
    private final char[][] before;
    private final char[][] expectedAfter;
    private final BoardCoordinateMapper.TargetSnapshot expectedTarget;
    private final long startedAtNanos;
    private final long timeoutNanos;

    private Outcome terminalOutcome;
    private ConfirmationEvent lastEvent;

    public MoveConfirmationTracker(AutomationSafetyKernel kernel,
                                   char[][] positionBeforeMove,
                                   GridMove expectedMove,
                                   BoardCoordinateMapper.TargetSnapshot expectedTarget,
                                   long startedAtNanos,
                                   long timeoutMillis) {
        this.kernel = Objects.requireNonNull(kernel, "kernel");
        this.before = requireBoard(positionBeforeMove);
        Objects.requireNonNull(expectedMove, "expectedMove");
        this.expectedAfter = applyExpectedMove(before, expectedMove);
        this.expectedTarget = Objects.requireNonNull(expectedTarget, "expectedTarget");
        if (!expectedTarget.focused() || !expectedTarget.visible()) {
            throw new IllegalArgumentException("expected target must be focused and visible");
        }
        if (startedAtNanos < 0) {
            throw new IllegalArgumentException("startedAtNanos must not be negative");
        }
        if (timeoutMillis < MIN_TIMEOUT_MILLIS || timeoutMillis > MAX_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException("timeoutMillis must be between 100 and 10000");
        }
        if (kernel.state() != AutomationState.CONFIRMING) {
            throw new IllegalArgumentException("safety kernel must be confirming");
        }
        this.startedAtNanos = startedAtNanos;
        this.timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    }

    public synchronized Outcome observe(long nowNanos,
                                        BoardCoordinateMapper.TargetSnapshot currentTarget,
                                        RecognitionResult recognition) {
        Objects.requireNonNull(currentTarget, "currentTarget");
        Objects.requireNonNull(recognition, "recognition");
        if (terminalOutcome != null) {
            return terminalOutcome;
        }
        if (nowNanos < startedAtNanos) {
            throw new IllegalArgumentException("nowNanos precedes confirmation start");
        }
        long elapsedNanos = nowNanos - startedAtNanos;
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);

        if (kernel.state() != AutomationState.CONFIRMING) {
            return fail(Status.TARGET_CHANGED,
                    "safety state changed during confirmation", elapsedMillis, recognition);
        }
        if (!expectedTarget.equals(currentTarget)) {
            return fail(Status.TARGET_CHANGED,
                    "target identity, geometry, DPI, focus, or visibility changed",
                    elapsedMillis, recognition);
        }
        if (elapsedNanos >= timeoutNanos) {
            return fail(Status.ANIMATION_TIMEOUT,
                    "stable expected position was not observed before timeout",
                    elapsedMillis, recognition);
        }
        if (!recognition.accepted()) {
            RecognitionResult.Rejection rejection = recognition.rejection().orElseThrow();
            return waiting(
                    "recognition pending: " + rejection.reason(),
                    elapsedMillis,
                    Optional.of(rejection.reason().name()),
                    Optional.empty()
            );
        }

        RecognitionResult.AcceptedPosition position = recognition.position().orElseThrow();
        if (sameBoard(position.boardCopy(), expectedAfter)) {
            if (!kernel.confirmationAccepted()) {
                return fail(Status.TARGET_CHANGED,
                        "safety state changed before confirmation commit",
                        elapsedMillis, recognition);
            }
            return finish(Status.CONFIRMED, "expected move confirmed", elapsedMillis,
                    Optional.empty(), Optional.of(position.modelVersion()));
        }
        if (sameBoard(position.boardCopy(), before)) {
            return waiting("waiting for board animation to finish", elapsedMillis,
                    Optional.empty(), Optional.of(position.modelVersion()));
        }
        return fail(Status.POSITION_MISMATCH,
                "stable position does not match the expected move",
                elapsedMillis, recognition);
    }

    public synchronized Optional<ConfirmationEvent> lastEvent() {
        return Optional.ofNullable(lastEvent);
    }

    private Outcome waiting(String detail,
                            long elapsedMillis,
                            Optional<String> recognitionCode,
                            Optional<String> modelVersion) {
        lastEvent = new ConfirmationEvent(
                Status.WAITING, detail, elapsedMillis, recognitionCode, modelVersion);
        return new Outcome(Status.WAITING, detail, elapsedMillis);
    }

    private Outcome fail(Status status,
                         String detail,
                         long elapsedMillis,
                         RecognitionResult recognition) {
        Optional<String> recognitionCode = recognition.rejection()
                .map(rejection -> rejection.reason().name());
        Optional<String> modelVersion = recognition.position()
                .map(RecognitionResult.AcceptedPosition::modelVersion);
        kernel.validationFailed("confirmation " + status + ": " + detail);
        return finish(status, detail, elapsedMillis, recognitionCode, modelVersion);
    }

    private Outcome finish(Status status,
                           String detail,
                           long elapsedMillis,
                           Optional<String> recognitionCode,
                           Optional<String> modelVersion) {
        lastEvent = new ConfirmationEvent(
                status, detail, elapsedMillis, recognitionCode, modelVersion);
        terminalOutcome = new Outcome(status, detail, elapsedMillis);
        return terminalOutcome;
    }

    private char[][] applyExpectedMove(char[][] source, GridMove move) {
        char movingPiece = source[move.fromRow()][move.fromFile()];
        char targetPiece = source[move.toRow()][move.toFile()];
        if (movingPiece == ' ') {
            throw new IllegalArgumentException("expected visual move has an empty source");
        }
        if (targetPiece != ' '
                && Character.isUpperCase(movingPiece) == Character.isUpperCase(targetPiece)) {
            throw new IllegalArgumentException("expected visual move cannot capture the same side");
        }
        char[][] result = copy(source);
        result[move.toRow()][move.toFile()] = movingPiece;
        result[move.fromRow()][move.fromFile()] = ' ';
        return result;
    }

    private char[][] requireBoard(char[][] source) {
        if (source == null || source.length != 10) {
            throw new IllegalArgumentException("position must contain 10 rows");
        }
        for (char[] row : source) {
            if (row == null || row.length != 9) {
                throw new IllegalArgumentException("position rows must contain 9 files");
            }
        }
        return copy(source);
    }

    private boolean sameBoard(char[][] first, char[][] second) {
        if (first == null || first.length != second.length) {
            return false;
        }
        for (int row = 0; row < second.length; row++) {
            if (first[row] == null || first[row].length != second[row].length) {
                return false;
            }
            for (int file = 0; file < second[row].length; file++) {
                if (first[row][file] != second[row][file]) {
                    return false;
                }
            }
        }
        return true;
    }

    private char[][] copy(char[][] source) {
        char[][] result = new char[source.length][];
        for (int row = 0; row < source.length; row++) {
            result[row] = source[row].clone();
        }
        return result;
    }

    public enum Status {
        WAITING,
        CONFIRMED,
        ANIMATION_TIMEOUT,
        POSITION_MISMATCH,
        TARGET_CHANGED
    }

    public record GridMove(int fromFile, int fromRow, int toFile, int toRow) {
        public GridMove {
            requireSquare(fromFile, fromRow);
            requireSquare(toFile, toRow);
            if (fromFile == toFile && fromRow == toRow) {
                throw new IllegalArgumentException("move source and target must differ");
            }
        }

        private static void requireSquare(int file, int row) {
            if (file < 0 || file > 8 || row < 0 || row > 9) {
                throw new IllegalArgumentException("grid square is outside the 9x10 board");
            }
        }
    }

    public record Outcome(Status status, String detail, long elapsedMillis) {
        public Outcome {
            Objects.requireNonNull(status, "status");
            if (detail == null || detail.isBlank()) {
                throw new IllegalArgumentException("detail must not be blank");
            }
            if (elapsedMillis < 0) {
                throw new IllegalArgumentException("elapsedMillis must not be negative");
            }
        }

        public boolean terminal() {
            return status != Status.WAITING;
        }
    }

    public record ConfirmationEvent(Status status,
                                    String detail,
                                    long elapsedMillis,
                                    Optional<String> recognitionCode,
                                    Optional<String> modelVersion) {
        public ConfirmationEvent {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(detail, "detail");
            Objects.requireNonNull(recognitionCode, "recognitionCode");
            Objects.requireNonNull(modelVersion, "modelVersion");
        }
    }
}
