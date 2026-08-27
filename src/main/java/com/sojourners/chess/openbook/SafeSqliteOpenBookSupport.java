package com.sojourners.chess.openbook;

import com.sojourners.chess.model.BookData;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteLimits;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
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
import java.util.Optional;
import java.util.Set;

final class SafeSqliteOpenBookSupport implements AutoCloseable {

    static final int MAX_RESULTS = 256;
    static final int MAX_MEMO_BYTES = 4_096;
    private static final long MAX_FILE_BYTES = 8L * 1024 * 1024 * 1024;
    private static final int QUERY_TIMEOUT_SECONDS = 3;
    private static final byte[] SQLITE_HEADER = "SQLite format 3\0"
            .getBytes(StandardCharsets.US_ASCII);

    private final Connection connection;
    private final String source;
    private OpenBookDiagnostic diagnostic;

    private SafeSqliteOpenBookSupport(Connection connection, String source) {
        this.connection = connection;
        this.source = source;
    }

    static SafeSqliteOpenBookSupport openXqb(Path selected)
            throws ClassNotFoundException, SQLException {
        SafeSqliteOpenBookSupport support = open(selected, ".xqb");
        try {
            support.requireTable("book", Map.ofEntries(
                    Map.entry("id", Set.of("INTEGER")),
                    Map.entry("key", Set.of("BLOB")),
                    Map.entry("move", Set.of("INTEGER")),
                    Map.entry("score", Set.of("INTEGER")),
                    Map.entry("win", Set.of("INTEGER")),
                    Map.entry("draw", Set.of("INTEGER")),
                    Map.entry("lost", Set.of("INTEGER")),
                    Map.entry("valid", Set.of("INTEGER")),
                    Map.entry("memo", Set.of("TEXT"))));
            support.requireTable("information", Map.of(
                    "name", Set.of("TEXT"), "value", Set.of("TEXT")));
            support.requireXqbV1();
            return support;
        } catch (SQLException failure) {
            support.closeAfterLoadFailure(failure);
            throw failure;
        }
    }

    static SafeSqliteOpenBookSupport openLegacy(Path selected,
                                                String extension,
                                                String table)
            throws ClassNotFoundException, SQLException {
        SafeSqliteOpenBookSupport support = open(selected, extension);
        try {
            Set<String> numeric = Set.of("INTEGER", "REAL", "NUMERIC");
            support.requireTable(table, Map.of(
                    "vkey", numeric,
                    "vmove", numeric,
                    "vscore", numeric,
                    "vwin", numeric,
                    "vdraw", numeric,
                    "vlost", numeric,
                    "vvalid", numeric,
                    "vmemo", Set.of("TEXT")));
            return support;
        } catch (SQLException failure) {
            support.closeAfterLoadFailure(failure);
            throw failure;
        }
    }

    private static SafeSqliteOpenBookSupport open(Path selected, String extension)
            throws ClassNotFoundException, SQLException {
        Objects.requireNonNull(selected, "selected");
        String fileName = selected.getFileName() == null
                ? "未知文件" : selected.getFileName().toString();
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(extension.toLowerCase(Locale.ROOT))) {
            throw loadFailure("UNSUPPORTED_FORMAT", fileName,
                    "文件扩展名与所选开局库格式不匹配");
        }

        Path path;
        try {
            if (!Files.isRegularFile(selected)) {
                throw loadFailure("NOT_REGULAR_FILE", fileName, "不是普通文件");
            }
            if (Files.size(selected) > MAX_FILE_BYTES) {
                throw loadFailure("FILE_TOO_LARGE", fileName, "文件超过读取上限");
            }
            byte[] header = new byte[SQLITE_HEADER.length];
            try (InputStream input = Files.newInputStream(selected)) {
                if (input.readNBytes(header, 0, header.length) != header.length
                        || !Arrays.equals(header, SQLITE_HEADER)) {
                    throw loadFailure("UNSUPPORTED_CONTAINER", fileName,
                            "不是受支持的标准 SQLite 3 容器");
                }
            }
            path = selected.toRealPath();
        } catch (OpenBookLoadException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new OpenBookLoadException("FILE_UNREADABLE",
                    "无法读取开局库 " + fileName, failure);
        }

