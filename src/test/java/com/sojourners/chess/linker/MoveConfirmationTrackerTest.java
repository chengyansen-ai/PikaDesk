package com.sojourners.chess.linker;

import com.sojourners.chess.automation.AutomationSafetyKernel;
import com.sojourners.chess.automation.AutomationState;
import com.sojourners.chess.automation.BoardCoordinateMapper;
import com.sojourners.chess.recognition.RecognitionCandidate;
import com.sojourners.chess.recognition.RecognitionGate;
import com.sojourners.chess.recognition.RecognitionResult;
import com.sojourners.chess.util.XiangqiUtils;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MoveConfirmationTrackerTest {

    private static final long START_NANOS = 1_000_000_000L;
    private static final BoardCoordinateMapper.TargetSnapshot TARGET =
            new BoardCoordinateMapper.TargetSnapshot(
                    "local-test-board:4242", 7,
                    new BoardCoordinateMapper.ClientArea(100, 200, 1_000, 800),
                    144, true, true);

    @Test
    void confirmsOnlyAfterTheExpectedPositionBecomesStable() {
        char[][] before = standardBoard();
        char[][] after = rookA0ToA1(before);
        AutomationSafetyKernel kernel = confirmingKernel();
        MoveConfirmationTracker tracker = tracker(kernel, before);
        RecognitionGate.StabilityTracker stability = stableAt(before);

        MoveConfirmationTracker.Outcome unchanged = tracker.observe(
                START_NANOS + 10_000_000L, TARGET, accepted(before));
        MoveConfirmationTracker.Outcome firstChangedFrame = tracker.observe(
                START_NANOS + 20_000_000L, TARGET, stability.evaluate(candidate(after)));
        MoveConfirmationTracker.Outcome stableChangedFrame = tracker.observe(
                START_NANOS + 30_000_000L, TARGET, stability.evaluate(candidate(after)));

        assertAll(
                () -> assertEquals(MoveConfirmationTracker.Status.WAITING, unchanged.status()),
                () -> assertEquals(MoveConfirmationTracker.Status.WAITING,
                        firstChangedFrame.status()),
                () -> assertEquals(MoveConfirmationTracker.Status.CONFIRMED,
                        stableChangedFrame.status()),
                () -> assertTrue(stableChangedFrame.terminal()),
                () -> assertEquals(AutomationState.OBSERVING, kernel.state()),
                () -> assertEquals("yolov11@test", tracker.lastEvent()
                        .orElseThrow().modelVersion().orElseThrow())
        );
    }

    @Test
    void animationTimeoutPausesWithoutRetryingAnyExternalEvent() {
        char[][] before = standardBoard();
        AutomationSafetyKernel kernel = confirmingKernel();
        MoveConfirmationTracker tracker = tracker(kernel, before);
        AtomicInteger laterEvents = new AtomicInteger();

        MoveConfirmationTracker.Outcome timedOut = tracker.observe(
                START_NANOS + 500_000_000L, TARGET, accepted(before));
        boolean retried = kernel.executeOne(permit -> permit.send(laterEvents::incrementAndGet));

        assertAll(
                () -> assertEquals(MoveConfirmationTracker.Status.ANIMATION_TIMEOUT,
                        timedOut.status()),
                () -> assertTrue(timedOut.terminal()),
                () -> assertEquals(AutomationState.PAUSED, kernel.state()),
                () -> assertFalse(retried),
                () -> assertEquals(0, laterEvents.get()),
                () -> assertTrue(kernel.lastReason().orElseThrow().contains("ANIMATION_TIMEOUT"))
        );
    }

    @Test
    void aDifferentStableLegalMoveIsAPositionMismatch() {
        char[][] before = standardBoard();
        char[][] differentMove = horseB0ToC2(before);
        AutomationSafetyKernel kernel = confirmingKernel();
        MoveConfirmationTracker tracker = tracker(kernel, before);
        RecognitionGate.StabilityTracker stability = stableAt(before);
        stability.evaluate(candidate(differentMove));

        MoveConfirmationTracker.Outcome mismatch = tracker.observe(
                START_NANOS + 50_000_000L, TARGET,
                stability.evaluate(candidate(differentMove)));

        assertAll(
                () -> assertEquals(MoveConfirmationTracker.Status.POSITION_MISMATCH,
                        mismatch.status()),
                () -> assertTrue(mismatch.terminal()),
                () -> assertEquals(AutomationState.PAUSED, kernel.state()),
                () -> assertTrue(kernel.lastReason().orElseThrow().contains("POSITION_MISMATCH"))
        );
    }

    @Test
    void movingOrUnfocusingTheTargetFailsConfirmationImmediately() {
        char[][] before = standardBoard();
        AutomationSafetyKernel movedKernel = confirmingKernel();
        MoveConfirmationTracker movedTracker = tracker(movedKernel, before);
        BoardCoordinateMapper.TargetSnapshot moved = new BoardCoordinateMapper.TargetSnapshot(
                TARGET.targetId(), TARGET.targetRevision(),
                new BoardCoordinateMapper.ClientArea(101, 200, 1_000, 800),
                144, true, true);
        AutomationSafetyKernel unfocusedKernel = confirmingKernel();
        MoveConfirmationTracker unfocusedTracker = tracker(unfocusedKernel, before);
        BoardCoordinateMapper.TargetSnapshot unfocused = new BoardCoordinateMapper.TargetSnapshot(
                TARGET.targetId(), TARGET.targetRevision(), TARGET.clientArea(),
                144, false, true);

        MoveConfirmationTracker.Outcome movedOutcome = movedTracker.observe(
                START_NANOS + 1, moved, accepted(before));
        MoveConfirmationTracker.Outcome unfocusedOutcome = unfocusedTracker.observe(
                START_NANOS + 1, unfocused, accepted(before));

        assertAll(
                () -> assertEquals(MoveConfirmationTracker.Status.TARGET_CHANGED,
                        movedOutcome.status()),
                () -> assertEquals(MoveConfirmationTracker.Status.TARGET_CHANGED,
                        unfocusedOutcome.status()),
                () -> assertEquals(AutomationState.PAUSED, movedKernel.state()),
                () -> assertEquals(AutomationState.PAUSED, unfocusedKernel.state())
        );
    }

    @Test
    void terminalFailureIsStickyEvenIfExpectedPositionAppearsLater() {
        char[][] before = standardBoard();
        AutomationSafetyKernel kernel = confirmingKernel();
        MoveConfirmationTracker tracker = tracker(kernel, before);

        MoveConfirmationTracker.Outcome timedOut = tracker.observe(
                START_NANOS + 500_000_000L, TARGET, accepted(before));
        MoveConfirmationTracker.Outcome lateExpected = tracker.observe(
                START_NANOS + 600_000_000L, TARGET, accepted(rookA0ToA1(before)));

        assertAll(
                () -> assertEquals(timedOut, lateExpected),
                () -> assertEquals(MoveConfirmationTracker.Status.ANIMATION_TIMEOUT,
                        lateExpected.status()),
                () -> assertEquals(AutomationState.PAUSED, kernel.state())
        );
    }

    @Test
    void diagnosticsCannotContainAboardOrScreenshotPayload() {
        Set<String> fields = Arrays.stream(
                        MoveConfirmationTracker.ConfirmationEvent.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase())
                .collect(Collectors.toSet());

        assertAll(
                () -> assertEquals(Set.of(
                                "status", "detail", "elapsedmillis",
                                "recognitioncode", "modelversion"), fields),
                () -> assertFalse(fields.contains("board")),
                () -> assertFalse(fields.contains("image")),
                () -> assertFalse(fields.contains("screenshot"))
        );
    }

    private MoveConfirmationTracker tracker(AutomationSafetyKernel kernel, char[][] before) {
        return new MoveConfirmationTracker(
                kernel,
                before,
                new MoveConfirmationTracker.GridMove(0, 9, 0, 8),
                TARGET,
                START_NANOS,
                500
        );
    }

    private AutomationSafetyKernel confirmingKernel() {
        AutomationSafetyKernel kernel = new AutomationSafetyKernel();
        assertTrue(kernel.arm(new AutomationSafetyKernel.Authorization(
                TARGET.targetId(), TARGET.targetRevision())));
        assertTrue(kernel.beginObservation());
        assertTrue(kernel.recognitionAccepted());
        assertTrue(kernel.beginThinking());
        assertTrue(kernel.readyToExecute());
        assertTrue(kernel.executeOne(permit -> assertTrue(permit.send(() -> { }))));
        return kernel;
    }

    private RecognitionGate.StabilityTracker stableAt(char[][] board) {
        RecognitionGate.StabilityTracker stability =
                new RecognitionGate(RecognitionGate.Policy.safeDefaults()).newStabilityTracker();
        stability.evaluate(candidate(board));
        assertTrue(stability.evaluate(candidate(board)).accepted());
        return stability;
    }

    private RecognitionResult accepted(char[][] board) {
        return new RecognitionGate(RecognitionGate.Policy.safeDefaults()).evaluate(candidate(board));
    }

    private RecognitionCandidate candidate(char[][] board) {
        double[][] confidences = new double[10][9];
        for (double[] row : confidences) {
            Arrays.fill(row, 0.99);
        }
        return new RecognitionCandidate(
                1_000, 1_100, 1_000_000,
                new RecognitionCandidate.BoardBounds(100, 50, 800, 900),
                board, confidences, 0.99, "yolov11@test");
    }

    private char[][] standardBoard() {
        return XiangqiUtils.fenToBoard(
                "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR");
    }

    private char[][] rookA0ToA1(char[][] source) {
        char[][] board = copy(source);
        board[9][0] = ' ';
        board[8][0] = 'R';
        return board;
    }

    private char[][] horseB0ToC2(char[][] source) {
        char[][] board = copy(source);
        board[9][1] = ' ';
        board[7][2] = 'N';
        return board;
    }

    private char[][] copy(char[][] source) {
        char[][] result = new char[source.length][];
        for (int row = 0; row < source.length; row++) {
            result[row] = source[row].clone();
        }
        return result;
    }
}
