package com.sojourners.chess;

import com.sojourners.chess.board.ChessBoard;
import com.sojourners.chess.util.XiangqiUtils;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class XiangqiRulesCharacterizationTest {

    private static final String START_FEN =
            "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR";

    @Test
    void roundTripsTheStandardPositionAndSideToMove() {
        char[][] board = XiangqiUtils.fenToBoard(START_FEN + " w - - 0 1");

        assertAll(
                () -> assertTrue(XiangqiUtils.validateChessBoard(board)),
                () -> assertEquals(START_FEN + " w - - 0 1", ChessBoard.fenCode(board, true)),
                () -> assertEquals(START_FEN + " b - - 0 1", ChessBoard.fenCode(board, false)),
                () -> assertEquals(START_FEN, ChessBoard.fenCode(board, null))
        );
    }

    @Test
    void normalizesAReversedBoardFenToTheInternalOrientation() {
        char[][] canonical = XiangqiUtils.fenToBoard("4k4/9/9/9/9/9/9/9/9/3K5");
        char[][] reversed = XiangqiUtils.fenToBoard("5K3/9/9/9/9/9/9/9/9/4k4");

        for (int row = 0; row < canonical.length; row++) {
            assertArrayEquals(canonical[row], reversed[row], "row " + row);
        }
    }

    @Test
    void rejectsMissingOrMisplacedGeneralsAndExcessPieces() {
        char[][] missingRedGeneral = emptyBoard();
        missingRedGeneral[0][4] = 'k';

        char[][] redGeneralOutsidePalace = boardWithBothGenerals();
        redGeneralOutsidePalace[9][4] = ' ';
        redGeneralOutsidePalace[9][0] = 'K';

        char[][] threeBlackRooks = boardWithBothGenerals();
        threeBlackRooks[0][0] = 'r';
        threeBlackRooks[1][0] = 'r';
        threeBlackRooks[2][0] = 'r';

        assertAll(
                () -> assertFalse(XiangqiUtils.validateChessBoard(missingRedGeneral)),
                () -> assertFalse(XiangqiUtils.validateChessBoard(redGeneralOutsidePalace)),
                () -> assertFalse(XiangqiUtils.validateChessBoard(threeBlackRooks))
        );
    }

    @Test
    void recordsCurrentValidationGapsForFacingGeneralsAndUnknownPieces() {
        char[][] facingGenerals = boardWithBothGenerals();
        char[][] unknownPiece = boardWithBothGenerals();
        unknownPiece[5][0] = 'x';

        assertAll(
                () -> assertTrue(XiangqiUtils.validateChessBoard(facingGenerals),
                        "现有校验尚未拒绝将帅照面，安全识别层必须额外处理"),
                () -> assertTrue(XiangqiUtils.validateChessBoard(unknownPiece),
                        "现有校验尚未拒绝未知字符，解析边界必须额外处理")
        );
    }

    @Test
    void appliesHorseLegCannonScreenAndPawnRiverRules() {
        char[][] horseBoard = emptyBoard();
        horseBoard[9][1] = 'N';
        assertTrue(XiangqiUtils.canGo(horseBoard, 9, 1, 7, 2));
        horseBoard[8][1] = 'P';
        assertFalse(XiangqiUtils.canGo(horseBoard, 9, 1, 7, 2));

        char[][] cannonBoard = emptyBoard();
        cannonBoard[7][1] = 'C';
        cannonBoard[0][1] = 'r';
        assertFalse(XiangqiUtils.canGo(cannonBoard, 7, 1, 0, 1));
        cannonBoard[3][1] = 'p';
        assertTrue(XiangqiUtils.canGo(cannonBoard, 7, 1, 0, 1));
        cannonBoard[5][1] = 'p';
        assertFalse(XiangqiUtils.canGo(cannonBoard, 7, 1, 0, 1));

        char[][] pawnBoard = emptyBoard();
        pawnBoard[6][0] = 'P';
        assertTrue(XiangqiUtils.canGo(pawnBoard, 6, 0, 5, 0));
        assertFalse(XiangqiUtils.canGo(pawnBoard, 6, 0, 6, 1));
        pawnBoard[6][0] = ' ';
        pawnBoard[4][0] = 'P';
        assertTrue(XiangqiUtils.canGo(pawnBoard, 4, 0, 4, 1));
    }

    private char[][] boardWithBothGenerals() {
        char[][] board = emptyBoard();
        board[0][4] = 'k';
        board[9][4] = 'K';
        return board;
    }

    private char[][] emptyBoard() {
        char[][] board = new char[10][9];
        for (char[] row : board) {
            Arrays.fill(row, ' ');
        }
        return board;
    }
}
