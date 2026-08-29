package com.sojourners.chess.linker;

import java.util.Arrays;

/** Normalizes a recognized 9x10 board without mutating the recognition frame. */
final class ObservedBoardOrientation {

    private ObservedBoardOrientation() {
    }

    static Position normalize(char[][] visualBoard) {
        requireBoardShape(visualBoard);
        int redGeneralRow = -1;
        int blackGeneralRow = -1;
        for (int row = 0; row < 10; row++) {
            for (int file = 0; file < 9; file++) {
                if (visualBoard[row][file] == 'K') redGeneralRow = row;
                if (visualBoard[row][file] == 'k') blackGeneralRow = row;
            }
        }
        boolean standard = redGeneralRow >= 7 && blackGeneralRow <= 2
                && redGeneralRow >= 0 && blackGeneralRow >= 0;
        boolean reversed = redGeneralRow <= 2 && blackGeneralRow >= 7
                && redGeneralRow >= 0 && blackGeneralRow >= 0;
        if (!standard && !reversed) {
            throw new IllegalArgumentException(
                    "recognized generals do not identify one coherent orientation");
        }

        char[][] canonical = copy(visualBoard);
        if (reversed) rotateInPlace(canonical);
        return new Position(canonical, reversed);
    }

    private static void requireBoardShape(char[][] board) {
        if (board == null || board.length != 10) {
            throw new IllegalArgumentException("recognized board must have 10 rows");
        }
        for (char[] row : board) {
            if (row == null || row.length != 9) {
                throw new IllegalArgumentException("recognized board must have 9 files");
            }
        }
    }

    private static char[][] copy(char[][] board) {
        char[][] result = new char[board.length][];
        for (int row = 0; row < board.length; row++) {
            result[row] = Arrays.copyOf(board[row], board[row].length);
        }
        return result;
    }

    private static void rotateInPlace(char[][] board) {
        for (int row = 0; row < 5; row++) {
            for (int file = 0; file < 9; file++) {
                char value = board[row][file];
                board[row][file] = board[9 - row][8 - file];
                board[9 - row][8 - file] = value;
            }
        }
    }

    record Position(char[][] boardCopy, boolean reversed) {
        Position {
            boardCopy = copy(boardCopy);
        }

        @Override
        public char[][] boardCopy() {
            return copy(boardCopy);
        }
    }
}
