package com.sojourners.chess.testboard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Deterministic, network-free state for the local automation test board.
 */
public final class LocalTestBoardState {

    public static final String STANDARD_FEN =
            "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w";
    private static final String PIECES = "rnbakcpRNBAKCP";

    private char[][] board;
    private boolean redToMove;
    private Orientation orientation = Orientation.RED_AT_BOTTOM;
    private Theme theme = Theme.CLASSIC;
    private double scale = 1.0;
    private int animationMillis;
    private Square selectedSquare;
    private final List<Move> receivedMoves = new ArrayList<>();

    public LocalTestBoardState() {
        setFen(STANDARD_FEN);
    }

    public void setFen(String fen) {
        ParsedPosition parsed = parseFen(fen);
        board = parsed.board;
        redToMove = parsed.redToMove;
        selectedSquare = null;
        receivedMoves.clear();
    }

    public String fen() {
        StringBuilder result = new StringBuilder();
        for (int row = 0; row < 10; row++) {
            int empty = 0;
            for (int file = 0; file < 9; file++) {
                char piece = board[row][file];
                if (piece == ' ') {
                    empty++;
                } else {
                    if (empty > 0) {
                        result.append(empty);
                        empty = 0;
                    }
                    result.append(piece);
                }
            }
            if (empty > 0) {
                result.append(empty);
            }
            if (row < 9) {
                result.append('/');
            }
        }
        return result.append(redToMove ? " w" : " b").toString();
    }

    public char pieceAt(int file, int row) {
        validateSquare(file, row);
        return board[row][file];
    }

    public char pieceAtDisplay(int displayFile, int displayRow) {
        Square square = toCanonical(displayFile, displayRow);
        return pieceAt(square.file, square.row);
    }

    public ClickResult clickDisplaySquare(int displayFile, int displayRow) {
        Square target = toCanonical(displayFile, displayRow);
        char targetPiece = board[target.row][target.file];
        if (selectedSquare == null) {
            if (targetPiece == ' ') {
                return ClickResult.ignored("empty source square");
            }
            selectedSquare = target;
            return ClickResult.selected(target);
        }

        char selectedPiece = board[selectedSquare.row][selectedSquare.file];
        if (targetPiece != ' ' && isRed(targetPiece) == isRed(selectedPiece)) {
            selectedSquare = target;
            return ClickResult.selected(target);
        }

        Square source = selectedSquare;
        board[source.row][source.file] = ' ';
        board[target.row][target.file] = selectedPiece;
        selectedSquare = null;
        redToMove = !redToMove;

        Move move = new Move(
                toUcci(source, target),
                source,
                target,
                selectedPiece,
                targetPiece
        );
        receivedMoves.add(move);
        return ClickResult.moved(move);
    }

    public Optional<Square> selectedSquare() {
        return Optional.ofNullable(selectedSquare);
    }

    public List<Move> receivedMoves() {
        return List.copyOf(receivedMoves);
    }

    public void clearReceivedMoves() {
        receivedMoves.clear();
    }

    public Orientation orientation() {
        return orientation;
    }

    public void setOrientation(Orientation orientation) {
        if (orientation == null) {
            throw new IllegalArgumentException("orientation is required");
        }
        this.orientation = orientation;
        selectedSquare = null;
    }

    public Theme theme() {
        return theme;
    }

    public void setTheme(Theme theme) {
        if (theme == null) {
            throw new IllegalArgumentException("theme is required");
        }
        this.theme = theme;
    }

    public double scale() {
        return scale;
    }

    public void setScale(double scale) {
        if (!Double.isFinite(scale) || scale < 0.75 || scale > 2.0) {
            throw new IllegalArgumentException("scale must be between 0.75 and 2.0");
        }
        this.scale = scale;
    }

    public int animationMillis() {
        return animationMillis;
    }

