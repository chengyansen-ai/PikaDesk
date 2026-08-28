package com.sojourners.chess.openbook;

import com.sojourners.chess.game.tree.GameTree;
import com.sojourners.chess.util.XiangqiUtils;
import com.sojourners.chess.util.ZobristUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PersonalOpeningBookBuilderTest {

    private static final Charset BIG5 = Charset.forName("Big5");
    private static final String INITIAL_FEN =
            "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1";

    @BeforeAll
    static void loadSqliteDriver() throws Exception {
        Class.forName("org.sqlite.JDBC");
    }

    @Test
    void fillsOnlyMissingPositionsAlongAlreadyApprovedBranches(@TempDir Path directory)
            throws Exception {
        Path primary = createPrimary(directory.resolve("primary.obk"));
        String primaryHash = sha256(primary);
        Path corpus = Files.createDirectory(directory.resolve("corpus"));
        writePgn(corpus.resolve("00000001.pgn"), "主線",
                "1. 炮二平五 馬８進７\r\n2. 馬二進三 車９平８  *");
        writePgn(corpus.resolve("00000002.pgn"), "衝突分支",
                "1. 兵七進一 馬８進７  *");
        Path destination = directory.resolve("personal.obk");

        PersonalOpeningBookBuilder.Report report = new PersonalOpeningBookBuilder()
                .build(primary, corpus, destination);

        assertEquals(2, report.primaryRows());
        assertEquals(2, report.corpusLines());
        assertEquals(2, report.followedRows());
        assertEquals(2, report.insertedGapRows());
        assertEquals(1, report.conflictingLinesStopped());
        assertEquals(1, report.completedLines());
        assertEquals(primaryHash, sha256(primary));

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + destination);
             Statement statement = connection.createStatement();
             ResultSet count = statement.executeQuery("SELECT count(*) FROM bhobk")) {
            assertEquals(true, count.next());
            assertEquals(4, count.getInt(1));
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + destination);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT vscore,vwin,vdraw,vlost,vvalid,vmemo FROM bhobk WHERE vmemo LIKE 'CCPD%'")) {
            int supplements = 0;
            while (rows.next()) {
                supplements++;
                assertEquals(3, rows.getInt(1));
                assertEquals(0, rows.getInt(2));
                assertEquals(0, rows.getInt(3));
                assertEquals(0, rows.getInt(4));
                assertEquals(1, rows.getInt(5));
            }
            assertEquals(2, supplements);
        }
        new BhOpenBook(destination.toString()).close();
    }

    private Path createPrimary(Path target) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + target);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE bhobk(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        vkey INTEGER, vmove INTEGER, vscore INTEGER,
                        vwin INTEGER, vdraw INTEGER, vlost INTEGER,
                        vvalid INTEGER, vmemo TEXT, vindex INTEGER)
                    """);
            statement.execute("CREATE INDEX idxkey ON bhobk(vkey)");
        }
        GameTree tree = GameTree.create(INITIAL_FEN);
        insert(target, tree.current().positionFen(), "h2e2");
        tree.insert(tree.current().id(), "h2e2");
        insert(target, tree.current().positionFen(), "h9g7");
        return target;
    }

    private void insert(Path target, String fen, String move) throws Exception {
        char[][] board = XiangqiUtils.fenToBoard(fen);
        boolean red = fen.endsWith(" w");
        long key = ZobristUtils.getZobristFromBoard(board, red, false);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + target);
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO bhobk(vkey,vmove,vscore,vwin,vdraw,vlost,vvalid,vmemo)
                     VALUES(?,?,5,1,0,0,1,'主库')
                     """)) {
            bindKey(insert, 1, key);
            insert.setInt(2, ZobristUtils.getVmoveFromMove(move, false));
            insert.executeUpdate();
        }
    }

    private void bindKey(PreparedStatement statement, int index, long key) throws Exception {
        if (key < 0) statement.setDouble(index, Double.longBitsToDouble(key));
        else statement.setLong(index, key);
    }

    private void writePgn(Path target, String event, String moves) throws Exception {
        String pgn = "[Game \"Chinese Chess\"]\r\n"
                + "[Event \"" + event + "\"]\r\n"
                + "[Result \"*\"]\r\n"
                + "[ECCO \"C44\"]\r\n"
                + "[FEN \"" + INITIAL_FEN + "\"]\r\n\r\n"
                + moves + "\r\n";
        Files.write(target, pgn.getBytes(BIG5));
    }

    private String sha256(Path path) throws Exception {
        return HexFormat.of().withUpperCase().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
