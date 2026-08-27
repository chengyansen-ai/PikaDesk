package com.sojourners.chess.openbook;

import com.sojourners.chess.model.BookData;
import com.sojourners.chess.util.ZobristUtils;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PfOpenBook implements OpenBook {

    private final SafeSqliteOpenBookSupport support;

    public PfOpenBook(String bookPath) throws ClassNotFoundException, SQLException {
        this.support = SafeSqliteOpenBookSupport.openLegacy(
                Path.of(bookPath), ".pfbook", "pfBook");
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
        return new SafeSqliteOpenBookSupport.QueryPlan(
                "SELECT vmove,vscore,vwin,vdraw,vlost,vmemo "
                        + "FROM pfBook WHERE vkey=? AND vvalid=1",
                query -> {
                    query.setLong(1, zobrist);
                    return 2;
                },
                rows -> {
                    BookData data = SafeSqliteOpenBookSupport.readBookData(rows,
                            "vscore", "vwin", "vdraw", "vlost", "vmemo",
                            support.source());
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
