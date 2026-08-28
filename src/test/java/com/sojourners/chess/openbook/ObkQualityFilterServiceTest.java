package com.sojourners.chess.openbook;

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
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ObkQualityFilterServiceTest {

    private static final int MOVE = 0xc3b3;

    @TempDir
    Path temporaryDirectory;

    @BeforeAll
    static void loadSqliteDriver() throws Exception {
        Class.forName("org.sqlite.JDBC");
    }

    @Test
    void keepsHighConfidenceRowsAndDeterministicallyResolvesConflicts() throws Exception {
        Path source = createObk("mixed.obk");
        double realKey = Double.longBitsToDouble(0x9123456789abcdefL);
        try (Connection connection = openWritable(source);
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO bhobk(vkey,vmove,vscore,vwin,vdraw,vlost,vvalid,vmemo)
                     VALUES(?,?,?,?,?,?,?,?)
                     """)) {
            insert(insert, 123L, MOVE, 3, 0, 0, 0, 1, "较低");
            insert(insert, 123L, MOVE, 5, 0, 0, 0, 1, "精选");
            insert(insert, 123L, MOVE, 5, 0, 0, 0, 1, "精选");
            insert(insert, realKey, 0xc3a3, 4, 1, 0, 0, 1, "负键");
            insert(insert, 200L, 0xc4b4, 2, 1, 0, 0, 1, "低分");
            insert(insert, 201L, 0xc5b5, 5, 1, 0, 0, 0, "停用");
            insert(insert, 202L, 0xffff, 5, 1, 0, 0, 1, "坏坐标");
            insert(insert, 203L, 0xc6b6, 5, -1, 0, 0, 1, "坏统计");
            insert(insert, null, 0xc7b7, 5, 1, 0, 0, 1, "空键");
            insert.setLong(1, 204L);
            insert.setInt(2, 0xc8b8);
            insert.setInt(3, 5);
            insert.setInt(4, 1);
            insert.setInt(5, 0);
            insert.setInt(6, 0);
            insert.setInt(7, 1);
            insert.setBytes(8, new byte[]{(byte) 0x80});
            insert.executeUpdate();
        }
        String sourceHash = sha256(source);
        Path destination = temporaryDirectory.resolve("selected.obk");

        ObkQualityFilterService.Report report = new ObkQualityFilterService(
                testLimits()).build(source, destination, 3, () -> false, ignored -> { });

        assertEquals(10, report.scannedRows());
        assertEquals(2, report.writtenRows());
        assertEquals(2, report.filteredRows());
        assertEquals(2, report.conflictRows());
        assertEquals(4, report.rejectedRows());
        assertEquals(sourceHash, sha256(source));
        assertFalse(Files.exists(Path.of(source + "-journal")));
        assertFalse(Files.exists(Path.of(source + "-wal")));
        assertFalse(Files.exists(Path.of(source + "-shm")));

        try (Connection output = DriverManager.getConnection("jdbc:sqlite:" + destination);
             Statement query = output.createStatement();
             ResultSet rows = query.executeQuery("""
                     SELECT typeof(vkey),vkey,vmove,vscore,vwin,vmemo
                     FROM bhobk ORDER BY vscore DESC
                     """)) {
            assertEquals(true, rows.next());
            assertEquals("integer", rows.getString(1));
            assertEquals(123L, rows.getLong(2));
            assertEquals(MOVE, rows.getInt(3));
            assertEquals(5, rows.getInt(4));
            assertEquals("精选", rows.getString(6));
            assertEquals(true, rows.next());
            assertEquals("real", rows.getString(1));
            assertEquals(Double.doubleToRawLongBits(realKey),
                    Double.doubleToRawLongBits(rows.getDouble(2)));
            assertEquals(false, rows.next());
        }
        new BhOpenBook(destination.toString()).close();
        assertNoTemporaryFiles();
    }

    @Test
    void cancellationPreservesExistingDestinationAndCleansTemporaryFiles() throws Exception {
        Path source = createObk("cancel-source.obk");
        try (Connection connection = openWritable(source);
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO bhobk(vkey,vmove,vscore,vwin,vdraw,vlost,vvalid,vmemo)
                     VALUES(?,?,?,?,?,?,?,?)
                     """)) {
            for (int i = 0; i < 20; i++) {
                insert(insert, 1_000L + i, MOVE, 5, 1, 0, 0, 1, null);
            }
        }
        Path destination = temporaryDirectory.resolve("existing.obk");
        byte[] original = "PRESERVE".getBytes(StandardCharsets.US_ASCII);
        Files.write(destination, original);
        AtomicBoolean cancelled = new AtomicBoolean();

        ObkQualityFilterService.FilterException failure = assertThrows(
                ObkQualityFilterService.FilterException.class,
                () -> new ObkQualityFilterService(testLimits()).build(
                        source, destination, 3, cancelled::get, progress -> {
                            if (progress.scannedRows() >= 2) cancelled.set(true);
                        }));

        assertEquals("CANCELLED", failure.code());
        assertArrayEquals(original, Files.readAllBytes(destination));
        assertNoTemporaryFiles();
    }

    @Test
    void unsupportedSchemaDoesNotReplaceDestination() throws Exception {
        Path source = temporaryDirectory.resolve("wrong.obk");
        try (Connection connection = openWritable(source);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE unrelated(value TEXT)");
        }
        Path destination = temporaryDirectory.resolve("unchanged.obk");
        byte[] original = "UNCHANGED".getBytes(StandardCharsets.US_ASCII);
        Files.write(destination, original);

        ObkQualityFilterService.FilterException failure = assertThrows(
                ObkQualityFilterService.FilterException.class,
                () -> new ObkQualityFilterService(testLimits()).build(
                        source, destination, 3, () -> false, ignored -> { }));

        assertEquals("UNSUPPORTED_SCHEMA", failure.code());
        assertArrayEquals(original, Files.readAllBytes(destination));
        assertNoTemporaryFiles();
    }

    @Test
    void immutableReadRejectsDatabaseSidecarsInsteadOfIgnoringUncheckpointedData()
            throws Exception {
        Path source = createObk("active.obk");
        Files.write(Path.of(source + "-wal"), new byte[]{1, 2, 3});
        Path destination = temporaryDirectory.resolve("preserved.obk");
        byte[] original = "PRESERVED".getBytes(StandardCharsets.US_ASCII);
        Files.write(destination, original);

        ObkQualityFilterService.FilterException failure = assertThrows(
                ObkQualityFilterService.FilterException.class,
                () -> new ObkQualityFilterService(testLimits()).build(
                        source, destination, 3, () -> false, ignored -> { }));

        assertEquals("ACTIVE_DATABASE_SIDECAR", failure.code());
        assertArrayEquals(original, Files.readAllBytes(destination));
        assertNoTemporaryFiles();
    }

    private ObkQualityFilterService.Limits testLimits() {
        return new ObkQualityFilterService.Limits(1_048_576, 100, 128, 2, 2);
    }

    private Path createObk(String name) throws Exception {
        Path path = temporaryDirectory.resolve(name);
        try (Connection connection = openWritable(path);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE bhobk(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        vkey INTEGER, vmove INTEGER, vscore INTEGER,
                        vwin INTEGER, vdraw INTEGER, vlost INTEGER,
                        vvalid INTEGER, vmemo BLOB, vindex INTEGER)
                    """);
            statement.execute("CREATE INDEX idxkey ON bhobk(vkey)");
        }
        return path;
    }

    private static Connection openWritable(Path path) throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
    }

    private static void insert(PreparedStatement insert, Object key, int move, int score,
                               int win, int draw, int lost, int valid, String memo)
            throws Exception {
        insert.setObject(1, key);
        insert.setInt(2, move);
        insert.setInt(3, score);
        insert.setInt(4, win);
        insert.setInt(5, draw);
        insert.setInt(6, lost);
        insert.setInt(7, valid);
        insert.setString(8, memo);
        insert.executeUpdate();
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return HexFormat.of().withUpperCase().formatHex(digest.digest());
    }

    private void assertNoTemporaryFiles() throws Exception {
        try (var files = Files.list(temporaryDirectory)) {
            assertFalse(files.anyMatch(path ->
                    path.getFileName().toString().startsWith(".pikadesk-obk-")));
        }
    }
}
