package com.sojourners.chess.recognition;

import com.sojourners.chess.board.ChessBoard;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RecognitionGateTest {

    private static final String MODEL_VERSION = "yolov11.onnx@sha256:099c4ef0";
    private final RecognitionGate gate = new RecognitionGate(RecognitionGate.Policy.safeDefaults());

    @Test
    void acceptsAHighConfidencePositionWithExplainableMetadataAndDefensiveCopies() {
        char[][] board = standardBoard();
        double[][] confidences = confidences(0.97);
        RecognitionCandidate candidate = candidate(board, confidences, 0.98);
        board[0][0] = ' ';
        confidences[0][0] = 0.01;

        RecognitionResult result = gate.evaluate(candidate);
        RecognitionResult.AcceptedPosition position = result.position().orElseThrow();
        char[][] exported = position.boardCopy();
        exported[0][0] = ' ';

        assertAll(
                () -> assertTrue(result.accepted()),
                () -> assertTrue(result.rejection().isEmpty()),
                () -> assertEquals('r', position.pieceAt(0, 0)),
                () -> assertEquals('r', position.boardCopy()[0][0]),
                () -> assertEquals(0.98, position.boardConfidence()),
                () -> assertEquals(0.97, position.minimumPieceConfidence()),
                () -> assertEquals(new RecognitionCandidate.BoardBounds(100, 50, 800, 900),
                        position.boardBounds()),
                () -> assertEquals("yolov11.onnx@sha256:099c4ef0", position.modelVersion())
        );
    }

    @Test
    void acceptsACoherentlyRotatedBlackAtBottomPosition() {
        char[][] rotated = rotate180(standardBoard());

        RecognitionResult result = gate.evaluate(
                candidate(rotated, confidences(0.99), 0.99));

        assertTrue(result.accepted());
    }

    @Test
    void rejectsLowBoardAndPieceConfidenceWithSpecificReasons() {
        RecognitionResult lowBoard = gate.evaluate(candidate(
                standardBoard(), confidences(0.99), 0.89));

        double[][] oneWeakPiece = confidences(0.99);
        oneWeakPiece[9][0] = 0.79;
        RecognitionResult lowPiece = gate.evaluate(candidate(
                standardBoard(), oneWeakPiece, 0.99));

        assertAll(
                () -> assertEquals(RecognitionResult.RejectionReason.LOW_BOARD_CONFIDENCE,
                        lowBoard.rejection().orElseThrow().reason()),
                () -> assertEquals(RecognitionResult.RejectionReason.LOW_PIECE_CONFIDENCE,
                        lowPiece.rejection().orElseThrow().reason()),
                () -> assertTrue(lowPiece.rejection().orElseThrow().detail().contains("file=0,row=9"))
        );
    }

    @Test
    void rejectsMissingGeneralsExcessPiecesAndFacingGenerals() {
        char[][] missingGeneral = standardBoard();
        missingGeneral[0][4] = ' ';

        char[][] excessRooks = standardBoard();
        excessRooks[4][1] = 'R';

        char[][] facingGenerals = emptyBoard();
        facingGenerals[0][4] = 'k';
        facingGenerals[9][4] = 'K';

        assertAll(
                () -> assertRejected(missingGeneral,
                        RecognitionResult.RejectionReason.GENERAL_COUNT_INVALID),
                () -> assertRejected(excessRooks,
                        RecognitionResult.RejectionReason.PIECE_COUNT_INVALID),
                () -> assertRejected(facingGenerals,
                        RecognitionResult.RejectionReason.FACING_GENERALS)
        );
    }

    @Test
    void rejectsMalformedBoardsUnsupportedPiecesAndConfidenceShapes() {
        char[][] shortBoard = new char[9][9];
        double[][] shortConfidences = new double[9][9];
        RecognitionResult malformed = gate.evaluate(candidate(shortBoard, shortConfidences, 0.99));

        char[][] unsupported = standardBoard();
        unsupported[4][4] = 'x';
        RecognitionResult badPiece = gate.evaluate(candidate(unsupported, confidences(0.99), 0.99));

        double[][] shortConfidence = new double[10][8];
        RecognitionResult badConfidenceShape = gate.evaluate(
                candidate(standardBoard(), shortConfidence, 0.99));

        assertAll(
                () -> assertReason(malformed, RecognitionResult.RejectionReason.BOARD_SHAPE_INVALID),
                () -> assertReason(badPiece, RecognitionResult.RejectionReason.UNSUPPORTED_PIECE),
                () -> assertReason(badConfidenceShape,
                        RecognitionResult.RejectionReason.CONFIDENCE_SHAPE_INVALID)
        );
    }

    @Test
    void rejectsImagesAndBoundsOutsideConfiguredResourceLimits() {
        RecognitionGate.Policy policy = RecognitionGate.Policy.safeDefaults();
        RecognitionCandidate oversized = new RecognitionCandidate(
                9_000, 1_000, 1_000_000,
                new RecognitionCandidate.BoardBounds(0, 0, 800, 900),
                standardBoard(), confidences(0.99), 0.99,
                "yolov11.onnx@sha256:099c4ef0"
        );
        RecognitionCandidate tooManyBytes = new RecognitionCandidate(
                1_000, 1_100, 33L * 1024 * 1024,
                new RecognitionCandidate.BoardBounds(100, 50, 800, 900),
                standardBoard(), confidences(0.99), 0.99,
                "yolov11.onnx@sha256:099c4ef0"
        );
        RecognitionCandidate outsideBounds = new RecognitionCandidate(
                1_000, 1_100, 1_000_000,
                new RecognitionCandidate.BoardBounds(300, 300, 800, 900),
                standardBoard(), confidences(0.99), 0.99,
                "yolov11.onnx@sha256:099c4ef0"
        );

        assertAll(
                () -> assertTrue(policy.permitsInput(1_000, 1_100, 1_000_000)),
                () -> assertFalse(policy.permitsInput(9_000, 1_000, 1_000_000)),
                () -> assertFalse(policy.permitsInput(1_000, 1_100, 33L * 1024 * 1024)),
                () -> assertReason(gate.evaluate(oversized),
                        RecognitionResult.RejectionReason.INPUT_TOO_LARGE),
                () -> assertReason(gate.evaluate(tooManyBytes),
                        RecognitionResult.RejectionReason.INPUT_TOO_LARGE),
                () -> assertReason(gate.evaluate(outsideBounds),
                        RecognitionResult.RejectionReason.BOUNDS_OUTSIDE_IMAGE)
        );
    }

    @Test
    void acceptsOneMoveButRejectsAJumpFromThePreviousAcceptedPosition() {
        RecognitionResult.AcceptedPosition previous = gate.evaluate(
                candidate(standardBoard(), confidences(0.99), 0.99))
                .position().orElseThrow();

        char[][] oneMove = standardBoard();
        oneMove[9][0] = ' ';
        oneMove[8][0] = 'R';
        RecognitionResult moved = gate.evaluate(
                candidate(oneMove, confidences(0.99), 0.99), previous);

        char[][] jump = standardBoard();
        jump[9][0] = ' ';
        jump[8][0] = 'R';
        jump[0][0] = ' ';
        jump[1][0] = 'r';
        RecognitionResult jumped = gate.evaluate(
                candidate(jump, confidences(0.99), 0.99), previous);

        assertAll(
                () -> assertTrue(moved.accepted()),
                () -> assertFalse(jumped.accepted()),
                () -> assertReason(jumped, RecognitionResult.RejectionReason.POSITION_JUMP)
        );
    }

    @Test
    void stabilityTrackerRequiresTwoMatchingFramesAndExplicitlyResetsNewGames() {
        RecognitionGate.StabilityTracker tracker = gate.newStabilityTracker();
        RecognitionCandidate initial = candidate(standardBoard(), confidences(0.99), 0.99);

        RecognitionResult firstInitial = tracker.evaluate(initial);
        RecognitionResult stableInitial = tracker.evaluate(initial);
        RecognitionResult repeatedInitial = tracker.evaluate(initial);

        char[][] movedBoard = standardBoard();
        movedBoard[9][0] = ' ';
        movedBoard[8][0] = 'R';
        RecognitionCandidate moved = candidate(movedBoard, confidences(0.99), 0.99);
        RecognitionResult firstMove = tracker.evaluate(moved);
        RecognitionResult stableMove = tracker.evaluate(moved);

        tracker.reset();
        RecognitionResult firstAfterReset = tracker.evaluate(initial);

        assertAll(
                () -> assertReason(firstInitial, RecognitionResult.RejectionReason.UNSTABLE_FRAME),
                () -> assertTrue(stableInitial.accepted()),
                () -> assertTrue(repeatedInitial.accepted()),
                () -> assertReason(firstMove, RecognitionResult.RejectionReason.UNSTABLE_FRAME),
                () -> assertTrue(stableMove.accepted()),
                () -> assertReason(firstAfterReset, RecognitionResult.RejectionReason.UNSTABLE_FRAME)
        );
    }

    @Test
    void mapsRealYoloDetectionScoresIntoAnAcceptedCandidate() {
        char[][] expected = standardBoard();
        List<YoloRecognitionMapper.Detection> detections = detectionsFor(expected, 0.97);

        RecognitionCandidate candidate = YoloRecognitionMapper.map(
                1_000, 1_100, 1_000_000, detections, MODEL_VERSION);
        RecognitionResult result = gate.evaluate(candidate);

        assertAll(
                () -> assertTrue(result.accepted()),
                () -> assertEquals(expected[0][0], candidate.boardCopy()[0][0]),
                () -> assertEquals(0.98, candidate.boardConfidence()),
                () -> assertEquals(0.97, result.position().orElseThrow().minimumPieceConfidence()),
                () -> assertEquals(new RecognitionCandidate.BoardBounds(100, 50, 800, 900),
                        candidate.boardBounds())
        );
    }

    @Test
    void keepsTheHighestConfidenceYoloDetectionWhenClassesCollide() {
        List<YoloRecognitionMapper.Detection> detections = new ArrayList<>();
        detections.add(boardDetection());
        detections.add(pieceDetection('R', 0, 9, 0.85));
        detections.add(pieceDetection('N', 0, 9, 0.96));

        RecognitionCandidate candidate = YoloRecognitionMapper.map(
                1_000, 1_100, 1_000_000, detections, MODEL_VERSION);

        assertAll(
                () -> assertEquals('N', candidate.boardCopy()[9][0]),
                () -> assertEquals(0.96, candidate.squareConfidencesCopy()[9][0])
        );
    }

    @Test
    void ignoresYoloPieceCentersOutsideTheNineByTenGrid() {
        List<YoloRecognitionMapper.Detection> detections = new ArrayList<>();
        detections.add(boardDetection());
        detections.add(new YoloRecognitionMapper.Detection('R', -100, -100,
                40, 40, 0.99));

        RecognitionCandidate candidate = YoloRecognitionMapper.map(
                1_000, 1_100, 1_000_000, detections, MODEL_VERSION);

        assertTrue(allEmpty(candidate.boardCopy()));
    }

    @Test
    void missingYoloBoardDetectionProducesAnExplainableRejection() {
        RecognitionCandidate candidate = YoloRecognitionMapper.map(
                1_000, 1_100, 1_000_000,
                List.of(pieceDetection('R', 0, 9, 0.99)), MODEL_VERSION);

        RecognitionResult result = gate.evaluate(candidate);

        assertAll(
                () -> assertNull(candidate.boardBounds()),
                () -> assertEquals(RecognitionResult.RejectionReason.BOUNDS_OUTSIDE_IMAGE,
                        result.rejection().orElseThrow().reason())
        );
    }

    @Test
    void choosesTheLargestYoloBoardBoxAndItsOwnConfidence() {
        List<YoloRecognitionMapper.Detection> detections = new ArrayList<>();
        detections.add(new YoloRecognitionMapper.Detection('0', 300, 300,
                200, 300, 0.99));
        detections.add(boardDetection());

        RecognitionCandidate candidate = YoloRecognitionMapper.map(
                1_000, 1_100, 1_000_000, detections, MODEL_VERSION);

        assertAll(
                () -> assertEquals(new RecognitionCandidate.BoardBounds(100, 50, 800, 900),
                        candidate.boardBounds()),
                () -> assertEquals(0.98, candidate.boardConfidence())
        );
    }

    private void assertRejected(char[][] board, RecognitionResult.RejectionReason reason) {
        assertReason(gate.evaluate(candidate(board, confidences(0.99), 0.99)), reason);
    }

    private void assertReason(RecognitionResult result, RecognitionResult.RejectionReason reason) {
        assertEquals(reason, result.rejection().orElseThrow().reason());
    }

    private RecognitionCandidate candidate(char[][] board, double[][] confidences,
                                           double boardConfidence) {
        return new RecognitionCandidate(
                1_000, 1_100, 1_000_000,
                new RecognitionCandidate.BoardBounds(100, 50, 800, 900),
                board, confidences, boardConfidence,
                "yolov11.onnx@sha256:099c4ef0"
        );
    }

    private char[][] standardBoard() {
        char[][] board = emptyBoard();
        ChessBoard.initChessBoard(board);
        return board;
    }

    private char[][] emptyBoard() {
        char[][] board = new char[10][9];
        for (char[] row : board) {
            Arrays.fill(row, ' ');
        }
        return board;
    }

    private double[][] confidences(double value) {
        double[][] confidences = new double[10][9];
        for (double[] row : confidences) {
            Arrays.fill(row, value);
        }
        return confidences;
    }

    private List<YoloRecognitionMapper.Detection> detectionsFor(char[][] board,
                                                                double confidence) {
        List<YoloRecognitionMapper.Detection> detections = new ArrayList<>();
        detections.add(boardDetection());
        for (int row = 0; row < 10; row++) {
            for (int file = 0; file < 9; file++) {
                if (board[row][file] != ' ') {
                    detections.add(pieceDetection(board[row][file], file, row, confidence));
                }
            }
        }
        return detections;
    }

    private YoloRecognitionMapper.Detection boardDetection() {
        return new YoloRecognitionMapper.Detection('0', 500, 500,
                800, 900, 0.98);
    }

    private YoloRecognitionMapper.Detection pieceDetection(char piece, int file, int row,
                                                           double confidence) {
        return new YoloRecognitionMapper.Detection(piece,
                100 + file * 100, 50 + row * 100, 42, 42, confidence);
    }

    private boolean allEmpty(char[][] board) {
        for (char[] row : board) {
            for (char piece : row) {
                if (piece != ' ') {
                    return false;
                }
            }
        }
        return true;
    }

    private char[][] rotate180(char[][] source) {
        char[][] rotated = new char[10][9];
        for (int row = 0; row < 10; row++) {
            for (int file = 0; file < 9; file++) {
                rotated[9 - row][8 - file] = source[row][file];
            }
        }
        return rotated;
    }
}
