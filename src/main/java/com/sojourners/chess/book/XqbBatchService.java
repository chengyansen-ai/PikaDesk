package com.sojourners.chess.book;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Builds a canonical XQB v1 database from one or more untrusted XQB inputs.
 * Inputs are opened read-only, exact duplicate records are removed, malformed
 * records are reported with bounded samples, and the destination is replaced
 * only after the complete output has been closed successfully.
 */
public final class XqbBatchService {

    private static final byte[] SQLITE_HEADER = "SQLite format 3\0"
            .getBytes(StandardCharsets.US_ASCII);
    private static final Map<String, String> BOOK_COLUMNS = Map.ofEntries(
            Map.entry("id", "INTEGER"),
            Map.entry("key", "BLOB"),
            Map.entry("move", "INTEGER"),
            Map.entry("score", "INTEGER"),
            Map.entry("win", "INTEGER"),
            Map.entry("draw", "INTEGER"),
            Map.entry("lost", "INTEGER"),
            Map.entry("valid", "INTEGER"),
            Map.entry("memo", "TEXT"));
    private static final Map<String, String> INFORMATION_COLUMNS = Map.of(
            "name", "TEXT", "value", "TEXT");
    private static final String READ_ROWS_SQL = """
            SELECT key,move,score,win,draw,lost,valid,memo
            FROM book
            """;
    private static final String STAGE_INSERT_SQL = """
            INSERT OR IGNORE INTO stage
            (key,move,score,win,draw,lost,valid,memo_is_null,memo)
            VALUES(?,?,?,?,?,?,?,?,?)
            """;

    private final Limits limits;

    public XqbBatchService() {
        this(Limits.defaults());
    }

