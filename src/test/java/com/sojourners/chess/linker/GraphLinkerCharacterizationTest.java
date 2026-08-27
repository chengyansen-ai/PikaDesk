package com.sojourners.chess.linker;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class GraphLinkerCharacterizationTest {

    @Test
    void returnsNoActionWhenRecognizedAndEngineBoardsMatch() {
        char[][] board = emptyBoard();
        board[9][0] = 'R';

        assertNull(AbstractGraphLinker.compareBoard(board, copy(board), true, false));
    }

    @Test
    void classifiesAnOpponentMoveThatMustBeSentToTheEngine() {
        char[][] engineBeforeMove = emptyBoard();
        engineBeforeMove[9][0] = 'R';
        char[][] linkAfterMove = copy(engineBeforeMove);
        linkAfterMove[9][0] = ' ';
        linkAfterMove[8][0] = 'R';

        AbstractGraphLinker.Action action =
                AbstractGraphLinker.compareBoard(linkAfterMove, engineBeforeMove, true, false);

        assertAction(action, 1, 0, 9, 0, 8);
    }

    @Test
    void classifiesAnEngineMoveThatMustBeClickedOnTheAuthorizedBoard() {
        char[][] linkBeforeMove = emptyBoard();
        linkBeforeMove[9][0] = 'R';
        char[][] engineAfterMove = copy(linkBeforeMove);
        engineAfterMove[9][0] = ' ';
        engineAfterMove[8][0] = 'R';

        AbstractGraphLinker.Action action =
                AbstractGraphLinker.compareBoard(linkBeforeMove, engineAfterMove, false, false);

        assertAction(action, 2, 0, 9, 0, 8);
    }

    @Test
    void preservesCaptureCoordinatesForAnOpponentMove() {
        char[][] engineBeforeCapture = emptyBoard();
        engineBeforeCapture[9][0] = 'R';
        engineBeforeCapture[5][0] = 'p';
        char[][] linkAfterCapture = copy(engineBeforeCapture);
        linkAfterCapture[9][0] = ' ';
        linkAfterCapture[5][0] = 'R';

        AbstractGraphLinker.Action action =
                AbstractGraphLinker.compareBoard(linkAfterCapture, engineBeforeCapture, true, false);

        assertAction(action, 1, 0, 9, 0, 5);
    }

    @Test
    void distinguishesANewGameFromAnUncertainMultiSquareChange() {
        char[][] engineBoard = emptyBoard();
        char[][] newGameBoard = emptyBoard();
        engineBoard[0][0] = 'r';
        engineBoard[0][1] = 'n';
        engineBoard[0][2] = 'b';
        newGameBoard[0][0] = 'p';
        newGameBoard[0][1] = 'c';
        newGameBoard[0][2] = 'k';

        AbstractGraphLinker.Action newGame =
                AbstractGraphLinker.compareBoard(newGameBoard, engineBoard, true, false);

        char[][] missingPieces = emptyBoard();
        AbstractGraphLinker.Action uncertain =
                AbstractGraphLinker.compareBoard(missingPieces, engineBoard, true, false);

        assertAll(
                () -> assertEquals(3, newGame.flag),
                () -> assertEquals(4, uncertain.flag)
        );
    }

    private void assertAction(AbstractGraphLinker.Action action,
                              int flag, int x1, int y1, int x2, int y2) {
        assertAll(
                () -> assertEquals(flag, action.flag),
                () -> assertEquals(x1, action.x1),
                () -> assertEquals(y1, action.y1),
                () -> assertEquals(x2, action.x2),
                () -> assertEquals(y2, action.y2)
        );
    }

    private char[][] emptyBoard() {
        char[][] board = new char[10][9];
        for (char[] row : board) {
            Arrays.fill(row, ' ');
        }
        return board;
    }

    private char[][] copy(char[][] board) {
        char[][] copy = new char[board.length][];
        for (int row = 0; row < board.length; row++) {
            copy[row] = Arrays.copyOf(board[row], board[row].length);
        }
        return copy;
    }
}
