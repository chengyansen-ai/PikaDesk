package com.sojourners.chess.openbook;

import com.sojourners.chess.model.BookData;
import com.sojourners.chess.util.ZobristUtils;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class BhOpenBook implements OpenBook {

    private final SafeSqliteOpenBookSupport support;

    public BhOpenBook(String bookPath) throws ClassNotFoundException, SQLException {
        this.support = SafeSqliteOpenBookSupport.openLegacy(
                Path.of(bookPath), ".obk", "bhobk");
    }

    @Override
    public List<BookData> get(char[][] board, boolean redGo) {

        long zobrist = ZobristUtils.getZobristFromBoard(board, redGo, false);
        long mirrored = ZobristUtils.getZobristFromBoard(board, redGo, true);
        if (zobrist == mirrored) {
            return support.query(List.of(plan(zobrist, false)));
        }
        return support.query(List.of(plan(zobrist, false), plan(mirrored, true)));
    }

    private SafeSqliteOpenBookSupport.QueryPlan plan(long zobrist, boolean leftRightSwap) {
        String sql;
        SafeSqliteOpenBookSupport.Binder binder;
        if (zobrist < 0) {
            sql = "SELECT vmove,vscore,vwin,vdraw,vlost,vmemo FROM bhobk "
                    + "WHERE cast(vkey as double)=? AND vvalid=1";
            binder = query -> {
                query.setDouble(1, Double.longBitsToDouble(zobrist));
                return 2;
            };
        } else {
            sql = "SELECT vmove,vscore,vwin,vdraw,vlost,vmemo FROM bhobk "
                    + "WHERE cast(vkey as integer)=? AND vvalid=1";
            binder = query -> {
                query.setLong(1, zobrist);
                return 2;
            };
        }
        return new SafeSqliteOpenBookSupport.QueryPlan(sql, binder, rows -> {
            BookData data = SafeSqliteOpenBookSupport.readBookData(rows,
                    "vscore", "vwin", "vdraw", "vlost", "vmemo", support.source());
            int move = SafeSqliteOpenBookSupport.integer(rows, "vmove", false);
            SafeSqliteOpenBookSupport.requireC90Move(move);
            data.setMove(ZobristUtils.getMoveFromVmove(move, leftRightSwap));
            return data;
        });
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
}
