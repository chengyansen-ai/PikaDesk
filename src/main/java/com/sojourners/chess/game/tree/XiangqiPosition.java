package com.sojourners.chess.game.tree;

import com.sojourners.chess.util.XiangqiUtils;

import java.util.Locale;

final class XiangqiPosition {

    private static final String PIECES = "rnbakcpRNBAKCP";

    private XiangqiPosition() {
    }

    static String normalizeFen(String fen) {
        Parsed parsed = parse(fen);
        boolean redInCheck = XiangqiUtils.isJiang(parsed.board(), true);
        boolean blackInCheck = XiangqiUtils.isJiang(parsed.board(), false);
        if (redInCheck && blackInCheck) {
            throw new IllegalArgumentException("both sides cannot be in check");
        }
        if (parsed.redToMove() ? blackInCheck : redInCheck) {
            throw new IllegalArgumentException("the side that just moved cannot remain in check");
        }
        return toFen(parsed.board(), parsed.redToMove());
    }

    static String applyMove(String fen, String move) {
        Parsed parsed = parse(fen);
        if (move == null || !move.matches("[a-i][0-9][a-i][0-9]")) {
            throw new IllegalArgumentException("move must use four-character UCCI coordinates");
        }
        int fromFile = move.charAt(0) - 'a';
        int fromRow = 9 - (move.charAt(1) - '0');
        int toFile = move.charAt(2) - 'a';
        int toRow = 9 - (move.charAt(3) - '0');
        if (fromFile == toFile && fromRow == toRow) {
            throw new IllegalArgumentException("move source and target must differ");
        }

        char[][] board = parsed.board();
        char piece = board[fromRow][fromFile];
        if (piece == ' ' || XiangqiUtils.isRed(piece) != parsed.redToMove()) {
            throw new IllegalArgumentException("move source does not belong to the side to move");
        }
        if (!XiangqiUtils.canGo(board, fromRow, fromFile, toRow, toFile)) {
            throw new IllegalArgumentException("piece cannot make the requested move");
        }
        if (Character.toLowerCase(board[toRow][toFile]) == 'k') {
            throw new IllegalArgumentException("a legal move cannot capture a king");
        }

        char[][] next = copy(board);
        next[toRow][toFile] = next[fromRow][fromFile];
        next[fromRow][fromFile] = ' ';
        if (XiangqiUtils.isJiang(next, parsed.redToMove())) {
            throw new IllegalArgumentException("move leaves the moving side in check");
        }
        if (!XiangqiUtils.validateChessBoard(next)) {
            throw new IllegalArgumentException("move produces an invalid position");
        }
        return toFen(next, !parsed.redToMove());
    }

    private static Parsed parse(String fen) {
        if (fen == null || fen.isBlank() || fen.length() > 512
                || fen.indexOf('\r') >= 0 || fen.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("FEN is empty or exceeds its limit");
        }
        String[] fields = fen.trim().split("\\s+");
        if (fields.length < 2 || fields.length > 6) {
            throw new IllegalArgumentException("FEN must contain a position and side to move");
        }
        String side = fields[1].toLowerCase(Locale.ROOT);
        if (!side.equals("w") && !side.equals("b")) {
            throw new IllegalArgumentException("FEN side to move must be w or b");
        }
        String[] ranks = fields[0].split("/", -1);
        if (ranks.length != 10) {
            throw new IllegalArgumentException("FEN must contain exactly ten ranks");
        }
        char[][] board = new char[10][9];
        for (int row = 0; row < ranks.length; row++) {
            int file = 0;
            for (char symbol : ranks[row].toCharArray()) {
                if (symbol >= '1' && symbol <= '9') {
                    int empty = symbol - '0';
                    if (file + empty > 9) {
                        throw new IllegalArgumentException("FEN rank exceeds nine files");
                    }
                    while (empty-- > 0) board[row][file++] = ' ';
                } else {
                    if (PIECES.indexOf(symbol) < 0 || file >= 9) {
                        throw new IllegalArgumentException("FEN contains an unsupported piece");
                    }
                    board[row][file++] = symbol;
                }
            }
            if (file != 9) {
                throw new IllegalArgumentException("FEN rank must contain nine files");
            }
        }
        if (!XiangqiUtils.validateChessBoard(board)) {
            throw new IllegalArgumentException("FEN position is not a valid xiangqi board");
        }
        return new Parsed(board, side.equals("w"));
    }

    private static String toFen(char[][] board, boolean redToMove) {
        StringBuilder fen = new StringBuilder(96);
        for (int row = 0; row < 10; row++) {
            int empty = 0;
            for (int file = 0; file < 9; file++) {
                char piece = board[row][file];
                if (piece == ' ') {
                    empty++;
                } else {
                    if (empty > 0) fen.append(empty);
                    empty = 0;
                    fen.append(piece);
                }
            }
            if (empty > 0) fen.append(empty);
            if (row < 9) fen.append('/');
        }
        return fen.append(redToMove ? " w" : " b").toString();
    }

    private static char[][] copy(char[][] source) {
        char[][] result = new char[10][9];
        for (int row = 0; row < source.length; row++) {
            System.arraycopy(source[row], 0, result[row], 0, source[row].length);
        }
        return result;
    }

    private record Parsed(char[][] board, boolean redToMove) {
    }
}
