package com.sojourners.chess.linker;

import com.sojourners.chess.automation.BoardCoordinateMapper;
import com.sojourners.chess.recognition.RecognitionCandidate;
import com.sojourners.chess.recognition.RecognitionGate;
import com.sojourners.chess.recognition.RecognitionResult;
import com.sojourners.chess.util.XiangqiUtils;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WindowsAutomationE2EProbeTest {

    @Test
    void acceptsAutomaticRedAndBlackModes() {
        WindowsAutomationE2EProbe.ProbeArguments red =
                WindowsAutomationE2EProbe.parseArguments(new String[0]);
        WindowsAutomationE2EProbe.ProbeArguments black =
                WindowsAutomationE2EProbe.parseArguments(new String[]{"black"});

        assertFalse(red.explicitCalibration());
        assertFalse(red.blackAtBottom());
        assertFalse(black.explicitCalibration());
        assertTrue(black.blackAtBottom());
    }

    @Test
    void retainsTheExplicitDiagnosticModeAndRejectsAmbiguousArguments() {
        WindowsAutomationE2EProbe.ProbeArguments explicit =
                WindowsAutomationE2EProbe.parseArguments(
                        new String[]{"80ACA", "1531", "365", "432", "486", "black"});

        assertTrue(explicit.explicitCalibration());
        assertEquals(0x80ACAL, explicit.handleValue());
        assertEquals(new BoardCoordinateMapper.BoardBounds(1531, 365, 432, 486),
                explicit.screenBoardBounds());
        assertTrue(explicit.blackAtBottom());
        assertThrows(IllegalArgumentException.class,
                () -> WindowsAutomationE2EProbe.parseArguments(new String[]{"red"}));
        assertThrows(IllegalArgumentException.class,
                () -> WindowsAutomationE2EProbe.parseArguments(
                        new String[]{"80ACA", "1", "2", "3"}));
    }

    @Test
    void acceptsOnlyBoundedAutomaticEnduranceRuns() {
        WindowsAutomationE2EProbe.ProbeArguments endurance =
                WindowsAutomationE2EProbe.parseArguments(
                        new String[]{"endurance=1000"});

        assertFalse(endurance.explicitCalibration());
        assertFalse(endurance.blackAtBottom());
        assertEquals(1_000, endurance.moveCount());
        assertThrows(IllegalArgumentException.class,
                () -> WindowsAutomationE2EProbe.parseArguments(
                        new String[]{"endurance=0"}));
        assertThrows(IllegalArgumentException.class,
                () -> WindowsAutomationE2EProbe.parseArguments(
                        new String[]{"endurance=1001"}));
    }

    @Test
    void enduranceCycleAlternatesSidesAndReturnsToTheInitialPosition() {
        char[][] board = XiangqiUtils.fenToBoard(
                "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR");
        char[][] initial = copy(board);

        for (int index = 0; index < 4; index++) {
            WindowsAutomationE2EProbe.ProbeMove move =
                    WindowsAutomationE2EProbe.enduranceMove(index);
            WindowsAutomationE2EProbe.applyMove(board, move.visual());
        }

        assertTrue(Arrays.deepEquals(initial, board));
        assertEquals("a0a1", WindowsAutomationE2EProbe.enduranceMove(0).ucci());
        assertEquals("a9a8", WindowsAutomationE2EProbe.enduranceMove(1).ucci());
        assertEquals("a1a0", WindowsAutomationE2EProbe.enduranceMove(2).ucci());
        assertEquals("a8a9", WindowsAutomationE2EProbe.enduranceMove(3).ucci());
    }

    @Test
    void convertsAcceptedRecognitionBoundsToClientCalibration() {
        char[][] board = XiangqiUtils.fenToBoard(
                "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR");
        double[][] confidence = new double[10][9];
        for (double[] row : confidence) Arrays.fill(row, 0.99);
        RecognitionCandidate candidate = new RecognitionCandidate(
                1120, 840, 3_763_200,
                new RecognitionCandidate.BoardBounds(189, 181, 432, 486),
                board, confidence, 0.99, "test-model");
        RecognitionResult accepted = new RecognitionGate(
                RecognitionGate.Policy.safeDefaults()).evaluate(candidate);

        assertEquals(new BoardCoordinateMapper.BoardBounds(189, 181, 432, 486),
                WindowsAutomationE2EProbe.detectedBoardBounds(
                        accepted.position().orElseThrow()));
    }

    private char[][] copy(char[][] source) {
        char[][] result = new char[source.length][];
        for (int row = 0; row < source.length; row++) {
            result[row] = source[row].clone();
        }
        return result;
    }
}
