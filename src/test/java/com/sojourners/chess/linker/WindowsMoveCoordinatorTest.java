package com.sojourners.chess.linker;

import com.sojourners.chess.automation.AutomationState;
import com.sojourners.chess.automation.BoardCoordinateMapper;
import com.sojourners.chess.recognition.RecognitionCandidate;
import com.sojourners.chess.recognition.RecognitionGate;
import com.sojourners.chess.recognition.RecognitionResult;
import com.sojourners.chess.util.XiangqiUtils;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WindowsMoveCoordinatorTest {

    private static final WinDef.HWND HANDLE =
            new WinDef.HWND(Pointer.createConstant(0x4567));
    private static final BoardCoordinateMapper.ClientArea CLIENT =
            new BoardCoordinateMapper.ClientArea(100, 200, 1_000, 800);
    private static final BoardCoordinateMapper.ActivitySnapshot QUIET =
            new BoardCoordinateMapper.ActivitySnapshot(20, 30, 100);

    @Test
    void coordinatesOneExecutionAndStableVisualConfirmation() {
        FakeBridge bridge = new FakeBridge();
        WindowsAutomationTarget target = new WindowsAutomationTarget(HANDLE, bridge, millis -> { });
        char[][] before = standardBoard();
        char[][] after = rookA0ToA1(before);
        RecognitionGate.StabilityTracker stability = stableAt(before);
        List<RecognitionResult> frames = new ArrayList<>();
        frames.add(stability.evaluate(candidate(after)));
        frames.add(stability.evaluate(candidate(after)));
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        WindowsMoveCoordinator coordinator = new WindowsMoveCoordinator(
                target,
                () -> frames.removeFirst(),
                () -> clock.getAndAdd(10_000_000L),
                millis -> { }
        );

        assertTrue(coordinator.arm());
        WindowsMoveCoordinator.ExecutionOutcome outcome = coordinator.executeAndConfirm(
                calibration(target), before, before,
                new BoardCoordinateMapper.Move(0, 0, 0, 1),
                new MoveConfirmationTracker.GridMove(0, 9, 0, 8),
                0, 0, 500);

        assertAll(
                () -> assertTrue(outcome.confirmed()),
                () -> assertEquals(MoveConfirmationTracker.Status.CONFIRMED,
                        outcome.confirmationStatus().orElseThrow()),
                () -> assertEquals(AutomationState.OBSERVING, coordinator.state()),
                () -> assertEquals(6, bridge.events.size()),
                () -> assertTrue(frames.isEmpty())
        );
    }

    @Test
    void keepsCanonicalRulesSeparateFromBlackAtBottomVisualOrientation() {
        FakeBridge bridge = new FakeBridge();
        WindowsAutomationTarget target = new WindowsAutomationTarget(HANDLE, bridge, millis -> { });
        char[][] canonicalBefore = standardBoard();
        char[][] visualBefore = rotate180(canonicalBefore);
        char[][] visualAfter = copy(visualBefore);
        visualAfter[0][8] = ' ';
        visualAfter[1][8] = 'R';
        RecognitionGate.StabilityTracker stability = stableAt(visualBefore);
        List<RecognitionResult> frames = new ArrayList<>();
        frames.add(stability.evaluate(candidate(visualAfter)));
        frames.add(stability.evaluate(candidate(visualAfter)));
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        WindowsMoveCoordinator coordinator = new WindowsMoveCoordinator(
                target, () -> frames.removeFirst(),
                () -> clock.getAndAdd(10_000_000L), millis -> { });

        assertTrue(coordinator.arm());
        WindowsMoveCoordinator.ExecutionOutcome outcome = coordinator.executeAndConfirm(
                calibration(target, BoardCoordinateMapper.Orientation.BLACK_AT_BOTTOM),
                canonicalBefore,
                visualBefore,
                new BoardCoordinateMapper.Move(0, 0, 0, 1),
                new MoveConfirmationTracker.GridMove(8, 0, 8, 1),
                0, 0, 500);

        assertAll(
                () -> assertTrue(outcome.confirmed()),
                () -> assertEquals(AutomationState.OBSERVING, coordinator.state()),
                () -> assertEquals(6, bridge.events.size())
        );
    }

    @Test
    void confirmationTimeoutPausesAndCannotClickAgain() {
        FakeBridge bridge = new FakeBridge();
        WindowsAutomationTarget target = new WindowsAutomationTarget(HANDLE, bridge, millis -> { });
        char[][] before = standardBoard();
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        WindowsMoveCoordinator coordinator = new WindowsMoveCoordinator(
                target,
                () -> accepted(before),
                () -> clock.getAndAdd(250_000_000L),
                millis -> { }
        );

        assertTrue(coordinator.arm());
        WindowsMoveCoordinator.ExecutionOutcome timedOut = coordinator.executeAndConfirm(
                calibration(target), before, before,
                new BoardCoordinateMapper.Move(0, 0, 0, 1),
                new MoveConfirmationTracker.GridMove(0, 9, 0, 8),
                0, 0, 500);
        WindowsMoveCoordinator.ExecutionOutcome secondAttempt = coordinator.executeAndConfirm(
                calibration(target), before, before,
                new BoardCoordinateMapper.Move(0, 0, 0, 1),
                new MoveConfirmationTracker.GridMove(0, 9, 0, 8),
                0, 0, 500);

        assertAll(
                () -> assertFalse(timedOut.confirmed()),
                () -> assertEquals(MoveConfirmationTracker.Status.ANIMATION_TIMEOUT,
                        timedOut.confirmationStatus().orElseThrow()),
                () -> assertFalse(secondAttempt.confirmed()),
                () -> assertEquals(AutomationState.PAUSED, coordinator.state()),
                () -> assertEquals(6, bridge.events.size()),
                () -> assertTrue(coordinator.lastReason().orElseThrow()
                        .contains("ANIMATION_TIMEOUT"))
        );
    }

    @Test
    void timeoutPreservesTheLastRecognitionReasonWithoutImageData() {
        FakeBridge bridge = new FakeBridge();
        WindowsAutomationTarget target = new WindowsAutomationTarget(HANDLE, bridge, millis -> { });
        char[][] before = standardBoard();
        RecognitionCandidate lowConfidence = candidate(before);
        double[][] confidences = lowConfidence.squareConfidencesCopy();
        confidences[9][0] = 0.20;
        RecognitionResult rejected = new RecognitionGate(
                RecognitionGate.Policy.safeDefaults()).evaluate(new RecognitionCandidate(
                        lowConfidence.imageWidth(), lowConfidence.imageHeight(),
                        lowConfidence.inputBytes(), lowConfidence.boardBounds(),
                        lowConfidence.boardCopy(), confidences,
                        lowConfidence.boardConfidence(), lowConfidence.modelVersion()));
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        WindowsMoveCoordinator coordinator = new WindowsMoveCoordinator(
                target, () -> rejected,
                () -> clock.getAndAdd(250_000_000L), millis -> { });

        assertTrue(coordinator.arm());
        WindowsMoveCoordinator.ExecutionOutcome outcome = coordinator.executeAndConfirm(
                calibration(target), before, before,
                new BoardCoordinateMapper.Move(0, 0, 0, 1),
                new MoveConfirmationTracker.GridMove(0, 9, 0, 8),
                0, 0, 500);

        assertAll(
                () -> assertFalse(outcome.confirmed()),
                () -> assertEquals("LOW_PIECE_CONFIDENCE",
                        outcome.recognitionCode().orElseThrow()),
                () -> assertTrue(outcome.detail().contains("LOW_PIECE_CONFIDENCE")),
                () -> assertFalse(outcome.detail().contains("[["))
        );
    }

    @Test
    void invalidConfirmationTimeoutIsRejectedBeforeAnyInput() {
        FakeBridge bridge = new FakeBridge();
        WindowsAutomationTarget target = new WindowsAutomationTarget(HANDLE, bridge, millis -> { });
        char[][] before = standardBoard();
        WindowsMoveCoordinator coordinator = new WindowsMoveCoordinator(
                target, () -> accepted(before), System::nanoTime, millis -> { });

        assertTrue(coordinator.arm());
        WindowsMoveCoordinator.ExecutionOutcome outcome = coordinator.executeAndConfirm(
                calibration(target), before, before,
                new BoardCoordinateMapper.Move(0, 0, 0, 1),
                new MoveConfirmationTracker.GridMove(0, 9, 0, 8),
                0, 0, 99);

        assertAll(
                () -> assertFalse(outcome.confirmed()),
                () -> assertEquals(AutomationState.PAUSED, coordinator.state()),
                () -> assertTrue(bridge.events.isEmpty()),
                () -> assertTrue(outcome.detail().contains("confirmation timeout"))
        );
    }

    @Test
    void convertsRecognizedImageBoundsIntoPhysicalClientIntersections() {
        BoardCoordinateMapper.BoardBounds bounds =
                WindowsGraphLinker.calibrationBoardBounds(
                        new Rectangle(100, 50, 800, 900),
                        1_000,
                        1_100,
                        new BoardCoordinateMapper.ClientArea(10, 20, 2_000, 2_200));

        assertEquals(new BoardCoordinateMapper.BoardBounds(367, 270, 1_266, 1_460), bounds);
    }

    @Test
    void executionDiagnosticsCannotCarryBoardsOrScreenshots() {
        Set<String> fields = Arrays.stream(
                        WindowsMoveCoordinator.ExecutionOutcome.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase())
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "confirmed", "detail", "confirmationstatus",
                "recognitioncode", "modelversion"), fields);
        assertFalse(fields.contains("board"));
        assertFalse(fields.contains("image"));
        assertFalse(fields.contains("screenshot"));
    }

    private BoardCoordinateMapper.Calibration calibration(WindowsAutomationTarget target) {
        return calibration(target, BoardCoordinateMapper.Orientation.RED_AT_BOTTOM);
    }

    private BoardCoordinateMapper.Calibration calibration(
            WindowsAutomationTarget target,
            BoardCoordinateMapper.Orientation orientation) {
        return new BoardCoordinateMapper.Calibration(
                target.authorization().targetId(),
                target.authorization().targetRevision(),
                CLIENT,
                new BoardCoordinateMapper.BoardBounds(50, 40, 800, 630),
                144,
                orientation
        );
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

    private char[][] copy(char[][] source) {
        char[][] result = new char[source.length][];
        for (int row = 0; row < source.length; row++) {
            result[row] = source[row].clone();
        }
        return result;
    }

    private char[][] rotate180(char[][] source) {
        char[][] result = new char[10][9];
        for (int row = 0; row < 10; row++) {
            for (int file = 0; file < 9; file++) {
                result[9 - row][8 - file] = source[row][file];
            }
        }
        return result;
    }

    private static final class FakeBridge implements WindowsAutomationTarget.NativeBridge {
        private final WindowsAutomationTarget.WindowObservation window =
                new WindowsAutomationTarget.WindowObservation(
                        true, 42, 77, CLIENT, 144, true, true);
        private final List<WindowsAutomationTarget.MouseEvent> events = new ArrayList<>();

        @Override
        public WindowsAutomationTarget.WindowObservation inspect(WinDef.HWND handle) {
            return window;
        }

        @Override
        public BoardCoordinateMapper.ActivitySnapshot activity() {
            return QUIET;
        }

        @Override
        public boolean send(WinDef.HWND handle,
                            WindowsAutomationTarget.MouseEvent event,
                            int timeoutMillis) {
            events.add(event);
            return true;
        }
    }
}
