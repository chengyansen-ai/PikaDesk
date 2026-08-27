package com.sojourners.chess.recognition;

import java.util.HashMap;
import java.util.Map;

/**
 * Pure fail-closed validation between model inference and automation state.
 */
public final class RecognitionGate {

    private static final String SUPPORTED_PIECES = "rnbakcpRNBAKCP ";
    private static final int CHANGED_SQUARES_PER_MOVE = 2;
    private static final Map<Character, Integer> MAX_PIECES = Map.ofEntries(
            Map.entry('k', 1), Map.entry('r', 2), Map.entry('n', 2),
            Map.entry('b', 2), Map.entry('a', 2), Map.entry('c', 2), Map.entry('p', 5),
            Map.entry('K', 1), Map.entry('R', 2), Map.entry('N', 2),
            Map.entry('B', 2), Map.entry('A', 2), Map.entry('C', 2), Map.entry('P', 5)
    );

    private final Policy policy;

    public RecognitionGate(Policy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("policy is required");
        }
        this.policy = policy;
    }

    public RecognitionResult evaluate(RecognitionCandidate candidate) {
        return evaluate(candidate, null);
    }

    public StabilityTracker newStabilityTracker() {
        return new StabilityTracker(this);
    }

    public RecognitionResult evaluate(RecognitionCandidate candidate,
                                      RecognitionResult.AcceptedPosition previousPosition) {
        RecognitionResult rejection = validateInput(candidate);
        if (rejection != null) {
            return rejection;
        }
        rejection = validateShapes(candidate);
        if (rejection != null) {
            return rejection;
        }
        if (!validConfidence(candidate.boardConfidence())) {
            return reject(RecognitionResult.RejectionReason.CONFIDENCE_INVALID,
                    "board confidence must be finite and between 0 and 1");
        }
        if (candidate.boardConfidence() < policy.minimumBoardConfidence) {
            return reject(RecognitionResult.RejectionReason.LOW_BOARD_CONFIDENCE,
                    "board confidence " + candidate.boardConfidence()
                            + " is below " + policy.minimumBoardConfidence);
        }

        ValidationSummary summary = validatePosition(candidate);
        if (summary.rejection != null) {
            return summary.rejection;
        }
        if (previousPosition != null) {
            rejection = validateTransition(previousPosition, candidate);
            if (rejection != null) {
                return rejection;
            }
        }
        return RecognitionResult.accepted(candidate, summary.minimumPieceConfidence);
    }

    private RecognitionResult validateInput(RecognitionCandidate candidate) {
        if (candidate == null || candidate.imageWidth() <= 0 || candidate.imageHeight() <= 0
                || candidate.inputBytes() < 0) {
            return reject(RecognitionResult.RejectionReason.INPUT_INVALID,
                    "image dimensions and input byte count must be valid");
        }
        if (!policy.permitsInput(candidate.imageWidth(), candidate.imageHeight(),
                candidate.inputBytes())) {
            return reject(RecognitionResult.RejectionReason.INPUT_TOO_LARGE,
                    "image exceeds configured dimension, pixel, or byte limits");
        }
        RecognitionCandidate.BoardBounds bounds = candidate.boardBounds();
        if (bounds == null || bounds.x() < 0 || bounds.y() < 0
                || bounds.width() <= 0 || bounds.height() <= 0
                || (long) bounds.x() + bounds.width() > candidate.imageWidth()
                || (long) bounds.y() + bounds.height() > candidate.imageHeight()) {
            return reject(RecognitionResult.RejectionReason.BOUNDS_OUTSIDE_IMAGE,
                    "board bounds must be positive and contained by the input image");
        }
        if (candidate.modelVersion() == null || candidate.modelVersion().isBlank()
                || candidate.modelVersion().length() > 128) {
            return reject(RecognitionResult.RejectionReason.MODEL_VERSION_MISSING,
                    "a bounded model version or hash is required");
        }
        return null;
    }

    private RecognitionResult validateShapes(RecognitionCandidate candidate) {
        if (candidate.boardRows() != 10) {
            return reject(RecognitionResult.RejectionReason.BOARD_SHAPE_INVALID,
                    "recognized board must have exactly 10 rows");
        }
        for (int row = 0; row < 10; row++) {
            if (candidate.boardColumns(row) != 9) {
                return reject(RecognitionResult.RejectionReason.BOARD_SHAPE_INVALID,
                        "recognized board row " + row + " must have exactly 9 files");
            }
        }
        if (candidate.confidenceRows() != 10) {
            return reject(RecognitionResult.RejectionReason.CONFIDENCE_SHAPE_INVALID,
                    "confidence grid must have exactly 10 rows");
        }
        for (int row = 0; row < 10; row++) {
            if (candidate.confidenceColumns(row) != 9) {
                return reject(RecognitionResult.RejectionReason.CONFIDENCE_SHAPE_INVALID,
                        "confidence row " + row + " must have exactly 9 files");
            }
        }
        return null;
    }

    private ValidationSummary validatePosition(RecognitionCandidate candidate) {
        Map<Character, Integer> counts = new HashMap<>();
        int redPieces = 0;
        int blackPieces = 0;
        int redGeneralFile = -1;
        int redGeneralRow = -1;
        int blackGeneralFile = -1;
        int blackGeneralRow = -1;
        double minimumConfidence = 1.0;

        for (int row = 0; row < 10; row++) {
            for (int file = 0; file < 9; file++) {
                char piece = candidate.pieceAt(file, row);
                if (SUPPORTED_PIECES.indexOf(piece) < 0) {
                    return ValidationSummary.rejected(reject(
                            RecognitionResult.RejectionReason.UNSUPPORTED_PIECE,
                            "unsupported piece at file=" + file + ",row=" + row));
                }
                if (piece == ' ') {
                    continue;
                }
                double confidence = candidate.confidenceAt(file, row);
                if (!validConfidence(confidence)) {
                    return ValidationSummary.rejected(reject(
                            RecognitionResult.RejectionReason.CONFIDENCE_INVALID,
                            "piece confidence must be finite at file=" + file + ",row=" + row));
                }
                if (confidence < policy.minimumPieceConfidence) {
                    return ValidationSummary.rejected(reject(
                            RecognitionResult.RejectionReason.LOW_PIECE_CONFIDENCE,
                            "piece confidence " + confidence + " is below "
                                    + policy.minimumPieceConfidence + " at file=" + file + ",row=" + row));
                }
                minimumConfidence = Math.min(minimumConfidence, confidence);
                counts.merge(piece, 1, Integer::sum);
                if (Character.isUpperCase(piece)) {
                    redPieces++;
                } else {
                    blackPieces++;
                }
                if (piece == 'K') {
                    redGeneralFile = file;
                    redGeneralRow = row;
                } else if (piece == 'k') {
                    blackGeneralFile = file;
                    blackGeneralRow = row;
                }
            }
        }

        if (counts.getOrDefault('K', 0) != 1 || counts.getOrDefault('k', 0) != 1) {
            return ValidationSummary.rejected(reject(
                    RecognitionResult.RejectionReason.GENERAL_COUNT_INVALID,
                    "recognized position must contain exactly one general per side"));
        }
        if (redPieces > 16 || blackPieces > 16) {
            return ValidationSummary.rejected(reject(
                    RecognitionResult.RejectionReason.PIECE_COUNT_INVALID,
                    "recognized position exceeds 16 pieces for one side"));
        }
        for (Map.Entry<Character, Integer> maximum : MAX_PIECES.entrySet()) {
            if (counts.getOrDefault(maximum.getKey(), 0) > maximum.getValue()) {
                return ValidationSummary.rejected(reject(
                        RecognitionResult.RejectionReason.PIECE_COUNT_INVALID,
                        "too many pieces of type " + maximum.getKey()));
            }
        }
        boolean standardOrientation = insidePalace(redGeneralFile, redGeneralRow, true)
                && insidePalace(blackGeneralFile, blackGeneralRow, false);
        boolean rotatedOrientation = insidePalace(redGeneralFile, redGeneralRow, false)
                && insidePalace(blackGeneralFile, blackGeneralRow, true);
        if (!standardOrientation && !rotatedOrientation) {
            return ValidationSummary.rejected(reject(
                    RecognitionResult.RejectionReason.GENERAL_OUTSIDE_PALACE,
                    "generals are not in one coherent board orientation"));
        }
        if (redGeneralFile == blackGeneralFile
                && noPieceBetween(candidate, redGeneralFile, blackGeneralRow, redGeneralRow)) {
            return ValidationSummary.rejected(reject(
                    RecognitionResult.RejectionReason.FACING_GENERALS,
                    "the generals face each other on an open file"));
        }
        return ValidationSummary.accepted(minimumConfidence);
    }

    private RecognitionResult validateTransition(RecognitionResult.AcceptedPosition previous,
                                                 RecognitionCandidate current) {
        int changed = 0;
        int sourceFile = -1;
        int sourceRow = -1;
        int targetFile = -1;
        int targetRow = -1;
        char movedPiece = ' ';

        for (int row = 0; row < 10; row++) {
            for (int file = 0; file < 9; file++) {
                char before = previous.pieceAt(file, row);
                char after = current.pieceAt(file, row);
                if (before == after) {
                    continue;
                }
                changed++;
                if (before != ' ' && after == ' ') {
                    sourceFile = file;
                    sourceRow = row;
                    movedPiece = before;
                } else if (after != ' ') {
                    targetFile = file;
                    targetRow = row;
                }
            }
        }
        if (changed == 0) {
            return null;
        }
        if (changed != CHANGED_SQUARES_PER_MOVE
                || sourceFile < 0 || targetFile < 0
                || current.pieceAt(targetFile, targetRow) != movedPiece) {
            return reject(RecognitionResult.RejectionReason.POSITION_JUMP,
                    "position change does not describe exactly one stable move; changed=" + changed);
        }
        char captured = previous.pieceAt(targetFile, targetRow);
        if (captured != ' ' && Character.isUpperCase(captured) == Character.isUpperCase(movedPiece)) {
            return reject(RecognitionResult.RejectionReason.POSITION_JUMP,
                    "position change would capture a same-side piece");
        }
        return null;
    }

    private boolean insidePalace(int file, int row, boolean red) {
        return file >= 3 && file <= 5 && (red ? row >= 7 && row <= 9 : row >= 0 && row <= 2);
    }

    private boolean noPieceBetween(RecognitionCandidate candidate, int file, int firstRow, int secondRow) {
        int from = Math.min(firstRow, secondRow) + 1;
        int to = Math.max(firstRow, secondRow);
        for (int row = from; row < to; row++) {
            if (candidate.pieceAt(file, row) != ' ') {
                return false;
            }
        }
        return true;
    }

    private boolean validConfidence(double confidence) {
        return Double.isFinite(confidence) && confidence >= 0.0 && confidence <= 1.0;
    }

    private RecognitionResult reject(RecognitionResult.RejectionReason reason, String detail) {
        return RecognitionResult.rejected(reason, detail);
    }

    public record Policy(double minimumBoardConfidence, double minimumPieceConfidence,
                         int maximumImageDimension, long maximumImagePixels,
                         long maximumInputBytes) {
        public Policy {
            if (!Double.isFinite(minimumBoardConfidence)
                    || minimumBoardConfidence < 0 || minimumBoardConfidence > 1
                    || !Double.isFinite(minimumPieceConfidence)
                    || minimumPieceConfidence < 0 || minimumPieceConfidence > 1
                    || maximumImageDimension < 1 || maximumImagePixels < 1
                    || maximumInputBytes < 1) {
                throw new IllegalArgumentException("recognition policy limits must be positive and bounded");
            }
        }

        public static Policy safeDefaults() {
            return new Policy(0.90, 0.80, 8_192, 32_000_000,
                    32L * 1024 * 1024);
        }

        public boolean permitsInput(int width, int height, long inputBytes) {
            if (width <= 0 || height <= 0 || inputBytes < 0) {
                return false;
            }
            long pixels = (long) width * height;
            return width <= maximumImageDimension
                    && height <= maximumImageDimension
                    && pixels <= maximumImagePixels
                    && inputBytes <= maximumInputBytes;
        }
    }

    private record ValidationSummary(double minimumPieceConfidence,
                                     RecognitionResult rejection) {
        private static ValidationSummary accepted(double minimumPieceConfidence) {
            return new ValidationSummary(minimumPieceConfidence, null);
        }

        private static ValidationSummary rejected(RecognitionResult rejection) {
            return new ValidationSummary(0, rejection);
        }
    }

    /**
     * Promotes a changed position only after two consecutive accepted frames.
     * Reset is deliberately explicit so a large new-game jump cannot silently
     * replace the current automation baseline.
     */
    public static final class StabilityTracker {
        private final RecognitionGate gate;
        private RecognitionResult.AcceptedPosition stablePosition;
        private RecognitionResult.AcceptedPosition pendingPosition;

        private StabilityTracker(RecognitionGate gate) {
            this.gate = gate;
        }

        public synchronized RecognitionResult evaluate(RecognitionCandidate candidate) {
            RecognitionResult validated = gate.evaluate(candidate, stablePosition);
            if (!validated.accepted()) {
                pendingPosition = null;
                return validated;
            }

            RecognitionResult.AcceptedPosition current = validated.position().orElseThrow();
            if (stablePosition != null && sameBoard(stablePosition, current)) {
                pendingPosition = null;
                return validated;
            }
            if (pendingPosition != null && sameBoard(pendingPosition, current)) {
                stablePosition = current;
                pendingPosition = null;
                return validated;
            }

            pendingPosition = current;
            return RecognitionResult.rejected(
                    RecognitionResult.RejectionReason.UNSTABLE_FRAME,
                    "position must appear in two consecutive accepted frames"
            );
        }

        public synchronized void reset() {
            stablePosition = null;
            pendingPosition = null;
        }

        private boolean sameBoard(RecognitionResult.AcceptedPosition first,
                                  RecognitionResult.AcceptedPosition second) {
            for (int row = 0; row < 10; row++) {
                for (int file = 0; file < 9; file++) {
                    if (first.pieceAt(file, row) != second.pieceAt(file, row)) {
                        return false;
                    }
                }
            }
            return true;
        }
    }
}