    public void setAnimationMillis(int animationMillis) {
        if (animationMillis < 0 || animationMillis > 2_000) {
            throw new IllegalArgumentException("animationMillis must be between 0 and 2000");
        }
        this.animationMillis = animationMillis;
    }

    private Square toCanonical(int displayFile, int displayRow) {
        validateSquare(displayFile, displayRow);
        if (orientation == Orientation.RED_AT_BOTTOM) {
            return new Square(displayFile, displayRow);
        }
        return new Square(8 - displayFile, 9 - displayRow);
    }

    private String toUcci(Square source, Square target) {
        return new String(new char[]{
                (char) ('a' + source.file),
                (char) ('0' + 9 - source.row),
                (char) ('a' + target.file),
                (char) ('0' + 9 - target.row)
        });
    }

    private boolean isRed(char piece) {
        return Character.isUpperCase(piece);
    }

    private void validateSquare(int file, int row) {
        if (file < 0 || file > 8 || row < 0 || row > 9) {
            throw new IllegalArgumentException("square is outside the 9x10 board");
        }
    }

    private ParsedPosition parseFen(String fen) {
        if (fen == null || fen.isBlank() || fen.length() > 256) {
            throw new IllegalArgumentException("FEN must contain a bounded position");
        }
        String[] fields = fen.trim().split("\\s+");
        if (fields.length > 2) {
            throw new IllegalArgumentException("test board FEN supports position and side only");
        }
        String[] ranks = fields[0].split("/", -1);
        if (ranks.length != 10) {
            throw new IllegalArgumentException("FEN must contain exactly 10 ranks");
        }

        char[][] parsedBoard = new char[10][9];
        for (char[] row : parsedBoard) {
            Arrays.fill(row, ' ');
        }
        for (int row = 0; row < ranks.length; row++) {
            int file = 0;
            for (int offset = 0; offset < ranks[row].length(); offset++) {
                char symbol = ranks[row].charAt(offset);
                if (symbol >= '1' && symbol <= '9') {
                    file += symbol - '0';
                } else if (PIECES.indexOf(symbol) >= 0) {
                    if (file >= 9) {
                        throw new IllegalArgumentException("FEN rank is wider than 9 files");
                    }
                    parsedBoard[row][file++] = symbol;
                } else {
                    throw new IllegalArgumentException("FEN contains an unsupported piece: " + symbol);
                }
                if (file > 9) {
                    throw new IllegalArgumentException("FEN rank is wider than 9 files");
                }
            }
            if (file != 9) {
                throw new IllegalArgumentException("FEN rank must contain exactly 9 files");
            }
        }

        boolean parsedRedToMove = true;
        if (fields.length == 2) {
            if (!fields[1].equals("w") && !fields[1].equals("b")) {
                throw new IllegalArgumentException("side to move must be w or b");
            }
            parsedRedToMove = fields[1].equals("w");
        }
        return new ParsedPosition(parsedBoard, parsedRedToMove);
    }

    public enum Orientation {
        RED_AT_BOTTOM,
        BLACK_AT_BOTTOM
    }

    public enum Theme {
        CLASSIC,
        HIGH_CONTRAST
    }

    public enum ClickKind {
        IGNORED,
        SELECTED,
        MOVED
    }

    public record Square(int file, int row) { }

    public record Move(String ucci, Square source, Square target,
                       char movedPiece, char capturedPiece) { }

    public record ClickResult(ClickKind kind, Optional<Square> selected,
                              Optional<Move> move, String message) {
        private static ClickResult ignored(String message) {
            return new ClickResult(ClickKind.IGNORED, Optional.empty(), Optional.empty(), message);
        }

        private static ClickResult selected(Square square) {
            return new ClickResult(ClickKind.SELECTED, Optional.of(square), Optional.empty(), "selected");
        }

        private static ClickResult moved(Move move) {
            return new ClickResult(ClickKind.MOVED, Optional.empty(), Optional.of(move), move.ucci());
        }
    }

    private record ParsedPosition(char[][] board, boolean redToMove) { }
}
