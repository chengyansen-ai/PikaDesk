package com.sojourners.chess.openbook;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteLimits;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Produces a smaller, canonical standard OBK from one user-supplied standard
 * SQLite OBK. It never invents positions: only source rows that pass the
 * configured quality threshold can reach the output.
 */
public final class ObkQualityFilterService {

    private static final byte[] SQLITE_HEADER = "SQLite format 3\0"
            .getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_ISSUE_SAMPLES = 32;
    private static final Map<String, Set<String>> REQUIRED_COLUMNS = Map.of(
            "vkey", Set.of("INTEGER", "REAL", "NUMERIC"),
            "vmove", Set.of("INTEGER", "REAL", "NUMERIC"),
            "vscore", Set.of("INTEGER", "REAL", "NUMERIC"),
            "vwin", Set.of("INTEGER", "REAL", "NUMERIC"),
            "vdraw", Set.of("INTEGER", "REAL", "NUMERIC"),
            "vlost", Set.of("INTEGER", "REAL", "NUMERIC"),
            "vvalid", Set.of("INTEGER", "REAL", "NUMERIC"),
            "vmemo", Set.of("TEXT", "BLOB"));

    private final Limits limits;

    public ObkQualityFilterService() {
        this(Limits.defaults());
    }

    public ObkQualityFilterService(Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public Report build(Path source,
                        Path destination,
                        int minimumScore,
                        BooleanSupplier cancellationRequested,
                        Consumer<Progress> progressListener) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(cancellationRequested, "cancellationRequested");
        Objects.requireNonNull(progressListener, "progressListener");
        Path input = validateSource(source);
        Path target = validateTarget(destination, input);
        MutableReport report = new MutableReport();
        checkCancelled(cancellationRequested);
        progressListener.accept(report.progress(Phase.VALIDATING, input));

        Path output = null;
        try {
            output = Files.createTempFile(target.getParent(), ".pikadesk-obk-", ".tmp");
            try (Connection sourceConnection = openReadOnly(input);
                 Connection outputConnection = openWritable(output)) {
                validateSchema(sourceConnection);
                createWorkingSchema(outputConnection);
                filterRows(sourceConnection, outputConnection, minimumScore, report,
                        cancellationRequested, progressListener, input);
                writeCanonicalRows(outputConnection, report, cancellationRequested,
                        progressListener, output);
                verifyOutput(outputConnection);
            }
            checkCancelled(cancellationRequested);
            replaceAtomically(output, target);
            output = null;
            progressListener.accept(report.progress(Phase.COMPLETED, target));
            return report.snapshot();
        } catch (FilterException failure) {
            throw failure;
        } catch (SQLException failure) {
            throw new FilterException("SQLITE_FAILURE",
                    "SQLite could not safely filter the OBK", failure);
        } finally {
            deleteDatabaseFiles(output);
        }
    }

