package com.sojourners.chess.openbook;

import com.sojourners.chess.book.CcpdOpeningCorpusAuditor;
import com.sojourners.chess.book.CcpdOpeningPgnReader;
import com.sojourners.chess.game.tree.GameTree;
import com.sojourners.chess.util.XiangqiUtils;
import com.sojourners.chess.util.ZobristUtils;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Builds a personal OBK by preserving the filtered primary book and using a
 * licensed corpus only to fill positions for which the primary book has no
 * move at all. A corpus line stops at the first disagreement with the primary
 * book, so unscored historical alternatives cannot displace stronger rows.
 */
public final class PersonalOpeningBookBuilder {

    private static final int PRIMARY_MINIMUM_SCORE = 3;
    private static final int SUPPLEMENT_SCORE = 3;

    public Report build(Path primary, Path corpusDirectory, Path destination) throws IOException {
        Objects.requireNonNull(primary, "primary");
        Objects.requireNonNull(corpusDirectory, "corpusDirectory");
        Objects.requireNonNull(destination, "destination");
        Path target = validateTarget(primary, destination);

        CcpdOpeningCorpusAuditor.AuditResult corpus =
                new CcpdOpeningCorpusAuditor().audit(corpusDirectory);
        Path staging = Files.createTempFile(
                target.getParent(), ".pikadesk-personal-book-", ".obk");
        try {
            ObkQualityFilterService.Report primaryReport = new ObkQualityFilterService().build(
                    primary, staging, PRIMARY_MINIMUM_SCORE, () -> false, ignored -> { });
            MutableReport report = supplement(staging, corpus.lines());
            verify(staging);
            replaceAtomically(staging, target);
            staging = null;
            return new Report(
                    primaryReport.writtenRows(),
                    corpus.report().uniqueLines(),
                    report.examinedRows,
                    report.followedRows,
                    report.insertedGapRows,
                    report.conflictingLinesStopped,
                    report.completedLines,
                    corpus.report());
        } catch (SQLException exception) {
            throw new BuildException("SQLITE_FAILURE", "could not build the personal OBK", exception);
        } finally {
            deleteDatabaseFiles(staging);
        }
    }

