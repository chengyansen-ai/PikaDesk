package com.sojourners.chess.recognition;

/**
 * Raw model output plus the input metadata needed for safety validation.
 * Arrays are copied at the boundary so an inference worker cannot mutate a
 * candidate after it has been submitted for validation.
 */
public final class RecognitionCandidate {

    private final int imageWidth;
    private final int imageHeight;
    private final long inputBytes;
    private final BoardBounds boardBounds;
    private final char[][] board;
    private final double[][] squareConfidences;
    private final double boardConfidence;
    private final String modelVersion;

    public RecognitionCandidate(int imageWidth, int imageHeight, long inputBytes,
                                BoardBounds boardBounds, char[][] board,
                                double[][] squareConfidences, double boardConfidence,
                                String modelVersion) {
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.inputBytes = inputBytes;
        this.boardBounds = boardBounds;
        this.board = copy(board);
        this.squareConfidences = copy(squareConfidences);
        this.boardConfidence = boardConfidence;
        this.modelVersion = modelVersion;
    }

    public int imageWidth() {
        return imageWidth;
    }

    public int imageHeight() {
        return imageHeight;
    }

    public long inputBytes() {
        return inputBytes;
    }

    public BoardBounds boardBounds() {
        return boardBounds;
    }

    public double boardConfidence() {
        return boardConfidence;
    }

    public String modelVersion() {
        return modelVersion;
    }

    public char[][] boardCopy() {
        return copy(board);
    }

    public double[][] squareConfidencesCopy() {
        return copy(squareConfidences);
    }

    int boardRows() {
        return board == null ? -1 : board.length;
    }

    int boardColumns(int row) {
        return board == null || row < 0 || row >= board.length || board[row] == null
                ? -1 : board[row].length;
    }

    char pieceAt(int file, int row) {
        return board[row][file];
    }

    int confidenceRows() {
        return squareConfidences == null ? -1 : squareConfidences.length;
    }

    int confidenceColumns(int row) {
        return squareConfidences == null || row < 0 || row >= squareConfidences.length
                || squareConfidences[row] == null ? -1 : squareConfidences[row].length;
    }

    double confidenceAt(int file, int row) {
        return squareConfidences[row][file];
    }

    private static char[][] copy(char[][] source) {
        if (source == null) {
            return null;
        }
        char[][] result = new char[source.length][];
        for (int row = 0; row < source.length; row++) {
            result[row] = source[row] == null ? null : source[row].clone();
        }
        return result;
    }

    private static double[][] copy(double[][] source) {
        if (source == null) {
            return null;
        }
        double[][] result = new double[source.length][];
        for (int row = 0; row < source.length; row++) {
            result[row] = source[row] == null ? null : source[row].clone();
        }
        return result;
    }

    public record BoardBounds(int x, int y, int width, int height) { }
}
