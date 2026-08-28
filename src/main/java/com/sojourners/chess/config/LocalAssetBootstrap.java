package com.sojourners.chess.config;

import com.sojourners.chess.enginee.Engine;
import com.sojourners.chess.model.EngineConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Applies an explicitly packaged, local-only first-run profile.
 *
 * <p>The source distribution contains no profile and therefore keeps the
 * offline defaults. A locally assembled app image may place a bounded profile
 * and verified assets below {@code local-assets/}; paths are containment
 * checked before they become executable or database configuration.</p>
 */
final class LocalAssetBootstrap {

    private static final long MAX_PROFILE_BYTES = 64 * 1024;
    private static final long MAX_BOOK_BYTES = 2L * 1024 * 1024 * 1024;

    private LocalAssetBootstrap() {
    }

    static Result apply(Properties target, Path applicationDirectory) {
        Path assets = applicationDirectory.toAbsolutePath().normalize()
                .resolve("local-assets").normalize();
        Path profilePath = assets.resolve("profile.properties");
        if (!Files.isRegularFile(profilePath)) {
            return Result.unchanged();
        }

        try {
            if (Files.size(profilePath) > MAX_PROFILE_BYTES) {
                return Result.failure("profile exceeds 64 KiB");
            }
            java.util.Properties profile = new java.util.Properties();
            try (var input = Files.newBufferedReader(profilePath, StandardCharsets.UTF_8)) {
                profile.load(input);
            }

            EngineConfig engine = engine(profile, assets, "engine");
            EngineConfig candidate = flag(profile, "engine.candidate.enabled")
                    ? engine(profile, assets, "engine.candidate") : null;
            List<String> books = books(profile, assets);
            boolean usableEngineExists = target.getEngineConfigList().stream()
                    .anyMatch(LocalAssetBootstrap::isUsableEngine);
            boolean engineAdded = false;
            if (!usableEngineExists) {
                target.getEngineConfigList().clear();
                target.getEngineConfigList().add(engine);
                target.setEngineName(engine.getName());
                target.setThreadNum(integer(profile, "engine.threads", 1, 256));
                target.setHashSize(integer(profile, "engine.hashMiB", 16, 1_048_576));
                target.setAnalysisModel(Engine.AnalysisModel.FIXED_TIME);
                target.setAnalysisValue(integer(profile,
                        "engine.moveTimeMs", 100, 600_000));
                engineAdded = true;
            }
            boolean candidateAdded = false;
            if (candidate != null && target.getEngineConfigList().stream()
                    .noneMatch(existing -> sameExecutable(existing, candidate))) {
                target.getEngineConfigList().add(candidate);
                candidateAdded = true;
            }
            boolean bookAdded = false;
            for (String book : books) {
                if (!target.getOpenBookList().contains(book)) {
                    target.getOpenBookList().add(book);
                    bookAdded = true;
                }
            }
            if (!engineAdded && !candidateAdded && !bookAdded) {
                return Result.unchanged();
            }
            target.setBookSwitch(flag(profile, "book.enabled"));
            target.setUseCloudBook(flag(profile, "cloudBook.enabled"));
            target.setCloudBookTimeout(integer(profile,
                    "cloudBook.timeoutMs", 100, 30_000));
            target.setOffManualSteps(integer(profile,
                    "book.offManualSteps", 1, 9999));
            return new Result(true, List.of());
        } catch (IOException | IllegalArgumentException failure) {
            return Result.failure(failure.getMessage());
        }
    }

