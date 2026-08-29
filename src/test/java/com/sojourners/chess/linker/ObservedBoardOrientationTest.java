package com.sojourners.chess.linker;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ObservedBoardOrientationTest {

    @Test
    void keepsARedAtBottomPositionInCanonicalOrientation() {
        char[][] visual = emptyBoard();
        visual[0][4] = 'k';
        visual[9][4] = 'K';
        visual[9][0] = 'R';

        ObservedBoardOrientation.Position position =
                ObservedBoardOrientation.normalize(visual);

        assertFalse(position.reversed());
        assertEquals('R', position.boardCopy()[9][0]);
    }

    @Test
    void rotatesABlackAtBottomPositionIntoCanonicalOrientation() {
        char[][] visual = emptyBoard();
        visual[0][4] = 'K';
        visual[9][4] = 'k';
        visual[0][8] = 'R';

        ObservedBoardOrientation.Position position =
                ObservedBoardOrientation.normalize(visual);

        assertTrue(position.reversed());
        assertEquals('K', position.boardCopy()[9][4]);
        assertEquals('k', position.boardCopy()[0][4]);
        assertEquals('R', position.boardCopy()[9][0]);
        assertEquals('R', visual[0][8], "normalization must not mutate the recognition frame");
    }

    @Test
    void rejectsAFrameWithoutEitherGeneralInsteadOfGuessingDirection() {
        assertThrows(IllegalArgumentException.class,
                () -> ObservedBoardOrientation.normalize(emptyBoard()));
    }

    private char[][] emptyBoard() {
        char[][] board = new char[10][9];
        for (char[] row : board) Arrays.fill(row, ' ');
        return board;
    }
}
