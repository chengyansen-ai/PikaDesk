package com.sojourners.chess.linker.profile;

import com.sojourners.chess.automation.BoardCoordinateMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Versioned, bounded, non-executable connection profile. */
public record ConnectionProfile(int schemaVersion,
                                String name,
                                TargetDescriptor target,
                                BoardCoordinateMapper.BoardBounds boardBounds,
                                int dpi,
                                BoardCoordinateMapper.Orientation orientation,
                                String themeId,
                                String modelId,
                                int scanIntervalMillis,
                                int recognitionThreads,
                                int clickDelayMillis,
                                int moveDelayMillis,
                                boolean dryRunVerified) {

    public static final int CURRENT_VERSION = 2;
    private static final int MAX_PROFILE_BYTES = 65_536;
    private static final Set<String> V1_FIELDS = Set.of(
            "name", "executable", "windowClass", "title",
            "clientWidth", "clientHeight", "boardX", "boardY",
            "boardWidth", "boardHeight", "dpi", "orientation", "model",
            "scanMillis", "threads", "clickMillis", "moveMillis");
    private static final Set<String> V2_FIELDS = Set.of(
            "name", "executable", "windowClass", "title", "path",
            "clientWidth", "clientHeight", "boardX", "boardY",
            "boardWidth", "boardHeight", "dpi", "orientation", "theme", "model",
            "scanMillis", "threads", "clickMillis", "moveMillis", "dryRun");

    public ConnectionProfile {
        if (schemaVersion != CURRENT_VERSION) {
            throw new IllegalArgumentException("connection profile version must be current");
        }
        name = bounded(name, "name", 80);
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(boardBounds, "boardBounds");
        Objects.requireNonNull(orientation, "orientation");
        themeId = identifier(themeId, "themeId");
        modelId = identifier(modelId, "modelId");
        if (dpi < 48 || dpi > 960) {
            throw new IllegalArgumentException("DPI must be between 48 and 960");
        }
        BoardCoordinateMapper.ClientArea localClient =
                new BoardCoordinateMapper.ClientArea(
                        0, 0, target.clientWidth(), target.clientHeight());
        new BoardCoordinateMapper.Calibration(
                "profile", 1, localClient, boardBounds, dpi, orientation);
        range(scanIntervalMillis, 20, 10_000, "scanIntervalMillis");
        range(recognitionThreads, 1, 64, "recognitionThreads");
        range(clickDelayMillis, 0, 2_000, "clickDelayMillis");
        range(moveDelayMillis, 0, 2_000, "moveDelayMillis");
    }

    public static void write(ConnectionProfile profile,
                             OutputStream output,
                             ExportMode mode) throws IOException {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(mode, "mode");
        boolean local = mode == ExportMode.LOCAL_FULL;
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("name", encode(profile.name()));
        fields.put("executable", encode(profile.target().executableName()));
        fields.put("windowClass", encode(profile.target().windowClassName()));
        fields.put("title", encode(profile.target().titleHint()));
        fields.put("path", encode(local ? profile.target().localExecutablePath() : ""));
        fields.put("clientWidth", Integer.toString(profile.target().clientWidth()));
        fields.put("clientHeight", Integer.toString(profile.target().clientHeight()));
        fields.put("boardX", Integer.toString(profile.boardBounds().x()));
        fields.put("boardY", Integer.toString(profile.boardBounds().y()));
        fields.put("boardWidth", Integer.toString(profile.boardBounds().width()));
        fields.put("boardHeight", Integer.toString(profile.boardBounds().height()));
        fields.put("dpi", Integer.toString(profile.dpi()));
        fields.put("orientation", profile.orientation().name());
        fields.put("theme", encode(profile.themeId()));
        fields.put("model", encode(profile.modelId()));
        fields.put("scanMillis", Integer.toString(profile.scanIntervalMillis()));
        fields.put("threads", Integer.toString(profile.recognitionThreads()));
        fields.put("clickMillis", Integer.toString(profile.clickDelayMillis()));
        fields.put("moveMillis", Integer.toString(profile.moveDelayMillis()));
        fields.put("dryRun", Boolean.toString(local && profile.dryRunVerified()));

        StringBuilder text = new StringBuilder("PDCP ")
                .append(CURRENT_VERSION).append('\n');
        fields.forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_PROFILE_BYTES) {
            throw new IOException("connection profile exceeds size limit");
        }
        output.write(bytes);
        output.flush();
    }

    public static ConnectionProfile read(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        byte[] bytes = input.readNBytes(MAX_PROFILE_BYTES + 1);
        if (bytes.length > MAX_PROFILE_BYTES) {
            throw new IOException("connection profile exceeds size limit");
        }
        List<String> lines = new String(bytes, StandardCharsets.UTF_8).lines().toList();
        if (lines.isEmpty() || !lines.getFirst().matches("PDCP [12]")) {
            throw new IOException("not a supported PikaDesk connection profile");
        }
        int sourceVersion = lines.getFirst().charAt(5) - '0';
        Set<String> allowed = sourceVersion == 1 ? V1_FIELDS : V2_FIELDS;
        Map<String, String> fields = new LinkedHashMap<>();
        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isEmpty()) continue;
            int separator = line.indexOf('=');
            if (separator < 1) throw new IOException("malformed profile field");
            String key = line.substring(0, separator);
            if (!allowed.contains(key)) throw new IOException("unknown profile field: " + key);
            if (fields.put(key, line.substring(separator + 1)) != null) {
                throw new IOException("duplicate profile field: " + key);
            }
        }
        if (!fields.keySet().equals(allowed)) {
            throw new IOException("connection profile has missing fields");
        }
        try {
            TargetDescriptor target = new TargetDescriptor(
                    decode(fields, "executable"), decode(fields, "windowClass"),
                    decode(fields, "title"),
                    sourceVersion == 1 ? "" : decode(fields, "path"),
                    number(fields, "clientWidth"), number(fields, "clientHeight"));
            return new ConnectionProfile(
                    CURRENT_VERSION,
                    decode(fields, "name"),
                    target,
                    new BoardCoordinateMapper.BoardBounds(
                            number(fields, "boardX"), number(fields, "boardY"),
                            number(fields, "boardWidth"), number(fields, "boardHeight")),
                    number(fields, "dpi"),
                    BoardCoordinateMapper.Orientation.valueOf(fields.get("orientation")),
                    sourceVersion == 1 ? "AUTO" : decode(fields, "theme"),
                    decode(fields, "model"),
                    number(fields, "scanMillis"), number(fields, "threads"),
                    number(fields, "clickMillis"), number(fields, "moveMillis"),
                    sourceVersion != 1 && bool(fields, "dryRun"));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid connection profile: " + message(invalid), invalid);
        }
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(Map<String, String> fields, String key) {
        try {
            return new String(Base64.getUrlDecoder().decode(fields.get(key)),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("invalid encoded field: " + key, invalid);
        }
    }

    private static int number(Map<String, String> fields, String key) {
        try {
            return Integer.parseInt(fields.get(key));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("invalid number field: " + key, invalid);
        }
    }

    private static boolean bool(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IllegalArgumentException("invalid boolean field: " + key);
        }
        return Boolean.parseBoolean(value);
    }

    private static String identifier(String value, String field) {
        String safe = bounded(value, field, 128);
        if (!safe.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(field + " contains unsupported characters");
        }
        return safe;
    }

    private static String bounded(String value, String field, int maxLength) {
        String safe = Objects.requireNonNull(value, field).trim();
        if (safe.isEmpty() || safe.length() > maxLength) {
            throw new IllegalArgumentException(field + " must contain 1 to " + maxLength + " characters");
        }
        return safe;
    }

    private static void range(int value, int min, int max, String field) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(field + " must be between " + min + " and " + max);
        }
    }

    private static String message(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message.trim();
    }

    public enum ExportMode {
        REDACTED,
        LOCAL_FULL
    }

    public record TargetDescriptor(String executableName,
                                   String windowClassName,
                                   String titleHint,
                                   String localExecutablePath,
                                   int clientWidth,
                                   int clientHeight) {
        public TargetDescriptor {
            executableName = bounded(executableName, "executableName", 260);
            windowClassName = bounded(windowClassName, "windowClassName", 256);
            titleHint = bounded(titleHint, "titleHint", 256);
            localExecutablePath = Objects.requireNonNull(
                    localExecutablePath, "localExecutablePath").trim();
            if (localExecutablePath.length() > 4_096) {
                throw new IllegalArgumentException("localExecutablePath exceeds limit");
            }
            range(clientWidth, 64, 32_768, "clientWidth");
            range(clientHeight, 64, 32_768, "clientHeight");
        }
    }
}
