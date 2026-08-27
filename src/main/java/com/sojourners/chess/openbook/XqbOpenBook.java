package com.sojourners.chess.openbook;

import com.sojourners.chess.board.ChessBoard;
import com.sojourners.chess.model.BookData;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class XqbOpenBook implements OpenBook {

    private final SafeSqliteOpenBookSupport support;

    public XqbOpenBook(String bookPath) throws ClassNotFoundException, SQLException {
        this.support = SafeSqliteOpenBookSupport.openXqb(Path.of(bookPath));
    }

    @Override
    public List<BookData> get(char[][] board, boolean redGo) {
        XQKEY xqKey = new XQKEY();
        String fenCode = ChessBoard.fenCode(board, redGo);
        FenToKey(fenCode, xqKey);
        return BookQuery(xqKey);
    }

    @Override
    public List<BookData> get(String fenCode, boolean onlyFinalPhase) {
        return new ArrayList<>();
    }

    @Override
    public void close() {
        support.closeQuietly();
    }

    @Override
    public Optional<OpenBookDiagnostic> diagnostic() {
        return support.diagnostic();
    }


    static class XQKEY {
        byte[] Key = new byte[128];
        int KeyLen;
        boolean MirrorUD;
        boolean MirrorLR;
        int Rows;
        int Cols;
    }

    private int GetRowsAndCols(String fen, int[] rowsCols) {
        int rows = 1;
        int cols = 0;
        boolean calcCols = true;
        for (int i = 0; fen.charAt(i) != ' '; i++) {
            char ch = fen.charAt(i);
            if (ch == '/') {
                rows++;
                calcCols = false;
            } else if (calcCols) {
                if (ch >= '0' && ch <= '9') {
                    cols += ch - '0';
                } else {
                    cols += 1;
                }
            }
        }
        rowsCols[0] = rows;
        rowsCols[1] = cols;
        return rows * cols;
    }

    private void FenToKey(String fen, XQKEY xqKey) {
        int turn = fen.charAt(fen.indexOf(' ') + 1) != 'b' ? 1 : 0;
        int[] rc = new int[2];
        int size = GetRowsAndCols(fen, rc);
        xqKey.Rows = rc[0];
        xqKey.Cols = rc[1];
        byte[] ary = new byte[size];
        for (int i = 0; i < size; i++) ary[i] = (byte) -1;
        for (int i = 0, index = 0; fen.charAt(i) != ' ' && index < size; i++) {
            char ch = fen.charAt(i);
            if (ch >= '0' && ch <= '9') {
                index += ch - '0';
            } else if (ch != '/') {
                char mapped = turn == 0 ? (char) (ch ^ 0x20) : ch;
                byte val = -1;
                switch (mapped) {
                    case 'X':
                    case 'x': val = 0; break;
                    case 'R': val = 1; break;
                    case 'N': val = 2; break;
                    case 'B': val = 3; break;
                    case 'A': val = 4; break;
                    case 'K': val = 5; break;
                    case 'C': val = 6; break;
                    case 'P': val = 7; break;
                    case 'r': val = 9; break;
                    case 'n': val = 10; break;
                    case 'b': val = 11; break;
                    case 'a': val = 12; break;
                    case 'k': val = 13; break;
                    case 'c': val = 14; break;
                    case 'p': val = 15; break;
                }
                ary[index++] = val;
            }
        }
        xqKey.MirrorUD = false;
        if (turn == 0) {
            for (int row = 0; row < xqKey.Rows / 2; row++) {
                for (int col = 0; col < xqKey.Cols; col++) {
                    int index = row * xqKey.Cols + col;
                    int index2 = (xqKey.Rows - 1 - row) * xqKey.Cols + (xqKey.Cols - 1 - col);
                    byte tmp = ary[index];
                    ary[index] = ary[index2];
                    ary[index2] = tmp;
                }
            }
            xqKey.MirrorUD = true;
        }
        xqKey.MirrorLR = false;
        boolean lrDone = false;
        for (int row = 0; row < xqKey.Rows && !lrDone; row++) {
            for (int col = 0; col < xqKey.Cols / 2 && !lrDone; col++) {
                int index = row * xqKey.Cols + col;
                int index2 = row * xqKey.Cols + (xqKey.Cols - 1 - col);
                if (ary[index] != ary[index2]) {
                    xqKey.MirrorLR = ary[index2] > ary[index];
                    lrDone = true;
                }
            }
        }
        if (xqKey.MirrorLR) {
            for (int row = 0; row < xqKey.Rows; row++) {
                for (int col = 0; col < xqKey.Cols / 2; col++) {
                    int index = row * xqKey.Cols + col;
                    int index2 = row * xqKey.Cols + (xqKey.Cols - col - 1);
                    byte tmp = ary[index];
                    ary[index] = ary[index2];
                    ary[index2] = tmp;
                }
            }
        }
        xqKey.KeyLen = 0;
        int buffer = 0;
        int bufferBits = 32;
        int codeBits = 4;
        int bits = 0;
        for (int index = 0; index < size; index++) {
            if (ary[index] == -1) {
                bits++;
            } else {
                buffer |= 1 << (bufferBits - bits - 1);
                buffer |= (ary[index] & 0xFF) << (bufferBits - bits - 1 - codeBits);
                bits += 1 + codeBits;
            }
            int nextBits = (index == size - 1) ? 0 : ((ary[index + 1] == -1) ? 1 : codeBits + 1);
            if (index == size - 1 || bufferBits - bits < nextBits) {
                int threshold = index == size - 1 ? 1 : 8;
                while (bits >= threshold) {
                    xqKey.Key[xqKey.KeyLen++] = (byte) ((buffer >>> (bufferBits - 8)) & 0xFF);
                    buffer <<= 8;
                    bits -= 8;
                }
            }
        }
    }

    private int MirrorMove(int move, boolean mirrorUD, boolean mirrorLR, int rows, int cols) {
        if (mirrorUD || mirrorLR) {
            int fromRow = move >> 12;
            int fromCol = (move >> 8) & 0xF;
            int toRow = (move >> 4) & 0xF;
            int toCol = move & 0xF;
            if (mirrorUD) {
                fromRow = rows - 1 - fromRow;
                toRow = rows - 1 - toRow;
                fromCol = cols - 1 - fromCol;
                toCol = cols - 1 - toCol;
            }
            if (mirrorLR) {
                fromCol = cols - 1 - fromCol;
                toCol = cols - 1 - toCol;
            }
            move = (fromRow << 12) | (fromCol << 8) | (toRow << 4) | toCol;
        }
        return move;
    }

    private List<BookData> BookQuery(XQKEY xqKey) {
        byte[] key = new byte[xqKey.KeyLen];
        System.arraycopy(xqKey.Key, 0, key, 0, xqKey.KeyLen);
        SafeSqliteOpenBookSupport.QueryPlan plan = new SafeSqliteOpenBookSupport.QueryPlan(
                "SELECT Move,Score,Win,Draw,Lost,Valid,Memo FROM book WHERE key=?",
                query -> {
                    query.setBytes(1, key);
                    return 2;
                },
                rows -> {
                    BookData data = SafeSqliteOpenBookSupport.readBookData(rows,
                            "Score", "Win", "Draw", "Lost", "Memo", support.source());
                    int valid = SafeSqliteOpenBookSupport.integer(rows, "Valid", false);
                    SafeSqliteOpenBookSupport.requireFlag(valid);
                    int encodedMove = SafeSqliteOpenBookSupport.integer(rows, "Move", false);
                    SafeSqliteOpenBookSupport.requireXqbMove(encodedMove);
                    int move = MirrorMove(encodedMove, xqKey.MirrorUD, xqKey.MirrorLR,
                            xqKey.Rows, xqKey.Cols);
                    SafeSqliteOpenBookSupport.requireXqbMove(move);
                    int from = move >>> 8;
                    int to = move & 0xff;
                    data.setMove(ChessBoard.stepForEngine(from & 0xf, from >>> 4,
                            to & 0xf, to >>> 4));
                    return data;
                });
        return support.query(List.of(plan));
    }
}