    private Path validateSource(Path selected) throws IOException {
        String fileName = fileName(selected);
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".obk")) {
            throw new FilterException("UNSUPPORTED_FORMAT", "source must use .obk");
        }
        if (!Files.isRegularFile(selected)) {
            throw new FilterException("NOT_REGULAR_FILE", "source is not a regular file");
        }
        for (String suffix : List.of("-journal", "-wal", "-shm")) {
            if (Files.exists(Path.of(selected + suffix), LinkOption.NOFOLLOW_LINKS)) {
                throw new FilterException("ACTIVE_DATABASE_SIDECAR",
                        "source has a SQLite sidecar and cannot be treated as immutable");
            }
        }
        long size = Files.size(selected);
        if (size == 0 || size > limits.maxSourceBytes()) {
            throw new FilterException("SOURCE_SIZE_LIMIT", "source size is outside the limit");
        }
        byte[] header = new byte[SQLITE_HEADER.length];
        try (InputStream input = Files.newInputStream(selected)) {
            if (input.readNBytes(header, 0, header.length) != header.length
                    || !Arrays.equals(header, SQLITE_HEADER)) {
                throw new FilterException("INVALID_SQLITE_HEADER",
                        "source is not a standard SQLite 3 container");
            }
        }
        return selected.toRealPath();
    }

    private static Path validateTarget(Path selected, Path source) throws IOException {
        String fileName = fileName(selected);
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".obk")) {
            throw new FilterException("UNSUPPORTED_FORMAT", "destination must use .obk");
        }
        Path target = selected.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new FilterException("INVALID_DESTINATION",
                    "destination parent must already exist");
        }
        if (target.equals(source) || Files.exists(target) && Files.isSameFile(source, target)) {
            throw new FilterException("SOURCE_DESTINATION_OVERLAP",
                    "source and destination must be different files");
        }
        return target;
    }

    private Connection openReadOnly(Path path) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setSharedCache(false);
        config.enableLoadExtension(false);
        config.setBusyTimeout(1_000);
        config.setCacheSize(-8_192);
        String uri = path.toUri().toASCIIString() + "?mode=ro&cache=private&immutable=1";
        Connection connection = config.createConnection("jdbc:sqlite:" + uri);
        configureLimits(connection);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA query_only=ON");
            statement.execute("PRAGMA trusted_schema=OFF");
        }
        return connection;
    }

    private static Connection openWritable(Path path) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setSharedCache(false);
        config.enableLoadExtension(false);
        config.setBusyTimeout(1_000);
        Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + path.toAbsolutePath(), config.toProperties());
        configureLimits(connection);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=DELETE");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA trusted_schema=OFF");
        }
        return connection;
    }

    private static void configureLimits(Connection connection) throws SQLException {
        if (!(connection instanceof SQLiteConnection sqlite)) return;
        sqlite.setLimit(SQLiteLimits.SQLITE_LIMIT_LENGTH, 65_536);
        sqlite.setLimit(SQLiteLimits.SQLITE_LIMIT_SQL_LENGTH, 32_768);
        sqlite.setLimit(SQLiteLimits.SQLITE_LIMIT_COLUMN, 64);
        sqlite.setLimit(SQLiteLimits.SQLITE_LIMIT_EXPR_DEPTH, 32);
        sqlite.setLimit(SQLiteLimits.SQLITE_LIMIT_COMPOUND_SELECT, 8);
        sqlite.setLimit(SQLiteLimits.SQLITE_LIMIT_VDBE_OP, 100_000);
        sqlite.setLimit(SQLiteLimits.SQLITE_LIMIT_FUNCTION_ARG, 16);
        sqlite.setLimit(SQLiteLimits.SQLITE_LIMIT_ATTACHED, 0);
        sqlite.setLimit(SQLiteLimits.SQLITE_LIMIT_VARIABLE_NUMBER, 32);
        sqlite.setLimit(SQLiteLimits.SQLITE_LIMIT_TRIGGER_DEPTH, 0);
        sqlite.setLimit(SQLiteLimits.SQLITE_LIMIT_WORKER_THREADS, 0);
    }

    private void validateSchema(Connection connection) throws SQLException, FilterException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT type FROM sqlite_master WHERE lower(name)=lower('bhobk')")) {
            query.setQueryTimeout(limits.queryTimeoutSeconds());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next() || !"table".equalsIgnoreCase(rows.getString(1))
                        || rows.next()) {
                    throw new FilterException("UNSUPPORTED_SCHEMA",
                            "source has no unambiguous bhobk table");
                }
            }
        }
        Map<String, String> actual = new LinkedHashMap<>();
        try (Statement query = connection.createStatement();
             ResultSet rows = query.executeQuery("PRAGMA table_info('bhobk')")) {
            while (rows.next()) {
                actual.put(rows.getString("name").toLowerCase(Locale.ROOT),
                        affinity(rows.getString("type")));
            }
        }
        for (Map.Entry<String, Set<String>> required : REQUIRED_COLUMNS.entrySet()) {
            if (!required.getValue().contains(actual.get(required.getKey()))) {
                throw new FilterException("UNSUPPORTED_SCHEMA",
                        "bhobk has an incompatible " + required.getKey() + " column");
            }
        }
    }

    private static String affinity(String declaredType) {
        String type = declaredType == null ? "" : declaredType.toUpperCase(Locale.ROOT);
        if (type.contains("INT")) return "INTEGER";
        if (type.contains("CHAR") || type.contains("CLOB") || type.contains("TEXT")) {
            return "TEXT";
        }
        if (type.isBlank() || type.contains("BLOB")) return "BLOB";
        if (type.contains("REAL") || type.contains("FLOA") || type.contains("DOUB")) {
            return "REAL";
        }
        return "NUMERIC";
    }

    private static void createWorkingSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE candidates(
                        key_kind INTEGER NOT NULL,
                        key_bits INTEGER NOT NULL,
                        vmove INTEGER NOT NULL,
                        vscore INTEGER NOT NULL,
                        vwin INTEGER NOT NULL,
                        vdraw INTEGER NOT NULL,
                        vlost INTEGER NOT NULL,
                        memo_null INTEGER NOT NULL,
                        vmemo TEXT NOT NULL,
                        source_id INTEGER NOT NULL,
                        PRIMARY KEY(key_kind,key_bits,vmove)
                    ) WITHOUT ROWID
                    """);
            statement.execute("""
                    CREATE TABLE bhobk(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        vkey INTEGER,
                        vmove INTEGER,
                        vscore INTEGER,
                        vwin INTEGER,
                        vdraw INTEGER,
                        vlost INTEGER,
                        vvalid INTEGER,
                        vmemo TEXT,
                        vindex INTEGER)
                    """);
        }
    }

    private void filterRows(Connection source,
                            Connection destination,
                            int minimumScore,
                            MutableReport report,
                            BooleanSupplier cancellationRequested,
                            Consumer<Progress> progressListener,
                            Path sourcePath) throws SQLException, IOException {
        String readSql = """
                SELECT typeof(vkey),vkey,vmove,vscore,vwin,vdraw,vlost,vvalid,vmemo
                FROM bhobk
                """;
        String insertSql = """
                INSERT OR IGNORE INTO candidates
                (key_kind,key_bits,vmove,vscore,vwin,vdraw,vlost,memo_null,vmemo,source_id)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """;
        String existingSql = """
                SELECT vscore,vwin,vdraw,vlost,memo_null,vmemo,source_id
                FROM candidates WHERE key_kind=? AND key_bits=? AND vmove=?
                """;
        String updateSql = """
                UPDATE candidates SET vscore=?,vwin=?,vdraw=?,vlost=?,
                    memo_null=?,vmemo=?,source_id=?
                WHERE key_kind=? AND key_bits=? AND vmove=?
                """;
        destination.setAutoCommit(false);
        try (Statement query = source.createStatement();
             PreparedStatement insert = destination.prepareStatement(insertSql);
             PreparedStatement existing = destination.prepareStatement(existingSql);
             PreparedStatement update = destination.prepareStatement(updateSql)) {
            query.setQueryTimeout(limits.queryTimeoutSeconds());
            query.setFetchSize(Math.min(limits.progressEveryRows(), 4096));
            try (ResultSet rows = query.executeQuery(readSql)) {
                while (rows.next()) {
                    report.scannedRows++;
                    if (report.scannedRows > limits.maxRows()) {
                        throw new FilterException("ROW_LIMIT_EXCEEDED",
                                "source exceeds the configured row limit");
                    }
                    try {
                        Candidate candidate = readCandidate(
                                rows, minimumScore, report.scannedRows);
                        if (candidate == null) {
                            report.filteredRows++;
                        } else if (!insertCandidate(insert, candidate)) {
                            report.conflictRows++;
                            Candidate current = readExisting(existing, candidate);
                            if (candidate.betterThan(current)) {
                                updateCandidate(update, candidate);
                            }
                        }
                    } catch (InvalidRow invalid) {
                        report.reject(invalid, report.scannedRows);
                    }
                    if (report.scannedRows % 4096 == 0) destination.commit();
                    if (report.scannedRows % limits.progressEveryRows() == 0) {
                        progressListener.accept(report.progress(Phase.READING, sourcePath));
                        checkCancelled(cancellationRequested);
                    }
                }
            }
            destination.commit();
        } catch (IOException | SQLException | RuntimeException failure) {
            rollbackQuietly(destination);
            throw failure;
        }
        progressListener.accept(report.progress(Phase.READING, sourcePath));
        checkCancelled(cancellationRequested);
    }

    private void writeCanonicalRows(Connection connection,
                                    MutableReport report,
                                    BooleanSupplier cancellationRequested,
                                    Consumer<Progress> progressListener,
                                    Path output) throws SQLException, IOException {
        String readSql = """
                SELECT key_kind,key_bits,vmove,vscore,vwin,vdraw,vlost,
                       memo_null,vmemo
                FROM candidates ORDER BY key_kind,key_bits,vmove
                """;
        String insertSql = """
                INSERT INTO bhobk(vkey,vmove,vscore,vwin,vdraw,vlost,vvalid,vmemo,vindex)
                VALUES(?,?,?,?,?,?,1,?,NULL)
                """;
        connection.setAutoCommit(false);
        try (Statement query = connection.createStatement();
             ResultSet rows = query.executeQuery(readSql);
             PreparedStatement insert = connection.prepareStatement(insertSql)) {
            while (rows.next()) {
                int keyKind = rows.getInt(1);
                long keyBits = rows.getLong(2);
                if (keyKind == 0) insert.setLong(1, keyBits);
                else insert.setDouble(1, Double.longBitsToDouble(keyBits));
                for (int column = 3; column <= 7; column++) {
                    insert.setInt(column - 1, rows.getInt(column));
                }
                if (rows.getInt(8) == 1) insert.setString(7, null);
                else insert.setString(7, rows.getString(9));
                insert.executeUpdate();
                report.writtenRows++;
                if (report.writtenRows % limits.progressEveryRows() == 0) {
                    progressListener.accept(report.progress(Phase.WRITING, output));
                    checkCancelled(cancellationRequested);
                }
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE INDEX idxkey ON bhobk(vkey)");
                statement.execute("DROP TABLE candidates");
            }
            connection.commit();
        } catch (IOException | SQLException | RuntimeException failure) {
            rollbackQuietly(connection);
            throw failure;
        }
        progressListener.accept(report.progress(Phase.WRITING, output));
        checkCancelled(cancellationRequested);
    }

    private Candidate readCandidate(ResultSet rows,
                                    int minimumScore,
                                    long sourceId)
            throws SQLException, InvalidRow {
        Key key = readKey(rows, 1, 2);
        int move = requiredInt(rows, 3, "vmove");
        try {
            SafeSqliteOpenBookSupport.requireC90Move(move);
        } catch (SafeSqliteOpenBookSupport.InvalidBookRow invalid) {
            throw invalid("INVALID_MOVE", "vmove is not a C90 coordinate move");
        }
        int score = requiredInt(rows, 4, "vscore");
        int win = nonNegativeInt(rows, 5, "vwin");
        int draw = nonNegativeInt(rows, 6, "vdraw");
        int lost = nonNegativeInt(rows, 7, "vlost");
        int valid = requiredInt(rows, 8, "vvalid");
        if (valid != 0 && valid != 1) {
            throw invalid("INVALID_VALID_FLAG", "vvalid must be zero or one");
        }
        if (valid == 0 || score < minimumScore) return null;
        byte[] memoBytes = rows.getBytes(9);
        boolean memoNull = memoBytes == null;
        String memo = memoNull ? "" : decodeMemo(memoBytes);
        return new Candidate(key.kind, key.bits, move, score, win, draw, lost,
                memoNull, memo, sourceId);
    }

    private static Key readKey(ResultSet rows, int typeColumn, int valueColumn)
            throws SQLException, InvalidRow {
        String type = rows.getString(typeColumn);
        if ("integer".equals(type)) {
            long value = rows.getLong(valueColumn);
            if (rows.wasNull()) throw invalid("INVALID_KEY", "vkey is null");
            return new Key(0, value);
        }
        if ("real".equals(type)) {
            double value = rows.getDouble(valueColumn);
            if (rows.wasNull() || !Double.isFinite(value)) {
                throw invalid("INVALID_KEY", "vkey is not a finite real value");
            }
            return new Key(1, Double.doubleToRawLongBits(value));
        }
        throw invalid("INVALID_KEY", "vkey is neither an integer nor a finite real");
    }

    private static int requiredInt(ResultSet rows, int column, String name)
            throws SQLException, InvalidRow {
        long value = rows.getLong(column);
        if (rows.wasNull() || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw invalid("INVALID_NUMBER", name + " is outside the 32-bit integer range");
        }
        return (int) value;
    }

    private static int nonNegativeInt(ResultSet rows, int column, String name)
            throws SQLException, InvalidRow {
        int value = requiredInt(rows, column, name);
        if (value < 0) throw invalid("INVALID_STATISTIC", name + " must not be negative");
        return value;
    }

    private String decodeMemo(byte[] bytes) throws InvalidRow {
        if (bytes.length > limits.maxMemoBytes()) {
            throw invalid("MEMO_TOO_LARGE", "vmemo exceeds the configured byte limit");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw invalid("INVALID_MEMO_ENCODING", "vmemo is not strict UTF-8");
        }
    }

    private static boolean insertCandidate(PreparedStatement insert, Candidate row)
            throws SQLException {
        insert.setInt(1, row.keyKind);
        insert.setLong(2, row.keyBits);
        insert.setInt(3, row.move);
        insert.setInt(4, row.score);
        insert.setInt(5, row.win);
        insert.setInt(6, row.draw);
        insert.setInt(7, row.lost);
        insert.setInt(8, row.memoNull ? 1 : 0);
        insert.setString(9, row.memo);
        insert.setLong(10, row.sourceId);
        return insert.executeUpdate() == 1;
    }

    private static Candidate readExisting(PreparedStatement query, Candidate key)
            throws SQLException, FilterException {
        query.setInt(1, key.keyKind);
        query.setLong(2, key.keyBits);
        query.setInt(3, key.move);
        try (ResultSet rows = query.executeQuery()) {
            if (!rows.next()) {
                throw new FilterException("STAGING_INTEGRITY_FAILURE",
                        "conflicting candidate disappeared from staging");
            }
            Candidate result = new Candidate(key.keyKind, key.keyBits, key.move,
                    rows.getInt(1), rows.getInt(2), rows.getInt(3), rows.getInt(4),
                    rows.getInt(5) == 1, rows.getString(6), rows.getLong(7));
            if (rows.next()) {
                throw new FilterException("STAGING_INTEGRITY_FAILURE",
                        "staging contains duplicate candidate keys");
            }
            return result;
        }
    }

    private static void updateCandidate(PreparedStatement update, Candidate row)
            throws SQLException, FilterException {
        update.setInt(1, row.score);
        update.setInt(2, row.win);
        update.setInt(3, row.draw);
        update.setInt(4, row.lost);
        update.setInt(5, row.memoNull ? 1 : 0);
        update.setString(6, row.memo);
        update.setLong(7, row.sourceId);
        update.setInt(8, row.keyKind);
        update.setLong(9, row.keyBits);
        update.setInt(10, row.move);
        if (update.executeUpdate() != 1) {
            throw new FilterException("STAGING_INTEGRITY_FAILURE",
                    "could not replace the selected candidate");
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Preserve the primary failure.
        }
    }

    private static InvalidRow invalid(String code, String message) {
        return new InvalidRow(code, message);
    }

    private static void verifyOutput(Connection connection) throws SQLException, FilterException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA quick_check")) {
            if (!rows.next() || !"ok".equalsIgnoreCase(rows.getString(1))) {
                throw new FilterException("OUTPUT_INTEGRITY_FAILURE",
                        "generated OBK did not pass SQLite quick_check");
            }
        }
    }

    private static void replaceAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteDatabaseFiles(Path database) {
        if (database == null) return;
        for (String suffix : List.of("", "-journal", "-wal", "-shm")) {
            try {
                Files.deleteIfExists(Path.of(database + suffix));
            } catch (IOException ignored) {
                // A primary failure is more useful than a best-effort cleanup failure.
            }
        }
    }

    private static String fileName(Path path) {
        return path.getFileName() == null ? "" : path.getFileName().toString();
    }

    private static void checkCancelled(BooleanSupplier cancellationRequested)
            throws FilterException {
        if (cancellationRequested.getAsBoolean()) {
            throw new FilterException("CANCELLED", "OBK filtering was cancelled");
        }
    }

    private static final class MutableReport {
        private final List<Issue> issues = new ArrayList<>();
        private long scannedRows;
        private long writtenRows;
        private long filteredRows;
        private long conflictRows;
        private long rejectedRows;
        private long omittedIssueCount;

        private void reject(InvalidRow invalid, long rowNumber) {
            rejectedRows++;
            if (issues.size() < MAX_ISSUE_SAMPLES) {
                issues.add(new Issue(invalid.code, invalid.getMessage(), rowNumber));
            } else {
                omittedIssueCount++;
            }
        }

        private Progress progress(Phase phase, Path currentFile) {
            return new Progress(phase, scannedRows, filteredRows, conflictRows,
                    rejectedRows, writtenRows, currentFile);
        }

        private Report snapshot() {
            return new Report(scannedRows, writtenRows, filteredRows, conflictRows,
                    rejectedRows, issues, omittedIssueCount);
        }
    }

    private record Key(int kind, long bits) { }

    private record Candidate(int keyKind,
                             long keyBits,
                             int move,
                             int score,
                             int win,
                             int draw,
                             int lost,
                             boolean memoNull,
                             String memo,
                             long sourceId) {
        private boolean betterThan(Candidate other) {
            if (score != other.score) return score > other.score;
            long games = (long) win + draw + lost;
            long otherGames = (long) other.win + other.draw + other.lost;
            if (games != otherGames) return games > otherGames;
            if (win != other.win) return win > other.win;
            if (draw != other.draw) return draw > other.draw;
            if (lost != other.lost) return lost < other.lost;
            if (memoNull != other.memoNull) return !memoNull;
            int memoOrder = memo.compareTo(other.memo);
            if (memoOrder != 0) return memoOrder < 0;
            return sourceId < other.sourceId;
        }
    }

    private static final class InvalidRow extends Exception {
        private final String code;

        private InvalidRow(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    public record Limits(long maxSourceBytes,
                         long maxRows,
                         int maxMemoBytes,
                         int progressEveryRows,
                         int queryTimeoutSeconds) {
        public Limits {
            if (maxSourceBytes < 1 || maxRows < 1 || maxMemoBytes < 1
                    || progressEveryRows < 1 || queryTimeoutSeconds < 1) {
                throw new IllegalArgumentException("invalid OBK filter limits");
            }
        }

        public static Limits defaults() {
            return new Limits(8L * 1024 * 1024 * 1024,
                    20_000_000, 4_096, 100_000, 600);
        }
    }

    public enum Phase {
        VALIDATING,
        READING,
        WRITING,
        COMPLETED
    }

    public record Progress(Phase phase,
                           long scannedRows,
                           long filteredRows,
                           long conflictRows,
                           long rejectedRows,
                           long writtenRows,
                           Path currentFile) {
        public Progress {
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(currentFile, "currentFile");
            if (scannedRows < 0 || filteredRows < 0 || conflictRows < 0
                    || rejectedRows < 0 || writtenRows < 0) {
                throw new IllegalArgumentException("invalid OBK filter progress");
            }
        }
    }

    public record Issue(String code, String message, long rowNumber) {
        public Issue {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
            if (code.isBlank() || message.isBlank() || rowNumber < 1) {
                throw new IllegalArgumentException("invalid OBK filter issue");
            }
        }
    }

    public record Report(long scannedRows,
                         long writtenRows,
                         long filteredRows,
                         long conflictRows,
                         long rejectedRows,
                         List<Issue> issues,
                         long omittedIssueCount) {
        public Report {
            issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
            if (scannedRows < 0 || writtenRows < 0 || filteredRows < 0
                    || conflictRows < 0 || rejectedRows < 0 || omittedIssueCount < 0
                    || writtenRows + filteredRows + conflictRows + rejectedRows
                    != scannedRows) {
                throw new IllegalArgumentException("invalid OBK filter report");
            }
        }
    }

    public static final class FilterException extends IOException {
        private final String code;

        FilterException(String code, String message) {
            super(code + ": " + message);
            this.code = Objects.requireNonNull(code, "code");
        }

        FilterException(String code, String message, Throwable cause) {
            super(code + ": " + message, cause);
            this.code = Objects.requireNonNull(code, "code");
        }

        public String code() {
            return code;
        }
    }
}