    private Path validateTarget(Path primary, Path destination) throws IOException {
        String name = destination.getFileName() == null ? "" : destination.getFileName().toString();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".obk")) {
            throw new BuildException("UNSUPPORTED_DESTINATION", "destination must use .obk");
        }
        Path target = destination.toAbsolutePath().normalize();
        if (target.getParent() == null || !Files.isDirectory(target.getParent())) {
            throw new BuildException("INVALID_DESTINATION", "destination parent must exist");
        }
        Path source = primary.toAbsolutePath().normalize();
        if (source.equals(target) || Files.exists(target) && Files.isSameFile(primary, target)) {
            throw new BuildException("SOURCE_DESTINATION_OVERLAP", "destination must differ from primary");
        }
        return target;
    }

    private MutableReport supplement(
            Path staging,
            List<CcpdOpeningPgnReader.OpeningLine> lines) throws SQLException {
        MutableReport report = new MutableReport();
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + staging.toAbsolutePath());
             PreparedStatement query = connection.prepareStatement(
                     "SELECT vmove FROM bhobk WHERE vkey=? AND vvalid=1 LIMIT 257");
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO bhobk(vkey,vmove,vscore,vwin,vdraw,vlost,vvalid,vmemo,vindex)
                     VALUES(?,?,?,0,0,0,1,?,NULL)
                     """)) {
            try (Statement settings = connection.createStatement()) {
                settings.execute("PRAGMA trusted_schema=OFF");
                settings.execute("PRAGMA journal_mode=DELETE");
                settings.execute("PRAGMA synchronous=FULL");
            }
            connection.setAutoCommit(false);
            try {
                for (CcpdOpeningPgnReader.OpeningLine line : lines) {
                    GameTree tree = GameTree.create(line.initialFen());
                    boolean completed = true;
                    for (String move : line.moves()) {
                        report.examinedRows++;
                        String fen = tree.current().positionFen();
                        char[][] board = XiangqiUtils.fenToBoard(fen);
                        boolean redToMove = fen.endsWith(" w");
                        long directKey = ZobristUtils.getZobristFromBoard(board, redToMove, false);
                        long mirroredKey = ZobristUtils.getZobristFromBoard(board, redToMove, true);
                        int directMove = ZobristUtils.getVmoveFromMove(move, false);
                        int mirroredMove = ZobristUtils.getVmoveFromMove(move, true);
                        Coverage coverage = coverage(
                                query, directKey, directMove, mirroredKey, mirroredMove);
                        if (coverage.exactMove()) {
                            report.followedRows++;
                        } else if (coverage.anyMove()) {
                            report.conflictingLinesStopped++;
                            completed = false;
                            break;
                        } else {
                            bindKey(insert, 1, directKey);
                            insert.setInt(2, directMove);
                            insert.setInt(3, SUPPLEMENT_SCORE);
                            insert.setString(4, supplementMemo(line));
                            if (insert.executeUpdate() != 1) {
                                throw new SQLException("could not insert a corpus gap row");
                            }
                            report.insertedGapRows++;
                        }
                        tree.insert(tree.current().id(), move);
                    }
                    if (completed) report.completedLines++;
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
        return report;
    }

    private Coverage coverage(PreparedStatement query,
                              long directKey,
                              int directMove,
                              long mirroredKey,
                              int mirroredMove) throws SQLException {
        Coverage direct = coverage(query, directKey, directMove);
        if (directKey == mirroredKey && directMove == mirroredMove) return direct;
        Coverage mirrored = coverage(query, mirroredKey, mirroredMove);
        return new Coverage(
                direct.anyMove() || mirrored.anyMove(),
                direct.exactMove() || mirrored.exactMove());
    }

    private Coverage coverage(PreparedStatement query, long key, int move) throws SQLException {
        bindKey(query, 1, key);
        boolean any = false;
        boolean exact = false;
        try (ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                any = true;
                if (rows.getInt(1) == move) exact = true;
            }
        }
        return new Coverage(any, exact);
    }

    private void bindKey(PreparedStatement statement, int index, long key) throws SQLException {
        if (key < 0) statement.setDouble(index, Double.longBitsToDouble(key));
        else statement.setLong(index, key);
    }

    private String supplementMemo(CcpdOpeningPgnReader.OpeningLine line) {
        String ecco = line.ecco().isBlank() ? "无ECCO" : line.ecco();
        return "CCPD CC BY 4.0 gap-fill; " + ecco + "; result=" + line.result();
    }

    private void verify(Path database) throws SQLException, BuildException {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + database.toAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA quick_check")) {
            if (!rows.next() || !"ok".equalsIgnoreCase(rows.getString(1))) {
                throw new BuildException("OUTPUT_INTEGRITY_FAILURE", "SQLite quick_check failed");
            }
        }
    }

    private void replaceAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteDatabaseFiles(Path database) {
        if (database == null) return;
        for (String suffix : List.of("", "-journal", "-wal", "-shm")) {
            try {
                Files.deleteIfExists(Path.of(database + suffix));
            } catch (IOException ignored) {
                // Preserve the primary failure.
            }
        }
    }

    private record Coverage(boolean anyMove, boolean exactMove) { }

    private static final class MutableReport {
        private long examinedRows;
        private long followedRows;
        private long insertedGapRows;
        private long conflictingLinesStopped;
        private long completedLines;
    }

    public record Report(long primaryRows,
                         long corpusLines,
                         long examinedRows,
                         long followedRows,
                         long insertedGapRows,
                         long conflictingLinesStopped,
                         long completedLines,
                         CcpdOpeningCorpusAuditor.AuditReport corpusAudit) {
        public Report {
            Objects.requireNonNull(corpusAudit, "corpusAudit");
        }
    }

    public static final class BuildException extends IOException {

        private final String code;

        private BuildException(String code, String message) {
            super(message);
            this.code = code;
        }

        private BuildException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
