package com.sojourners.chess.manual;

import com.sojourners.chess.board.ChessBoard;
import com.sojourners.chess.model.ManualRecord;
import com.sojourners.chess.util.StringUtils;
import com.sojourners.chess.util.XiangqiUtils;

import java.io.File;

public interface ChessManualService {
    ChessManual openChessManual(File file);
    void saveChessManual(ChessManual chessManual, File file);

    default void translate(String fenCode, ManualRecord mr) {
        char[][] board = XiangqiUtils.fenToBoard(fenCode);
        translateRecursive(board, mr);
    }

    static void translateMoves(String fenCode, ManualRecord mr) {
        char[][] board = XiangqiUtils.fenToBoard(fenCode);
        translateRecursiveMoves(board, mr);
    }

    default void translateRecursive(char[][] board, ManualRecord mr) {
        translateRecursiveMoves(board, mr);
    }

    private static void translateRecursiveMoves(char[][] board, ManualRecord mr) {
        if (mr == null) {
            return;
        }

        String move = mr.getMove();
        // 保存棋盘状态
        Character movedPiece = null;
        int fromI = -1, fromJ = -1, toI = -1, toJ = -1;

        if (StringUtils.isNotEmpty(move) && mr.getId() > 0) {
            StringBuilder sb = new StringBuilder();
            ChessBoard.Step step = XiangqiUtils.translate(board, sb, move, false);
            mr.setCnMove(sb.toString());

            fromI = step.getStart().getY();
            fromJ = step.getStart().getX();
            toI = step.getEnd().getY();
            toJ = step.getEnd().getX();
            movedPiece = board[toI][toJ];
            board[toI][toJ] = board[fromI][fromJ];
            board[fromI][fromJ] = ' ';
        }

        // 递归翻译所有分支招法
        for (ManualRecord child : mr.getList()) {
            translateRecursiveMoves(board, child);
        }

        // 恢复棋盘状态
        if (movedPiece != null) {
            board[fromI][fromJ] = board[toI][toJ];
            board[toI][toJ] = movedPiece;
        }
    }
}
