package com.sojourners.chess.book;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class XqbResumableBatchServiceTest {

    private static final byte[] EMPTY_POSITION_KEY = new byte[12];

    @TempDir
    Path temporaryDirectory;

    @BeforeAll
    static void loadSqliteDriver() throws Exception {
        Class.forName("org.sqlite.JDBC");
    }

    @Test
    void resumesCompletedSourcesAndMatchesFreshCanonicalBuild() throws Exception {
        Row common = new Row(EMPTY_POSITION_KEY, 0x6656, 10, 4, 2, 1, 1, null);
        Path first = createXqb("first.xqb", List.of(
                common, new Row(EMPTY_POSITION_KEY, 0x7774, 5, 1, 1, 1, 1, "中炮")));
        Path second = createXqb("second.xqb", List.of(
                common, new Row(EMPTY_POSITION_KEY, 0x9786, 7, 2, 0, 0, 1, "屏风马")));
        Path destination = temporaryDirectory.resolve("resumed.xqb");
        byte[] original = "KEEP".getBytes(StandardCharsets.US_ASCII);
        Files.write(destination, original);
        XqbResumableBatchService service = new XqbResumableBatchService(testLimits());
        AtomicBoolean cancelled = new AtomicBoolean();

        XqbBatchService.BookBatchException failure = assertThrows(
                XqbBatchService.BookBatchException.class,
                () -> service.buildOrResume(List.of(first, second), destination,
                        cancelled::get, progress -> {
                            if (progress.phase() == XqbBatchService.Phase.CHECKPOINTING
                                    && progress.completedSources() == 1) {
                                cancelled.set(true);
                            }
                        }));

        assertEquals("CANCELLED", failure.code());
        assertArrayEquals(original, Files.readAllBytes(destination));
        XqbResumableBatchService.RecoveryStatus status = service.inspect(destination);
        assertTrue(status.available());
        assertEquals(1, status.completedSources());
        assertEquals(2, status.sourceCount());
        Path firstChunk = XqbResumableBatchService.recoveryDirectory(destination)
                .resolve("source-0000.xqb");
        String checkpointHash = sha256(firstChunk);

        XqbBatchService.BatchReport resumed = new XqbResumableBatchService(testLimits())
                .buildOrResume(
                List.of(first, second), destination, () -> false, ignored -> { });
        Path fresh = temporaryDirectory.resolve("fresh.xqb");
        new XqbBatchService(testLimits()).build(
                List.of(first, second), fresh, () -> false, ignored -> { });

        assertEquals(4, resumed.scannedRows());
        assertEquals(3, resumed.writtenRows());
        assertEquals(1, resumed.duplicateRows());
        assertEquals(sha256(fresh), sha256(destination));
        assertFalse(service.inspect(destination).available());
        assertFalse(Files.exists(firstChunk));
        assertFalse(checkpointHash.isBlank());
    }

    @Test
    void rejectsChangedSourceAndPreservesCheckpointAndDestination() throws Exception {
        Path first = createXqb("mutable.xqb", List.of(
                new Row(EMPTY_POSITION_KEY, 0x6656, 1, 0, 0, 0, 1, null)));
        Path second = createXqb("later.xqb", List.of(
                new Row(EMPTY_POSITION_KEY, 0x7774, 2, 0, 0, 0, 1, null)));
        Path destination = temporaryDirectory.resolve("changed.xqb");
        Files.writeString(destination, "ORIGINAL", StandardCharsets.US_ASCII);
        XqbResumableBatchService service = new XqbResumableBatchService(testLimits());
        AtomicBoolean cancelled = new AtomicBoolean();
        assertThrows(XqbBatchService.BookBatchException.class,
                () -> service.buildOrResume(List.of(first, second), destination,
                        cancelled::get, progress -> {
                            if (progress.phase() == XqbBatchService.Phase.CHECKPOINTING) {
                                cancelled.set(true);
                            }
                        }));
        try (Connection connection = openWritable(first);
             Statement statement = connection.createStatement()) {
            statement.execute("UPDATE book SET score=99 WHERE id=1");
        }

        XqbBatchService.BookBatchException failure = assertThrows(
                XqbBatchService.BookBatchException.class,
                () -> service.buildOrResume(List.of(first, second), destination,
                        () -> false, ignored -> { }));

        assertEquals("CHECKPOINT_MISMATCH", failure.code());
        assertEquals("ORIGINAL", Files.readString(destination, StandardCharsets.US_ASCII));
        assertTrue(service.inspect(destination).available());
    }

    @Test
    void enforcesTheGlobalRowLimitAcrossSourceCheckpoints() throws Exception {
        Path first = createXqb("limit-a.xqb", List.of(
                new Row(EMPTY_POSITION_KEY, 0x6656, 1, 0, 0, 0, 1, null),
                new Row(EMPTY_POSITION_KEY, 0x7774, 2, 0, 0, 0, 1, null)));
        Path second = createXqb("limit-b.xqb", List.of(
                new Row(EMPTY_POSITION_KEY, 0x9786, 3, 0, 0, 0, 1, null),
                new Row(EMPTY_POSITION_KEY, 0x8786, 4, 0, 0, 0, 1, null)));
        Path destination = temporaryDirectory.resolve("limited.xqb");
        Files.writeString(destination, "UNCHANGED", StandardCharsets.US_ASCII);
        XqbBatchService.Limits limits = new XqbBatchService.Limits(
                8, 1_048_576, 3, 128, 64, 4, 1, 5);

        XqbBatchService.BookBatchException failure = assertThrows(
                XqbBatchService.BookBatchException.class,
                () -> new XqbResumableBatchService(limits).buildOrResume(
                        List.of(first, second), destination, () -> false, ignored -> { }));

        assertEquals("ROW_LIMIT_EXCEEDED", failure.code());
        assertEquals("UNCHANGED", Files.readString(destination, StandardCharsets.US_ASCII));
        assertEquals(1, new XqbResumableBatchService(limits)
                .inspect(destination).completedSources());
    }

    @Test
    void rejectsCorruptManifestAndDiscardKeepsUnknownFiles() throws Exception {
        Path source = createXqb("corrupt.xqb", List.of(
                new Row(EMPTY_POSITION_KEY, 0x6656, 1, 0, 0, 0, 1, null)));
        Path destination = temporaryDirectory.resolve("corrupt-output.xqb");
        XqbResumableBatchService service = new XqbResumableBatchService(testLimits());
        AtomicBoolean cancelled = new AtomicBoolean();
        assertThrows(XqbBatchService.BookBatchException.class,
                () -> service.buildOrResume(List.of(source), destination,
                        cancelled::get, progress -> {
                            if (progress.phase() == XqbBatchService.Phase.CHECKPOINTING) {
                                cancelled.set(true);
                            }
                        }));
        Path workspace = XqbResumableBatchService.recoveryDirectory(destination);
        Files.write(workspace.resolve("manifest.bin"), new byte[]{1, 2, 3});
        Path unknown = workspace.resolve("user-note.txt");
        Files.writeString(unknown, "KEEP", StandardCharsets.US_ASCII);

        XqbBatchService.BookBatchException failure = assertThrows(
                XqbBatchService.BookBatchException.class,
                () -> service.buildOrResume(List.of(source), destination,
                        () -> false, ignored -> { }));

        assertEquals("CHECKPOINT_CORRUPT", failure.code());
        service.discard(destination);
        assertFalse(service.inspect(destination).available());
        assertEquals("KEEP", Files.readString(unknown, StandardCharsets.US_ASCII));
    }

    @Test
    void refusesAConcurrentWriterHoldingTheWorkspaceLock() throws Exception {
        Path source = createXqb("locked.xqb", List.of());
        Path destination = temporaryDirectory.resolve("locked-output.xqb");
        Path workspace = XqbResumableBatchService.recoveryDirectory(destination);
        Files.createDirectory(workspace);
        Path lockPath = workspace.resolve("batch.lock");
        try (FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            XqbBatchService.BookBatchException failure = assertThrows(
                    XqbBatchService.BookBatchException.class,
                    () -> new XqbResumableBatchService(testLimits()).buildOrResume(
                            List.of(source), destination, () -> false, event -> { }));
            assertEquals("CHECKPOINT_BUSY", failure.code());
        }
    }

    @Test
    void rejectsAModifiedCanonicalChunkBeforeFinalReplacement() throws Exception {
        Path source = createXqb("chunk-source.xqb", List.of(
                new Row(EMPTY_POSITION_KEY, 0x6656, 1, 0, 0, 0, 1, null)));
        Path destination = temporaryDirectory.resolve("chunk-output.xqb");
        Files.writeString(destination, "PRESERVE", StandardCharsets.US_ASCII);
        XqbResumableBatchService service = new XqbResumableBatchService(testLimits());
        AtomicBoolean cancelled = new AtomicBoolean();
        assertThrows(XqbBatchService.BookBatchException.class,
                () -> service.buildOrResume(List.of(source), destination,
                        cancelled::get, progress -> {
                            if (progress.phase() == XqbBatchService.Phase.CHECKPOINTING
                                    && progress.completedSources() == 1) {
                                cancelled.set(true);
                            }
                        }));
        Path chunk = XqbResumableBatchService.recoveryDirectory(destination)
                .resolve("source-0000.xqb");
        Files.write(chunk, new byte[]{99}, StandardOpenOption.APPEND);

        XqbBatchService.BookBatchException failure = assertThrows(
                XqbBatchService.BookBatchException.class,
                () -> new XqbResumableBatchService(testLimits()).buildOrResume(
                        List.of(source), destination, () -> false, ignored -> { }));

        assertEquals("CHECKPOINT_CORRUPT", failure.code());
        assertEquals("PRESERVE", Files.readString(destination, StandardCharsets.US_ASCII));
    }

    @Test
    void resumesAFullyCheckpointedBatchAtTheFinalMerge() throws Exception {
        Path source = createXqb("ready.xqb", List.of(
                new Row(EMPTY_POSITION_KEY, 0x6656, 1, 0, 0, 0, 1, null)));
        Path destination = temporaryDirectory.resolve("ready-output.xqb");
        AtomicBoolean cancelled = new AtomicBoolean();
        XqbResumableBatchService firstRun = new XqbResumableBatchService(testLimits());
        assertThrows(XqbBatchService.BookBatchException.class,
                () -> firstRun.buildOrResume(List.of(source), destination,
                        cancelled::get, progress -> {
                            if (progress.phase() == XqbBatchService.Phase.CHECKPOINTING
                                    && progress.completedSources() == 1) {
                                cancelled.set(true);
                            }
                        }));
        assertEquals(1, firstRun.inspect(destination).completedSources());

        XqbBatchService.BatchReport report = new XqbResumableBatchService(testLimits())
                .buildOrResume(List.of(source), destination, () -> false, ignored -> { });

        assertEquals(1, report.scannedRows());
        assertEquals(1, report.writtenRows());
        assertTrue(Files.isRegularFile(destination));
    }

    private XqbBatchService.Limits testLimits() {
        return new XqbBatchService.Limits(8, 1_048_576, 100,
                128, 64, 4, 1, 5);
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

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[16_384];
            for (int count; (count = input.read(buffer)) >= 0; ) {
                if (count > 0) digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
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
    }
}