    private static EngineConfig engine(java.util.Properties profile,
                                       Path assets,
                                       String prefix)
            throws IOException {
        String displayName = required(profile, prefix + ".displayName");
        if (displayName.length() > 128) {
            throw new IllegalArgumentException("engine display name exceeds 128 characters");
        }
        Path executable = containedFile(assets,
                required(profile, prefix + ".executable"), Long.MAX_VALUE);
        Path network = containedFile(assets,
                required(profile, prefix + ".network"), Long.MAX_VALUE);
        if (!executable.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe")) {
            throw new IllegalArgumentException("engine executable must be a Windows .exe file");
        }
        if (!network.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".nnue")) {
            throw new IllegalArgumentException("engine network must be an .nnue file");
        }
        if (!network.getParent().equals(executable.getParent())) {
            throw new IllegalArgumentException(
                    "engine network must be beside the packaged executable");
        }
        LinkedHashMap<String, String> options = new LinkedHashMap<>();
        // The engine process already runs in the executable directory. Keeping
        // EvalFile relative avoids Windows native-process encoding damage when
        // the app image is installed below a non-ASCII directory such as D:\象棋.
        options.put("EvalFile", network.getFileName().toString());
        options.put("MultiPV", "3");
        return new EngineConfig(displayName, executable.toString(), "uci", options);
    }

    private static boolean sameExecutable(EngineConfig first, EngineConfig second) {
        if (first == null || first.getPath() == null || second.getPath() == null) {
            return false;
        }
        try {
            Path firstPath = Path.of(first.getPath()).toAbsolutePath().normalize();
            Path secondPath = Path.of(second.getPath()).toAbsolutePath().normalize();
            if (Files.exists(firstPath) && Files.exists(secondPath)) {
                return Files.isSameFile(firstPath, secondPath);
            }
            return firstPath.equals(secondPath);
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static List<String> books(java.util.Properties profile, Path assets)
            throws IOException {
        String value = profile.getProperty("book.files", "").trim();
        if (value.isEmpty()) {
            return List.of();
        }
        boolean allowLegacyObk = flag(profile, "book.allowLegacyObk");
        List<String> result = new ArrayList<>();
        for (String item : value.split(",")) {
            Path book = containedFile(assets, item.trim(), MAX_BOOK_BYTES);
            String fileName = book.getFileName().toString().toLowerCase(Locale.ROOT);
            if (fileName.endsWith(".obk")) {
                if (!allowLegacyObk) {
                    throw new IllegalArgumentException(
                            "packaged .obk requires explicit opt-in");
                }
                requireSqliteHeader(book);
            } else if (!fileName.endsWith(".xqb")) {
                throw new IllegalArgumentException(
                        "packaged book must use the audited .xqb format");
            }
            result.add(book.toString());
        }
        return List.copyOf(result);
    }

    private static void requireSqliteHeader(Path book) throws IOException {
        byte[] expected = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
        byte[] header = new byte[expected.length];
        try (InputStream input = Files.newInputStream(book)) {
            if (input.readNBytes(header, 0, header.length) != header.length
                    || !Arrays.equals(header, expected)) {
                throw new IllegalArgumentException(
                        "packaged .obk must be a standard SQLite 3 container");
            }
        }
    }

    private static Path containedFile(Path root, String relative, long maxBytes)
            throws IOException {
        Path candidate = root.resolve(relative).normalize().toAbsolutePath();
        if (Path.of(relative).isAbsolute() || !candidate.startsWith(root)
                || !Files.isRegularFile(candidate) || Files.size(candidate) == 0
                || Files.size(candidate) > maxBytes) {
            throw new IllegalArgumentException("invalid packaged asset: " + relative);
        }
        return candidate;
    }

    private static String required(java.util.Properties profile, String key) {
        String value = profile.getProperty(key, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("missing profile field: " + key);
        }
        return value;
    }

    private static int integer(java.util.Properties profile, String key, int min, int max) {
        try {
            int value = Integer.parseInt(required(profile, key));
            if (value < min || value > max) {
                throw new IllegalArgumentException("profile field out of range: " + key);
            }
            return value;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("invalid integer profile field: " + key);
        }
    }

    private static boolean flag(java.util.Properties profile, String key) {
        String value = profile.getProperty(key, "false").trim();
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IllegalArgumentException("invalid boolean profile field: " + key);
        }
        return Boolean.parseBoolean(value);
    }

    private static boolean isUsableEngine(EngineConfig config) {
        if (config == null || config.getPath() == null) {
            return false;
        }
        try {
            return Files.isRegularFile(Path.of(config.getPath()));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    record Result(boolean changed, List<String> diagnostics) {
        Result {
            diagnostics = List.copyOf(diagnostics);
        }

        static Result unchanged() {
            return new Result(false, List.of());
        }

        static Result failure(String detail) {
            String safe = detail == null || detail.isBlank()
                    ? "local asset bootstrap failed" : detail;
            return new Result(false, List.of(safe));
        }
    }
}
