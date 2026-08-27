package com.sojourners.chess.recognition;

import java.util.Optional;

/**
 * A recognition result is either an immutable accepted position or one explicit
 * rejection. It never retains the source image.
 */
public final class RecognitionResult {

    private final AcceptedPosition position;
    private final Rejection rejection;

    private RecognitionResult(AcceptedPosition position, Rejection rejection) {
        this.position = position;
        this.rejection = rejection;
    }

    static RecognitionResult accepted(RecognitionCandidate candidate,
                                      double minimumPieceConfidence) {
        return new RecognitionResult(new AcceptedPosition(
                candidate.boardCopy(),
                candidate.boardBounds(),
                candidate.boardConfidence(),
                minimumPieceConfidence,
                candidate.modelVersion()
        ), null);
    }

    static RecognitionResult rejected(RejectionReason reason, String detail) {
        return new RecognitionResult(null, new Rejection(reason, detail));
    }

    public boolean accepted() {
        return position != null;
    }

    public Optional<AcceptedPosition> position() {
        return Optional.ofNullable(position);
    }

    public Optional<Rejection> rejection() {
        return Optional.ofNullable(rejection);
    }

    public enum RejectionReason {
        INPUT_INVALID,
        INPUT_TOO_LARGE,
        BOUNDS_OUTSIDE_IMAGE,
        MODEL_VERSION_MISSING,
        BOARD_SHAPE_INVALID,
        CONFIDENCE_SHAPE_INVALID,
        CONFIDENCE_INVALID,
        LOW_BOARD_CONFIDENCE,
        LOW_PIECE_CONFIDENCE,
        UNSUPPORTED_PIECE,
        GENERAL_COUNT_INVALID,
        PIECE_COUNT_INVALID,
        GENERAL_OUTSIDE_PALACE,
        FACING_GENERALS,
        POSITION_JUMP,
        UNSTABLE_FRAME
    }

    public record Rejection(RejectionReason reason, String detail) { }

    public static final class AcceptedPosition {
        private final char[][] board;
        private final RecognitionCandidate.BoardBounds boardBounds;
        private final double boardConfidence;
        private final double minimumPieceConfidence;
        private final String modelVersion;

        private AcceptedPosition(char[][] board, RecognitionCandidate.BoardBounds boardBounds,
                                 double boardConfidence, double minimumPieceConfidence,
                                 String modelVersion) {
            this.board = copy(board);
            this.boardBounds = boardBounds;
            this.boardConfidence = boardConfidence;
            this.minimumPieceConfidence = minimumPieceConfidence;
            this.modelVersion = modelVersion;
        }

        public char pieceAt(int file, int row) {
            if (file < 0 || file > 8 || row < 0 || row > 9) {
                throw new IllegalArgumentException("square is outside the 9x10 board");
            }
            return board[row][file];
        }

        public char[][] boardCopy() {
            return copy(board);
        }

        public RecognitionCandidate.BoardBounds boardBounds() {
            return boardBounds;
        }

        public double boardConfidence() {
            return boardConfidence;
        }

        public double minimumPieceConfidence() {
            return minimumPieceConfidence;
        }

        public String modelVersion() {
            return modelVersion;
        }

        private static char[][] copy(char[][] source) {
            char[][] result = new char[source.length][];
            for (int row = 0; row < source.length; row++) {
                result[row] = source[row].clone();
            }
            return result;
        }
    }
}