    public XqbBatchService(Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public BatchReport build(List<Path> sourceFiles,
                             Path destination,
                             BooleanSupplier cancellationRequested,
                             Consumer<Progress> progressListener) throws IOException {
        Objects.requireNonNull(cancellationRequested, "cancellationRequested");
        Objects.requireNonNull(progressListener, "progressListener");
        List<Path> sources = normalizeSources(sourceFiles);
        Path target = normalize(destination, "destination");
        validateTarget(target, sources);
        checkCancelled(cancellationRequested);

        MutableReport report = new MutableReport(sources.size(), limits.maxIssueSamples());
        Path parent = target.getParent();
        Path staging = null;
        Path output = null;
        try {
            staging = Files.createTempFile(parent, ".pikadesk-book-stage-", ".tmp");
            output = Files.createTempFile(parent, ".pikadesk-book-output-", ".tmp");
            importSources(sources, staging, report, cancellationRequested, progressListener);
            writeCanonicalOutput(staging, output, report,
                    cancellationRequested, progressListener);
            checkCancelled(cancellationRequested);
            replaceAtomically(output, target);
            output = null;
            Progress completed = report.progress(Phase.COMPLETED, sources.size(), target);
            progressListener.accept(completed);
            return report.snapshot();
        } catch (BookBatchException failure) {
            throw failure;
        } catch (SQLException failure) {
            throw new BookBatchException("SQLITE_FAILURE",
                    "SQLite could not safely process the XQB batch", failure);
        } finally {
            deleteDatabaseFiles(output);
            deleteDatabaseFiles(staging);
        }
    }

    private void importSources(List<Path> sources,
                               Path staging,
                               MutableReport report,
                               BooleanSupplier cancellationRequested,
                               Consumer<Progress> progressListener)
            throws SQLException, IOException {
        try (Connection stageConnection = openWritable(staging)) {
            configureConnectionLimits(stageConnection);
            createStageSchema(stageConnection);
            stageConnection.setAutoCommit(false);
            try (PreparedStatement stageInsert = stageConnection.prepareStatement(STAGE_INSERT_SQL)) {
                for (int sourceIndex = 0; sourceIndex < sources.size(); sourceIndex++) {
                    Path source = sources.get(sourceIndex);
                    checkCancelled(cancellationRequested);
                    validateSourceFile(source);
                    progressListener.accept(report.progress(Phase.VALIDATING,
                            sourceIndex, source));
                    importSource(source, sourceIndex, stageConnection, stageInsert, report,
                            cancellationRequested, progressListener);
                    stageConnection.commit();
                }
            } catch (IOException | SQLException | RuntimeException failure) {
                rollbackQuietly(stageConnection);
                throw failure;
            }
        }
    }

    private void importSource(Path source,
                              int sourceIndex,
                              Connection stageConnection,
                              PreparedStatement stageInsert,
                              MutableReport report,
                              BooleanSupplier cancellationRequested,
                              Consumer<Progress> progressListener)
            throws SQLException, IOException {
        try (Connection input = openReadOnly(source)) {
            validateSchema(input, source);
            try (Statement query = input.createStatement()) {
                query.setQueryTimeout(limits.queryTimeoutSeconds());
                query.setFetchSize(Math.min(limits.progressEveryRows(), 4096));
                try (ResultSet rows = query.executeQuery(READ_ROWS_SQL)) {
                    long sourceRow = 0;
                    while (rows.next()) {
                        sourceRow++;
                        report.scannedRows++;
                        if (report.scannedRows > limits.maxTotalRows()) {
                            throw new BookBatchException("ROW_LIMIT_EXCEEDED",
                                    "XQB batch exceeds the configured total row limit");
                        }
                        Row record;
                        try {
                            record = readAndValidateRow(rows);
                        } catch (InvalidRow invalid) {
                            report.reject(new Issue(invalid.code, invalid.getMessage(),
                                    source, sourceRow));
                            publishReadingProgress(sourceIndex, source, report, progressListener);
                            checkCancelled(cancellationRequested);
                            continue;
                        }
                        bind(stageInsert, record);
                        if (stageInsert.executeUpdate() == 0) {
                            report.duplicateRows++;
                        } else {
                            report.acceptedRows++;
                        }
                        if (report.scannedRows % 4096 == 0) stageConnection.commit();
                        publishReadingProgress(sourceIndex, source, report, progressListener);
                        checkCancelled(cancellationRequested);
                    }
                }
            }
        }
        progressListener.accept(report.progress(Phase.READING, sourceIndex + 1, source));
    }

    private void publishReadingProgress(int sourceIndex,
                                        Path source,
                                        MutableReport report,
                                        Consumer<Progress> progressListener) {
        if (report.scannedRows % limits.progressEveryRows() == 0) {
            progressListener.accept(report.progress(Phase.READING, sourceIndex, source));
        }
    }

    private void writeCanonicalOutput(Path staging,
                                      Path output,
                                      MutableReport report,
                                      BooleanSupplier cancellationRequested,
                                      Consumer<Progress> progressListener)
            throws SQLException, IOException {
        try (Connection stage = openReadOnly(staging);
             Connection destination = openWritable(output)) {
            configureConnectionLimits(destination);
            createOutputSchema(destination);
            destination.setAutoCommit(false);
            String readStage = """
                    SELECT key,move,score,win,draw,lost,valid,memo_is_null,memo
                    FROM stage
                    ORDER BY key,move,score,win,draw,lost,valid,memo_is_null,memo
                    """;
            String insertOutput = """
                    INSERT INTO book(key,move,score,win,draw,lost,valid,memo)
                    VALUES(?,?,?,?,?,?,?,?)
                    """;
            try (Statement query = stage.createStatement();
                 ResultSet rows = query.executeQuery(readStage);
                 PreparedStatement insert = destination.prepareStatement(insertOutput)) {
                while (rows.next()) {
                    checkCancelled(cancellationRequested);
                    insert.setBytes(1, rows.getBytes(1));
                    for (int column = 2; column <= 7; column++) {
                        insert.setInt(column, rows.getInt(column));
                    }
                    if (rows.getInt(8) == 1) insert.setString(8, null);
                    else insert.setString(8, rows.getString(9));
                    insert.executeUpdate();
                    report.writtenRows++;
                    if (report.writtenRows % limits.progressEveryRows() == 0) {
                        progressListener.accept(report.progress(Phase.WRITING,
                                report.sourceCount, output));
                    }
                }
                destination.commit();
            } catch (IOException | SQLException | RuntimeException failure) {
                rollbackQuietly(destination);
                throw failure;
            }
        }
        progressListener.accept(report.progress(Phase.WRITING, report.sourceCount, output));
    }

    private Row readAndValidateRow(ResultSet rows) throws SQLException, InvalidRow {
        byte[] key = rows.getBytes(1);
        if (key == null) throw invalid("INVALID_KEY", "position key is null");
        validatePositionKey(key);
        int move = requiredInt(rows, 2, "move");
        validateMove(move);
        int score = requiredInt(rows, 3, "score");
        int win = nonNegativeInt(rows, 4, "win");
        int draw = nonNegativeInt(rows, 5, "draw");
        int lost = nonNegativeInt(rows, 6, "lost");
        int valid = requiredInt(rows, 7, "valid");
        if (valid != 0 && valid != 1) {
            throw invalid("INVALID_VALID_FLAG", "valid must be 0 or 1");
        }
        byte[] memoBytes = rows.getBytes(8);
        boolean memoNull = memoBytes == null;
        String memo = memoNull ? "" : decodeMemo(memoBytes);
        return new Row(key, move, score, win, draw, lost, valid, memoNull, memo);
    }

    private void validatePositionKey(byte[] key) throws InvalidRow {
        if (key.length == 0 || key.length > limits.maxKeyBytes()) {
            throw invalid("INVALID_KEY", "position key length is outside the configured limit");
        }
        int bit = 0;
        int pieces = 0;
        for (int cell = 0; cell < 90; cell++) {
            if (bit >= key.length * 8) {
                throw invalid("INVALID_KEY", "position key ends before 90 board cells");
            }
            if (bitAt(key, bit++) == 1) {
                if (bit + 4 > key.length * 8) {
                    throw invalid("INVALID_KEY", "position key ends inside a piece code");
                }
                int code = 0;
                for (int codeBit = 0; codeBit < 4; codeBit++) {
                    code = (code << 1) | bitAt(key, bit++);
                }
                if (code == 8) {
                    throw invalid("INVALID_KEY", "position key contains the reserved piece code");
                }
                if (++pieces > 32) {
                    throw invalid("INVALID_KEY", "position key contains more than 32 pieces");
                }
            }
        }
        int usedBytes = (bit + 7) / 8;
        if (usedBytes != key.length) {
            throw invalid("INVALID_KEY", "position key has trailing bytes");
        }
        for (int paddingBit = bit; paddingBit < key.length * 8; paddingBit++) {
            if (bitAt(key, paddingBit) != 0) {
                throw invalid("INVALID_KEY", "position key has non-zero padding bits");
            }
        }
    }

    private static int bitAt(byte[] bytes, int bit) {
        return (bytes[bit / 8] >>> (7 - bit % 8)) & 1;
    }

    private static void validateMove(int move) throws InvalidRow {
        int fromRow = move >>> 12;
        int fromColumn = (move >>> 8) & 0xf;
        int toRow = (move >>> 4) & 0xf;
        int toColumn = move & 0xf;
        if (move < 0 || move > 0xffff || fromRow > 9 || toRow > 9
                || fromColumn > 8 || toColumn > 8
                || fromRow == toRow && fromColumn == toColumn) {
            throw invalid("INVALID_MOVE", "move does not contain two distinct board coordinates");
        }
    }

    private static int requiredInt(ResultSet rows, int column, String name)
            throws SQLException, InvalidRow {
        long value = rows.getLong(column);
        if (rows.wasNull() || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw invalid("INVALID_NUMBER", name + " is null or outside the 32-bit range");
        }
        return (int) value;
    }

    private static int nonNegativeInt(ResultSet rows, int column, String name)
            throws SQLException, InvalidRow {
        int value = requiredInt(rows, column, name);
        if (value < 0) {
            throw invalid("INVALID_STATISTIC", name + " must not be negative");
        }
        return value;
    }

    private String decodeMemo(byte[] bytes) throws InvalidRow {
        if (bytes.length > limits.maxMemoBytes()) {
            throw invalid("MEMO_TOO_LARGE", "memo exceeds the configured UTF-8 byte limit");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalidUtf8) {
            throw invalid("INVALID_MEMO_ENCODING", "memo is not strict UTF-8");
        }
    }

    private static void bind(PreparedStatement insert, Row row) throws SQLException {
        insert.setBytes(1, row.key);
        insert.setInt(2, row.move);
        insert.setInt(3, row.score);
        insert.setInt(4, row.win);
        insert.setInt(5, row.draw);
        insert.setInt(6, row.lost);
        insert.setInt(7, row.valid);
        insert.setInt(8, row.memoNull ? 1 : 0);
        insert.setString(9, row.memo);
    }

    private Connection openReadOnly(Path path) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setSharedCache(false);
        config.enableLoadExtension(false);
        config.setBusyTimeout(1_000);
        config.setCacheSize(-2_048);
        String uri = path.toUri().toASCIIString() + "?mode=ro&cache=private";
        Connection connection = config.createConnection("jdbc:sqlite:" + uri);
        configureConnectionLimits(connection);
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
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=DELETE");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA trusted_schema=OFF");
        }
        return connection;
    }

    private static void configureConnectionLimits(Connection connection) throws SQLException {
        if (!(connection instanceof SQLiteConnection sqlite)) return;
        sqlite.setLimit(SQLiteLimits.SQLITE_LIMIT_LENGTH, 65_536);
        sqlite.setLimit(SQLiteLimits.SQLITE_LIMIT_SQL_LENGTH, 32_768);
        sqlite.setLimit(SQLiteLimits.SQLITE_LIMIT_COLUMN, 64);
        sqlite.setLimit(SQLiteLimits.SQLITE_LIMIT_EXPR_DEPTH, 32);
        sqlite.setLimit(SQLiteLimits.SQLITE_LIMIT_COMPOUND_SELECT, 8);
        sqlite.setLimit(SQLiteLimits.SQLITE_LIMIT_VDBE_OP, 100_000);
        sqlite.setLimit(SQLiteLimits.SQLITE_LIMIT_FUNCTION_ARG, 16);
        sqlite.setLimit(SQLiteLimits.SQLITE_LIMIT_ATTACHED, 0);
        sqlite.setLimit(SQLiteLimits.SQLITE_LIMIT_LIKE_PATTERN_LENGTH, 1_024);
        sqlite.setLimit(SQLiteLimits.SQLITE_LIMIT_VARIABLE_NUMBER, 32);
        sqlite.setLimit(SQLiteLimits.SQLITE_LIMIT_TRIGGER_DEPTH, 0);
        sqlite.setLimit(SQLiteLimits.SQLITE_LIMIT_WORKER_THREADS, 0);
    }

    private static void createStageSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE stage(
                        key BLOB NOT NULL,
                        move INTEGER NOT NULL,
                        score INTEGER NOT NULL,
                        win INTEGER NOT NULL,
                        draw INTEGER NOT NULL,
                        lost INTEGER NOT NULL,
                        valid INTEGER NOT NULL,
                        memo_is_null INTEGER NOT NULL,
                        memo TEXT NOT NULL,
                        PRIMARY KEY(key,move,score,win,draw,lost,valid,memo_is_null,memo)
                    ) WITHOUT ROWID
                    """);
        }
    }

    private static void createOutputSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE information(name TEXT, value TEXT)");
            statement.execute("INSERT INTO information VALUES ('version','1')");
            statement.execute("INSERT INTO information VALUES ('type','xiangqi')");
            statement.execute("""
                    CREATE TABLE book(
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        key BLOB,
                        move INTEGER,
                        score INTEGER,
                        win INTEGER,
                        draw INTEGER,
                        lost INTEGER,
                        valid INTEGER,
                        memo TEXT
                    )
                    """);
            statement.execute("CREATE INDEX idxkey ON book(key)");
        }
    }

    private void validateSchema(Connection connection, Path source)
            throws SQLException, BookBatchException {
        requireTable(connection, "book", BOOK_COLUMNS, source);
        requireTable(connection, "information", INFORMATION_COLUMNS, source);
        Map<String, List<String>> information = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(limits.queryTimeoutSeconds());
            try (ResultSet rows = statement.executeQuery(
                    "SELECT name,value FROM information LIMIT 17")) {
                int count = 0;
                while (rows.next()) {
                    if (++count > 16) {
                        throw unsupportedSchema(source,
                                "information contains too many records");
                    }
                    String name = rows.getString(1);
                    String value = rows.getString(2);
                    if (name != null && value != null) {
                        information.computeIfAbsent(name.trim().toLowerCase(Locale.ROOT),
                                ignored -> new ArrayList<>()).add(value.trim());
                    }
                }
            }
        }
        if (!List.of("1").equals(information.get("version"))
                || information.get("type") == null
                || information.get("type").size() != 1
                || !"xiangqi".equalsIgnoreCase(information.get("type").getFirst())) {
            throw new BookBatchException("UNSUPPORTED_XQB_VERSION",
                    "XQB information must declare exactly version=1 and type=xiangqi: "
                            + source.getFileName());
        }
    }

    private static void requireTable(Connection connection,
                                     String table,
                                     Map<String, String> requiredColumns,
                                     Path source) throws SQLException, BookBatchException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT type FROM sqlite_master WHERE lower(name)=lower(?)")) {
            query.setString(1, table);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next() || !"table".equalsIgnoreCase(result.getString(1))
                        || result.next()) {
                    throw unsupportedSchema(source, "missing or ambiguous table " + table);
                }
            }
        }
        Map<String, ColumnInfo> actualColumns = new LinkedHashMap<>();
        try (Statement query = connection.createStatement();
             ResultSet result = query.executeQuery("PRAGMA table_info('" + table + "')")) {
            while (result.next()) {
                String name = result.getString("name");
                String type = result.getString("type");
                if (name != null) {
                    actualColumns.put(name.toLowerCase(Locale.ROOT),
                            new ColumnInfo(affinity(type), result.getInt("pk") > 0));
                }
            }
        }
        for (Map.Entry<String, String> required : requiredColumns.entrySet()) {
            ColumnInfo actual = actualColumns.get(required.getKey());
            if (actual == null || !required.getValue().equals(actual.affinity)) {
                throw unsupportedSchema(source, table + " has a missing or incompatible "
                        + required.getKey() + " column");
            }
        }
        if ("book".equals(table) && !actualColumns.get("id").primaryKey) {
            throw unsupportedSchema(source, "book.id is not a primary key");
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

    private static BookBatchException unsupportedSchema(Path source, String detail) {
        return new BookBatchException("UNSUPPORTED_SCHEMA",
                "unsupported XQB schema in " + source.getFileName() + ": " + detail);
    }

    private void validateSourceFile(Path source) throws IOException {
        if (!Files.isRegularFile(source)) {
            throw new BookBatchException("INVALID_SOURCE", "XQB source is not a regular file");
        }
        if (!hasXqbExtension(source)) {
            throw new BookBatchException("UNSUPPORTED_EXTENSION",
                    "XQB batch inputs must use the .xqb extension");
        }
        long size = Files.size(source);
        if (size > limits.maxSourceBytes()) {
            throw new BookBatchException("SOURCE_TOO_LARGE",
                    "XQB source exceeds the configured file-size limit");
        }
        try (InputStream input = Files.newInputStream(source)) {
            byte[] header = input.readNBytes(SQLITE_HEADER.length);
            if (!Arrays.equals(SQLITE_HEADER, header)) {
                throw new BookBatchException("INVALID_SQLITE_HEADER",
                        "XQB source does not contain the SQLite 3 header");
            }
        }
    }

    private List<Path> normalizeSources(List<Path> sourceFiles) throws BookBatchException {
        Objects.requireNonNull(sourceFiles, "sourceFiles");
        if (sourceFiles.isEmpty()) {
            throw new BookBatchException("NO_SOURCES", "at least one XQB source is required");
        }
        if (sourceFiles.size() > limits.maxInputFiles()) {
            throw new BookBatchException("TOO_MANY_SOURCES",
                    "XQB batch exceeds the configured input-file limit");
        }
        LinkedHashSet<Path> normalized = new LinkedHashSet<>();
        for (Path source : sourceFiles) normalized.add(normalize(source, "source"));
        return List.copyOf(normalized);
    }

    private static Path normalize(Path path, String label) {
        return Objects.requireNonNull(path, label).toAbsolutePath().normalize();
    }

    private static void validateTarget(Path target, List<Path> sources)
            throws IOException {
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new BookBatchException("INVALID_DESTINATION",
                    "XQB destination directory does not exist");
        }
        if (!hasXqbExtension(target)) {
            throw new BookBatchException("UNSUPPORTED_EXTENSION",
                    "XQB batch destination must use the .xqb extension");
        }
        if (Files.isDirectory(target)) {
            throw new BookBatchException("INVALID_DESTINATION",
                    "XQB destination must not be a directory");
        }
        for (Path source : sources) {
            if (source.equals(target)
                    || Files.exists(target) && Files.exists(source)
                    && Files.isSameFile(source, target)) {
                throw new BookBatchException("SOURCE_DESTINATION_OVERLAP",
                        "XQB destination must not also be an input source");
            }
        }
    }

    private static boolean hasXqbExtension(Path path) {
        Path fileName = path.getFileName();
        return fileName != null
                && fileName.toString().toLowerCase(Locale.ROOT).endsWith(".xqb");
    }

    private static void replaceAtomically(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteDatabaseFiles(Path path) {
        if (path == null) return;
        deleteQuietly(path);
        deleteQuietly(Path.of(path + "-journal"));
        deleteQuietly(Path.of(path + "-wal"));
        deleteQuietly(Path.of(path + "-shm"));
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Preserve the primary failure; same-directory randomized temp names are inert.
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Preserve the primary failure.
        }
    }

    private static void checkCancelled(BooleanSupplier cancellationRequested)
            throws BookBatchException {
        if (cancellationRequested.getAsBoolean()) {
            throw new BookBatchException("CANCELLED", "XQB batch was cancelled");
        }
    }

    private static InvalidRow invalid(String code, String message) {
        return new InvalidRow(code, message);
    }

    public record Limits(int maxInputFiles,
                         long maxSourceBytes,
                         long maxTotalRows,
                         int maxKeyBytes,
                         int maxMemoBytes,
                         int maxIssueSamples,
                         int progressEveryRows,
                         int queryTimeoutSeconds) {
        public Limits {
            if (maxInputFiles < 1 || maxSourceBytes < SQLITE_HEADER.length
                    || maxTotalRows < 1 || maxKeyBytes < 12 || maxKeyBytes > 128
                    || maxMemoBytes < 1 || maxMemoBytes > 65_536
                    || maxIssueSamples < 0 || progressEveryRows < 1
                    || queryTimeoutSeconds < 1) {
                throw new IllegalArgumentException("invalid XQB batch limits");
            }
        }

        public static Limits defaults() {
            return new Limits(256, 8L * 1024 * 1024 * 1024,
                    5_000_000, 128, 4_096, 200, 1_000, 30);
        }
    }

    public enum Phase {
        VALIDATING,
        READING,
        CHECKPOINTING,
        WRITING,
        COMPLETED
    }

    public record Progress(Phase phase,
                           int completedSources,
                           int sourceCount,
                           long scannedRows,
                           long acceptedRows,
                           long duplicateRows,
                           long rejectedRows,
                           long writtenRows,
                           Path currentFile) {
        public Progress {
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(currentFile, "currentFile");
            if (completedSources < 0 || completedSources > sourceCount
                    || sourceCount < 1 || scannedRows < 0 || acceptedRows < 0
                    || duplicateRows < 0 || rejectedRows < 0 || writtenRows < 0) {
                throw new IllegalArgumentException("invalid XQB batch progress");
            }
        }
    }

    public record Issue(String code, String message, Path source, long rowNumber) {
        public Issue {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(source, "source");
            if (code.isBlank() || message.isBlank() || rowNumber < 1) {
                throw new IllegalArgumentException("invalid XQB issue");
            }
        }
    }

    public record BatchReport(int sourceCount,
                              long scannedRows,
                              long writtenRows,
                              long duplicateRows,
                              long rejectedRows,
                              List<Issue> issues,
                              long omittedIssueCount) {
        public BatchReport {
            issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
            if (sourceCount < 1 || scannedRows < 0 || writtenRows < 0
                    || duplicateRows < 0 || rejectedRows < 0 || omittedIssueCount < 0
                    || writtenRows + duplicateRows + rejectedRows != scannedRows) {
                throw new IllegalArgumentException("invalid XQB batch report");
            }
        }
    }

    public static final class BookBatchException extends IOException {
        private final String code;

        BookBatchException(String code, String message) {
            super(code + ": " + message);
            this.code = Objects.requireNonNull(code, "code");
        }

        BookBatchException(String code, String message, Throwable cause) {
            super(code + ": " + message, cause);
            this.code = Objects.requireNonNull(code, "code");
        }

        public String code() {
            return code;
        }
    }

    private static final class MutableReport {
        private final int sourceCount;
        private final int maxIssueSamples;
        private final List<Issue> issues = new ArrayList<>();
        private long scannedRows;
        private long acceptedRows;
        private long duplicateRows;
        private long rejectedRows;
        private long writtenRows;
        private long omittedIssueCount;

        private MutableReport(int sourceCount, int maxIssueSamples) {
            this.sourceCount = sourceCount;
            this.maxIssueSamples = maxIssueSamples;
        }

        private void reject(Issue issue) {
            rejectedRows++;
            if (issues.size() < maxIssueSamples) issues.add(issue);
            else omittedIssueCount++;
        }

        private Progress progress(Phase phase, int completedSources, Path currentFile) {
            return new Progress(phase, completedSources, sourceCount, scannedRows,
                    acceptedRows, duplicateRows, rejectedRows, writtenRows, currentFile);
        }

        private BatchReport snapshot() {
            return new BatchReport(sourceCount, scannedRows, writtenRows, duplicateRows,
                    rejectedRows, issues, omittedIssueCount);
        }
    }

    private record Row(byte[] key, int move, int score, int win, int draw,
                       int lost, int valid, boolean memoNull, String memo) { }

    private record ColumnInfo(String affinity, boolean primaryKey) { }

    private static final class InvalidRow extends Exception {
        private final String code;

        private InvalidRow(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
