package com.sojourners.chess.openbook;

import com.sojourners.chess.board.ChessBoard;
import com.sojourners.chess.model.BookData;
import com.sojourners.chess.util.ZobristUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SqliteOpenBookAdapterTest {

    @TempDir
    Path temporaryDirectory;

    @BeforeAll
    static void loadSqliteDriver() throws Exception {
        Class.forName("org.sqlite.JDBC");
    }

    @Test
    void xqbReadsAValidRowWithoutModifyingTheSource() throws Exception {
        Path book = createXqb("read-only.xqb");
        try (Connection connection = writable(book);
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO book(key,move,score,win,draw,lost,valid,memo)
                     VALUES(?,?,?,?,?,?,?,?)
                     """)) {
            insert.setBytes(1, new byte[12]);
            insert.setInt(2, 0x9080);
            insert.setInt(3, 31);
            insert.setInt(4, 4);
            insert.setInt(5, 2);
            insert.setInt(6, 1);
            insert.setInt(7, 1);
            insert.setString(8, "测试库招");
            insert.executeUpdate();
        }
        String before = sha256(book);

        List<BookData> rows;
        try (XqbOpenBook openBook = new XqbOpenBook(book.toString())) {
            rows = openBook.get(emptyBoard(), true);
            assertTrue(openBook.diagnostic().isEmpty());
        }

        assertEquals(1, rows.size());
        assertEquals("a0a1", rows.getFirst().getMove());
        assertEquals(31, rows.getFirst().getScore());
        assertEquals(71.43d, rows.getFirst().getWinRate(), 0.001d);
        assertEquals("测试库招", rows.getFirst().getNote());
        assertEquals(before, sha256(book));
        assertFalse(Files.exists(Path.of(book + "-journal")));
        assertFalse(Files.exists(Path.of(book + "-wal")));
        assertFalse(Files.exists(Path.of(book + "-shm")));
    }

    @Test
    void pfUsesAParameterisedLookupAndSkipsMalformedRows() throws Exception {
        Path book = createLegacy("test.pfBook", "pfBook");
        char[][] board = initialBoard();
        long key = ZobristUtils.getZobristFromBoard(board, true, false);
        try (Connection connection = writable(book);
             PreparedStatement insert = legacyInsert(connection, "pfBook")) {
            addLegacyRow(insert, key, 0xC3B3, 12, 3, 1, 0, 1, "飞相");
            addLegacyRow(insert, key, 0xffff, 99, 1, 0, 0, 1, "坏坐标");
        }

        try (PfOpenBook openBook = new PfOpenBook(book.toString())) {
            List<BookData> rows = openBook.get(board, true);

            assertEquals(1, rows.size());
            assertEquals("a0a1", rows.getFirst().getMove());
            assertEquals("INVALID_ROW", openBook.diagnostic().orElseThrow().code());
        }
    }

    @Test
    void bhReadsTheDocumentedUnsignedKeyLayoutForTheInitialPosition() throws Exception {
        Path book = createLegacy("test.obk", "bhobk");
        char[][] board = initialBoard();
        long key = ZobristUtils.getZobristFromBoard(board, true, false);
        assertEquals(0x628d04d7c9c144aeL, key);
        try (Connection connection = writable(book);
             PreparedStatement insert = legacyInsert(connection, "bhobk")) {
            addLegacyRow(insert, key, 0xC3B3, 8, 2, 2, 0, 1, "兵河兼容行");
        }

        try (BhOpenBook openBook = new BhOpenBook(book.toString())) {
            List<BookData> rows = openBook.get(board, true);

            assertEquals(1, rows.size());
            assertEquals("a0a1", rows.getFirst().getMove());
            assertTrue(openBook.diagnostic().isEmpty());
        }
    }

    @Test
    void boundsOnePositionLookupAndReportsTruncation() throws Exception {
        Path book = createLegacy("large.pfBook", "pfBook");
        char[][] board = initialBoard();
        long key = ZobristUtils.getZobristFromBoard(board, true, false);
        try (Connection connection = writable(book);
             PreparedStatement insert = legacyInsert(connection, "pfBook")) {
            for (int index = 0; index < 300; index++) {
                addLegacyRow(insert, key, 0xC3B3, index, 1, 0, 0, 1, "row-" + index);
            }
        }

        try (PfOpenBook openBook = new PfOpenBook(book.toString())) {
            List<BookData> rows = openBook.get(board, true);

            assertEquals(256, rows.size());
            assertEquals("RESULT_TRUNCATED", openBook.diagnostic().orElseThrow().code());
        }
    }

    @Test
    void rejectsWrongContainerAndWrongSchemaWithStableCodes() throws Exception {
        Path text = temporaryDirectory.resolve("not-sqlite.xqb");
        Files.writeString(text, "not a database", StandardCharsets.UTF_8);
        Path wrongSchema = temporaryDirectory.resolve("wrong.pfBook");
        try (Connection connection = writable(wrongSchema);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE unrelated(value TEXT)");
        }

        OpenBookLoadException containerFailure = assertThrows(OpenBookLoadException.class,
                () -> new XqbOpenBook(text.toString()));
        OpenBookLoadException schemaFailure = assertThrows(OpenBookLoadException.class,
                () -> new PfOpenBook(wrongSchema.toString()));

        assertEquals("UNSUPPORTED_CONTAINER", containerFailure.code());
        assertEquals("UNSUPPORTED_SCHEMA", schemaFailure.code());
        assertFalse(containerFailure.getMessage().contains(text.toAbsolutePath().toString()));
    }

    @Test
    void managerStyleDiagnosticContainsOnlyTheFileName() {
        OpenBookDiagnostic diagnostic = OpenBookDiagnostic.loadFailure(
                Path.of("C:/private/folder/secret.pfBook"),
                new OpenBookLoadException("UNSUPPORTED_FORMAT", "details"));

        assertEquals("UNSUPPORTED_FORMAT", diagnostic.code());
        assertEquals("secret.pfBook", diagnostic.source());
        assertFalse(diagnostic.message().contains("C:/private"));
    }

    private Path createXqb(String name) throws SQLException {
        Path path = temporaryDirectory.resolve(name);
        try (Connection connection = writable(path);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE information(name TEXT, value TEXT)");
            statement.execute("INSERT INTO information VALUES ('version','1')");
            statement.execute("INSERT INTO information VALUES ('type','xiangqi')");
            statement.execute("""
                    CREATE TABLE book(
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        key BLOB, move INTEGER, score INTEGER, win INTEGER,
                        draw INTEGER, lost INTEGER, valid INTEGER, memo TEXT)
                    """);
        }
        return path;
    }

    private Path createLegacy(String name, String table) throws SQLException {
        Path path = temporaryDirectory.resolve(name);
        try (Connection connection = writable(path);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE %s(
                        vkey INTEGER, vmove INTEGER, vscore INTEGER,
                        vwin INTEGER, vdraw INTEGER, vlost INTEGER,
                        vvalid INTEGER, vmemo TEXT)
                    """.formatted(table));
        }
        return path;
    }

    private static PreparedStatement legacyInsert(Connection connection, String table)
            throws SQLException {
        return connection.prepareStatement("""
                INSERT INTO %s(vkey,vmove,vscore,vwin,vdraw,vlost,vvalid,vmemo)
                VALUES(?,?,?,?,?,?,?,?)
                """.formatted(table));
    }

    private static void addLegacyRow(PreparedStatement insert,
                                     long key,
                                     int move,
                                     int score,
                                     int win,
                                     int draw,
                                     int lost,
                                     int valid,
                                     String memo) throws SQLException {
        insert.setLong(1, key);
        insert.setInt(2, move);
        insert.setInt(3, score);
        insert.setInt(4, win);
        insert.setInt(5, draw);
        insert.setInt(6, lost);
        insert.setInt(7, valid);
        insert.setString(8, memo);
        insert.executeUpdate();
    }

    private static Connection writable(Path path) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
    }

    private static char[][] emptyBoard() {
        char[][] board = new char[10][9];
        for (char[] row : board) {
            Arrays.fill(row, ' ');
        }
        return board;
    }

    private static char[][] initialBoard() {
        char[][] board = emptyBoard();
        ChessBoard.initChessBoard(board);
        return board;
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(path));
        return HexFormat.of().formatHex(digest.digest());
    }
}
