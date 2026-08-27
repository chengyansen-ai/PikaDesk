package com.sojourners.chess.book;

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
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class XqbBatchServiceTest {

    private static final byte[] EMPTY_POSITION_KEY = new byte[12];

    @TempDir
    Path temporaryDirectory;

    @BeforeAll
    static void loadSqliteDriver() throws Exception {
        Class.forName("org.sqlite.JDBC");
    }

    @Test
    void mergesSourcesDeduplicatesExactRowsAndProducesCanonicalOutput() throws Exception {
        Row common = new Row(EMPTY_POSITION_KEY, 0x6656, 10, 4, 2, 1, 1, null);
        Row sameMoveDifferentEvidence = new Row(EMPTY_POSITION_KEY, 0x6656,
                20, 8, 1, 0, 1, "复核");
        Row otherMove = new Row(EMPTY_POSITION_KEY, 0x7774, 5, 1, 1, 1, 1, "中炮");
        Path first = createXqb("first.xqb", List.of(otherMove, common));
        Path second = createXqb("second.xqb", List.of(common, sameMoveDifferentEvidence));
        Path forward = temporaryDirectory.resolve("forward.xqb");
        Path reversed = temporaryDirectory.resolve("reversed.xqb");
        XqbBatchService service = new XqbBatchService(testLimits());

        XqbBatchService.BatchReport report = service.build(
                List.of(first, second), forward, () -> false, ignored -> { });
        service.build(List.of(second, first), reversed, () -> false, ignored -> { });

        assertEquals(4, report.scannedRows());
        assertEquals(3, report.writtenRows());
        assertEquals(1, report.duplicateRows());
        assertEquals(0, report.rejectedRows());
        assertEquals(List.of(), report.issues());
        assertEquals(0, report.omittedIssueCount());
        assertEquals(List.of(common, sameMoveDifferentEvidence, otherMove), readRows(forward));
        assertEquals("1", information(forward, "version"));
        assertEquals("xiangqi", information(forward, "type"));
        assertEquals(sha256(forward), sha256(reversed));
    }

    @Test
    void rejectsUnsupportedSchemaWithoutReplacingDestination() throws Exception {
        Path source = temporaryDirectory.resolve("wrong.xqb");
        try (Connection connection = openWritable(source);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE unrelated(value TEXT)");
        }
        Path destination = temporaryDirectory.resolve("existing.xqb");
        byte[] original = "KEEP-EXISTING".getBytes(StandardCharsets.US_ASCII);
        Files.write(destination, original);

        XqbBatchService.BookBatchException failure = assertThrows(
                XqbBatchService.BookBatchException.class,
                () -> new XqbBatchService(testLimits()).build(List.of(source), destination,
                        () -> false, ignored -> { }));

        assertEquals("UNSUPPORTED_SCHEMA", failure.code());
        assertArrayEquals(original, Files.readAllBytes(destination));
        assertNoTemporaryFiles();
    }

    @Test
    void rejectsMalformedRowsAndBoundsIssueSamples() throws Exception {
        List<Row> malformed = List.of(
                new Row(new byte[]{(byte) 0xff}, 0x6656, 0, 0, 0, 0, 1, null),
                new Row(EMPTY_POSITION_KEY, 0xffff, 0, 0, 0, 0, 1, null),
                new Row(EMPTY_POSITION_KEY, 0x6656, 0, -1, 0, 0, 1, null),
                new Row(EMPTY_POSITION_KEY, 0x6656, 0, 0, 0, 0, 2, null),
                new Row(EMPTY_POSITION_KEY, 0x6656, 0, 0, 0, 0, 1,
                        "备注内容超过测试限制"));
        Path source = createXqb("malformed.xqb", malformed);
        Path destination = temporaryDirectory.resolve("clean.xqb");

        XqbBatchService.BatchReport report = new XqbBatchService(testLimits()).build(
                List.of(source), destination, () -> false, ignored -> { });

        assertEquals(5, report.scannedRows());
        assertEquals(0, report.writtenRows());
        assertEquals(5, report.rejectedRows());
        assertEquals(2, report.issues().size());
        assertEquals(3, report.omittedIssueCount());
        assertTrue(report.issues().stream().allMatch(issue -> issue.rowNumber() > 0));
        assertEquals(List.of(), readRows(destination));
    }

    @Test
    void cancellationPreservesExistingDestinationAndRemovesTemporaryFiles() throws Exception {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            rows.add(new Row(EMPTY_POSITION_KEY, 0x6650 + i, i, 0, 0, 0, 1, null));
        }
        Path source = createXqb("many.xqb", rows);
        Path destination = temporaryDirectory.resolve("cancelled.xqb");
        byte[] original = "ORIGINAL".getBytes(StandardCharsets.US_ASCII);
        Files.write(destination, original);
        AtomicBoolean cancelled = new AtomicBoolean();

        XqbBatchService.BookBatchException failure = assertThrows(
                XqbBatchService.BookBatchException.class,
                () -> new XqbBatchService(testLimits()).build(List.of(source), destination,
                        cancelled::get, progress -> {
                            if (progress.scannedRows() >= 2) cancelled.set(true);
                        }));

        assertEquals("CANCELLED", failure.code());
        assertArrayEquals(original, Files.readAllBytes(destination));
        assertNoTemporaryFiles();
    }

    @Test
    void reportsMonotonicProgressAndCanCancelDuringCanonicalWriting() throws Exception {
        List<Row> rows = List.of(
                new Row(EMPTY_POSITION_KEY, 0x6656, 1, 0, 0, 0, 1, null),
                new Row(EMPTY_POSITION_KEY, 0x7774, 2, 0, 0, 0, 1, null),
                new Row(EMPTY_POSITION_KEY, 0x9786, 3, 0, 0, 0, 1, null));
        Path source = createXqb("progress.xqb", rows);
        Path completed = temporaryDirectory.resolve("completed.xqb");
        List<XqbBatchService.Progress> events = new ArrayList<>();
        XqbBatchService service = new XqbBatchService(testLimits());

        service.build(List.of(source), completed, () -> false, events::add);

        assertEquals(XqbBatchService.Phase.VALIDATING, events.getFirst().phase());
        assertEquals(XqbBatchService.Phase.COMPLETED, events.getLast().phase());
        assertTrue(events.stream().anyMatch(event ->
                event.phase() == XqbBatchService.Phase.READING));
        assertTrue(events.stream().anyMatch(event ->
                event.phase() == XqbBatchService.Phase.WRITING));
        for (int i = 1; i < events.size(); i++) {
            assertTrue(events.get(i).scannedRows() >= events.get(i - 1).scannedRows());
            assertTrue(events.get(i).writtenRows() >= events.get(i - 1).writtenRows());
        }

        Path cancelledOutput = temporaryDirectory.resolve("cancel-writing.xqb");
        Files.writeString(cancelledOutput, "PRESERVE", StandardCharsets.US_ASCII);
        AtomicBoolean cancelled = new AtomicBoolean();
        XqbBatchService.BookBatchException failure = assertThrows(
                XqbBatchService.BookBatchException.class,
                () -> service.build(List.of(source), cancelledOutput, cancelled::get,
                        progress -> {
                            if (progress.phase() == XqbBatchService.Phase.WRITING
                                    && progress.writtenRows() >= 2) {
                                cancelled.set(true);
                            }
                        }));

        assertEquals("CANCELLED", failure.code());
        assertEquals("PRESERVE", Files.readString(cancelledOutput,
                StandardCharsets.US_ASCII));
        assertNoTemporaryFiles();
    }

    @Test
    void readsSourcesWithoutChangingThemOrCreatingJournalSidecars() throws Exception {
        Path source = createXqb("read only source.xqb", List.of(
                new Row(EMPTY_POSITION_KEY, 0x6656, 0, 0, 0, 0, 1, null)));
        String before = sha256(source);

        new XqbBatchService(testLimits()).build(List.of(source),
                temporaryDirectory.resolve("result.xqb"), () -> false, ignored -> { });

        assertEquals(before, sha256(source));
        assertFalse(Files.exists(Path.of(source + "-journal")));
        assertFalse(Files.exists(Path.of(source + "-wal")));
        assertFalse(Files.exists(Path.of(source + "-shm")));
    }

    @Test
    void rejectsNonSqliteFilesOversizedFilesAndSourceDestinationOverlap() throws Exception {
        Path text = temporaryDirectory.resolve("text.xqb");
        Files.writeString(text, "not a database", StandardCharsets.US_ASCII);
        Path oversized = temporaryDirectory.resolve("oversized.xqb");
        Files.write(oversized, new byte[1_048_577]);
        XqbBatchService service = new XqbBatchService(testLimits());

        assertEquals("INVALID_SQLITE_HEADER", assertThrows(
                XqbBatchService.BookBatchException.class,
                () -> service.build(List.of(text), temporaryDirectory.resolve("a.xqb"),
                        () -> false, ignored -> { })).code());
        assertEquals("SOURCE_TOO_LARGE", assertThrows(
                XqbBatchService.BookBatchException.class,
                () -> service.build(List.of(oversized), temporaryDirectory.resolve("b.xqb"),
                        () -> false, ignored -> { })).code());
        Path valid = createXqb("same.xqb", List.of());
        assertEquals("SOURCE_DESTINATION_OVERLAP", assertThrows(
                XqbBatchService.BookBatchException.class,
                () -> service.build(List.of(valid), valid, () -> false,
                        ignored -> { })).code());
    }

    @Test
    void rejectsHardLinkedDestinationOverlapAndIncompatibleColumnTypes() throws Exception {
        Path source = createXqb("source.xqb", List.of());
        Path hardLink = temporaryDirectory.resolve("hard-link.xqb");
        Files.createLink(hardLink, source);
        XqbBatchService service = new XqbBatchService(testLimits());

        assertEquals("SOURCE_DESTINATION_OVERLAP", assertThrows(
                XqbBatchService.BookBatchException.class,
                () -> service.build(List.of(source), hardLink, () -> false,
                        ignored -> { })).code());

        Path wrongType = temporaryDirectory.resolve("wrong-type.xqb");
        try (Connection connection = openWritable(wrongType);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE information(name TEXT, value TEXT)");
            statement.execute("INSERT INTO information VALUES ('version', '1')");
            statement.execute("INSERT INTO information VALUES ('type', 'xiangqi')");
            statement.execute("CREATE TABLE book(id INTEGER PRIMARY KEY, key TEXT,"
                    + "move INTEGER, score INTEGER, win INTEGER, draw INTEGER,"
                    + "lost INTEGER, valid INTEGER, memo TEXT)");
        }
        assertEquals("UNSUPPORTED_SCHEMA", assertThrows(
                XqbBatchService.BookBatchException.class,
                () -> service.build(List.of(wrongType),
                        temporaryDirectory.resolve("wrong-type-output.xqb"),
                        () -> false, ignored -> { })).code());
    }

    @Test
    void enforcesGlobalRowLimitBeforeWritingBeyondIt() throws Exception {
        Path source = createXqb("limit.xqb", List.of(
                new Row(EMPTY_POSITION_KEY, 0x6656, 0, 0, 0, 0, 1, null),
                new Row(EMPTY_POSITION_KEY, 0x7774, 0, 0, 0, 0, 1, null),
                new Row(EMPTY_POSITION_KEY, 0x9786, 0, 0, 0, 0, 1, null)));
        Path destination = temporaryDirectory.resolve("limited.xqb");
        Files.writeString(destination, "UNCHANGED", StandardCharsets.US_ASCII);
        XqbBatchService.Limits limits = new XqbBatchService.Limits(
                8, 1_048_576, 2, 128, 24, 2, 1, 5);

        XqbBatchService.BookBatchException failure = assertThrows(
                XqbBatchService.BookBatchException.class,
                () -> new XqbBatchService(limits).build(List.of(source), destination,
                        () -> false, ignored -> { }));

        assertEquals("ROW_LIMIT_EXCEEDED", failure.code());
        assertEquals("UNCHANGED", Files.readString(destination, StandardCharsets.US_ASCII));
        assertNoTemporaryFiles();
    }

    private XqbBatchService.Limits testLimits() {
        return new XqbBatchService.Limits(8, 1_048_576, 100, 128, 24, 2, 1, 5);
    }

    private Path createXqb(String name, List<Row> rows) throws Exception {
        Path path = temporaryDirectory.resolve(name);
        try (Connection connection = openWritable(path);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE information(name TEXT, value TEXT)");
            statement.execute("INSERT INTO information VALUES ('version', '1')");
            statement.execute("INSERT INTO information VALUES ('type', 'xiangqi')");
            statement.execute("CREATE TABLE book(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,"
                    + "key BLOB, move INTEGER, score INTEGER, win INTEGER, draw INTEGER,"
                    + "lost INTEGER, valid INTEGER, memo TEXT)");
            statement.execute("CREATE INDEX idxkey ON book(key)");
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO book(key,move,score,win,draw,lost,valid,memo)"
                            + " VALUES(?,?,?,?,?,?,?,?)")) {
                for (Row row : rows) {
                    insert.setBytes(1, row.key());
                    insert.setInt(2, row.move());
                    insert.setInt(3, row.score());
                    insert.setInt(4, row.win());
                    insert.setInt(5, row.draw());
                    insert.setInt(6, row.lost());
                    insert.setInt(7, row.valid());
                    insert.setString(8, row.memo());
                    insert.executeUpdate();
                }
            }
        }
        return path;
    }

    private static Connection openWritable(Path path) throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
    }

    private static List<Row> readRows(Path path) throws Exception {
        List<Row> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT key,move,score,win,draw,lost,valid,memo FROM book ORDER BY id")) {
            while (result.next()) {
                rows.add(new Row(result.getBytes(1), result.getInt(2), result.getInt(3),
                        result.getInt(4), result.getInt(5), result.getInt(6),
                        result.getInt(7), result.getString(8)));
            }
        }
        return rows;
    }

    private static String information(Path path, String name) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             PreparedStatement query = connection.prepareStatement(
                     "SELECT value FROM information WHERE name = ?")) {
            query.setString(1, name);
            try (ResultSet result = query.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path)));
    }

    private void assertNoTemporaryFiles() throws Exception {
        try (var paths = Files.list(temporaryDirectory)) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString()
                    .startsWith(".pikadesk-book-")));
        }
    }

    private record Row(byte[] key, int move, int score, int win, int draw,
                       int lost, int valid, String memo) {
        private Row {
            key = key.clone();
        }

        @Override
        public byte[] key() {
            return key.clone();
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Row row)) return false;
            return java.util.Arrays.equals(key, row.key)
                    && move == row.move && score == row.score && win == row.win
                    && draw == row.draw && lost == row.lost && valid == row.valid
                    && java.util.Objects.equals(memo, row.memo);
        }

        @Override
        public int hashCode() {
            return 31 * java.util.Arrays.hashCode(key)
                    + java.util.Objects.hash(move, score, win, draw, lost, valid, memo);
        }
    }
}