        Class.forName("org.sqlite.JDBC");
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setSharedCache(false);
        config.enableLoadExtension(false);
        config.setBusyTimeout(1_000);
        config.setCacheSize(-2_048);
        String uri = path.toUri().toASCIIString() + "?mode=ro&cache=private";
        Connection connection = null;
        try {
            connection = config.createConnection("jdbc:sqlite:" + uri);
            configureLimits(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA query_only=ON");
                statement.execute("PRAGMA trusted_schema=OFF");
            }
        } catch (SQLException failure) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw new OpenBookLoadException("OPEN_FAILED",
                    "无法以只读方式打开开局库 " + fileName, failure);
        }
        return new SafeSqliteOpenBookSupport(connection, fileName);
    }

    Optional<OpenBookDiagnostic> diagnostic() {
        return Optional.ofNullable(diagnostic);
    }

    List<BookData> query(List<QueryPlan> plans) {
        Objects.requireNonNull(plans, "plans");
        diagnostic = null;
        List<BookData> results = new ArrayList<>();
        int invalidRows = 0;
        boolean truncated = false;
        try {
            for (int planIndex = 0; planIndex < plans.size(); planIndex++) {
                QueryPlan plan = plans.get(planIndex);
                int remaining = MAX_RESULTS - results.size();
                if (remaining == 0) {
                    truncated = true;
                    break;
                }
                try (PreparedStatement query = connection.prepareStatement(
                        plan.sql() + " LIMIT ?")) {
                    int nextParameter = plan.binder().bind(query);
                    query.setInt(nextParameter, remaining + 1);
                    query.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                    query.setFetchSize(Math.min(remaining + 1, 64));
                    try (ResultSet rows = query.executeQuery()) {
                        int scanned = 0;
                        while (rows.next()) {
                            if (++scanned > remaining) {
                                truncated = true;
                                break;
                            }
                            try {
                                results.add(plan.mapper().map(rows));
                            } catch (InvalidBookRow ignored) {
                                invalidRows++;
                            }
                        }
                    }
                }
            }
        } catch (SQLException | RuntimeException failure) {
            diagnostic = OpenBookDiagnostic.queryFailure(source, "QUERY_FAILED",
                    "读取开局库失败：" + source);
            return new ArrayList<>();
        }

        if (truncated) {
            diagnostic = OpenBookDiagnostic.queryFailure(source, "RESULT_TRUNCATED",
                    "单个局面最多显示 " + MAX_RESULTS + " 条库招：" + source);
        } else if (invalidRows > 0) {
            diagnostic = OpenBookDiagnostic.queryFailure(source, "INVALID_ROW",
                    "已跳过 " + invalidRows + " 条无效库招：" + source);
        }
        return results;
    }

    String source() {
        return source;
    }

    void closeQuietly() {
        try {
            close();
        } catch (SQLException failure) {
            diagnostic = OpenBookDiagnostic.queryFailure(source, "CLOSE_FAILED",
                    "关闭开局库失败：" + source);
        }
    }

    static BookData readBookData(ResultSet rows,
                                 String scoreColumn,
                                 String winColumn,
                                 String drawColumn,
                                 String lostColumn,
                                 String memoColumn,
                                 String source) throws SQLException, InvalidBookRow {
        int score = integer(rows, scoreColumn, true);
        int win = integer(rows, winColumn, false);
        int draw = integer(rows, drawColumn, false);
        int lost = integer(rows, lostColumn, false);
        String memo = strictMemo(rows, memoColumn);

        BookData data = new BookData();
        data.setScore(score);
        data.setWinNum(win);
        data.setDrawNum(draw);
        data.setLoseNum(lost);
        long games = (long) win + draw + lost;
        double winRate = games == 0 ? 0d
                : Math.round(10_000d * (win + draw / 2d) / games) / 100d;
        data.setWinRate(winRate);
        data.setNote(memo);
        data.setSource(source);
        return data;
    }

    static int integer(ResultSet rows, String column, boolean signed)
            throws SQLException, InvalidBookRow {
        long value = rows.getLong(column);
        if (rows.wasNull() || value < (signed ? Integer.MIN_VALUE : 0)
                || value > Integer.MAX_VALUE) {
            throw new InvalidBookRow();
        }
        return (int) value;
    }

    static void requireC90Move(int move) throws InvalidBookRow {
        if ((move & ~0xffff) != 0) throw new InvalidBookRow();
        int from = (move >>> 8) & 0xff;
        int to = move & 0xff;
        if (!c90Square(from) || !c90Square(to) || from == to) {
            throw new InvalidBookRow();
        }
    }

    static void requireXqbMove(int move) throws InvalidBookRow {
        if ((move & ~0xffff) != 0) throw new InvalidBookRow();
        int from = (move >>> 8) & 0xff;
        int to = move & 0xff;
        if (!boardSquare(from) || !boardSquare(to) || from == to) {
            throw new InvalidBookRow();
        }
    }

    static void requireFlag(int value) throws InvalidBookRow {
        if (value != 0 && value != 1) throw new InvalidBookRow();
    }

    private static boolean c90Square(int square) {
        int row = (square >>> 4) & 0xf;
        int column = square & 0xf;
        return row >= 3 && row <= 12 && column >= 3 && column <= 11;
    }

    private static boolean boardSquare(int square) {
        int row = (square >>> 4) & 0xf;
        int column = square & 0xf;
        return row <= 9 && column <= 8;
    }

    private static String strictMemo(ResultSet rows, String column)
            throws SQLException, InvalidBookRow {
        byte[] bytes = rows.getBytes(column);
        if (bytes == null) return null;
        if (bytes.length > MAX_MEMO_BYTES) throw new InvalidBookRow();
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw new InvalidBookRow();
        }
    }

    private void requireXqbV1() throws SQLException {
        Map<String, List<String>> information = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet rows = statement.executeQuery(
                    "SELECT name,value FROM information LIMIT 17")) {
                int count = 0;
                while (rows.next()) {
                    if (++count > 16) {
                        throw loadFailure("UNSUPPORTED_SCHEMA", source,
                                "XQB information 记录过多");
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
            throw loadFailure("UNSUPPORTED_XQB_VERSION", source,
                    "只支持 version=1、type=xiangqi");
        }
    }

    private void requireTable(String table, Map<String, Set<String>> required)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT type FROM sqlite_master WHERE lower(name)=lower(?)")) {
            query.setString(1, table);
            query.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next() || !"table".equalsIgnoreCase(result.getString(1))
                        || result.next()) {
                    throw loadFailure("UNSUPPORTED_SCHEMA", source,
                            "缺少或存在歧义的数据表 " + table);
                }
            }
        }

        Map<String, String> actual = new LinkedHashMap<>();
        try (Statement query = connection.createStatement();
             ResultSet result = query.executeQuery("PRAGMA table_info('" + table + "')")) {
            while (result.next()) {
                String name = result.getString("name");
                if (name != null) {
                    actual.put(name.toLowerCase(Locale.ROOT), affinity(result.getString("type")));
                }
            }
        }
        for (Map.Entry<String, Set<String>> column : required.entrySet()) {
            if (!column.getValue().contains(actual.get(column.getKey()))) {
                throw loadFailure("UNSUPPORTED_SCHEMA", source,
                        table + " 缺少或不兼容字段 " + column.getKey());
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
        sqlite.setLimit(SQLiteLimits.SQLITE_LIMIT_LIKE_PATTERN_LENGTH, 1_024);
        sqlite.setLimit(SQLiteLimits.SQLITE_LIMIT_VARIABLE_NUMBER, 32);
        sqlite.setLimit(SQLiteLimits.SQLITE_LIMIT_TRIGGER_DEPTH, 0);
        sqlite.setLimit(SQLiteLimits.SQLITE_LIMIT_WORKER_THREADS, 0);
    }

    private static OpenBookLoadException loadFailure(String code,
                                                     String fileName,
                                                     String detail) {
        return new OpenBookLoadException(code,
                "无法加载开局库 " + fileName + "：" + detail);
    }

    private void closeAfterLoadFailure(SQLException primary) {
        try {
            close();
        } catch (SQLException closeFailure) {
            primary.addSuppressed(closeFailure);
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }

    @FunctionalInterface
    interface Binder {
        int bind(PreparedStatement query) throws SQLException;
    }

    @FunctionalInterface
    interface RowMapper {
        BookData map(ResultSet rows) throws SQLException, InvalidBookRow;
    }

    record QueryPlan(String sql, Binder binder, RowMapper mapper) {
        QueryPlan {
            Objects.requireNonNull(sql, "sql");
            Objects.requireNonNull(binder, "binder");
            Objects.requireNonNull(mapper, "mapper");
        }
    }

    static final class InvalidBookRow extends Exception { }
}
