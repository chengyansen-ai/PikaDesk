package com.sojourners.chess.config;

import com.sojourners.chess.enginee.Engine;
import com.sojourners.chess.model.EngineConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
            try (InputStream input = Files.newInputStream(profilePath)) {
                profile.load(input);
            }

            EngineConfig engine = engine(profile, assets);
            List<String> books = books(profile, assets);
            boolean usableEngineExists = target.getEngineConfigList().stream()
                    .anyMatch(LocalAssetBootstrap::isUsableEngine);
            if (usableEngineExists) {
                return Result.unchanged();
            }

            target.getEngineConfigList().clear();
            target.getEngineConfigList().add(engine);
            target.setEngineName(engine.getName());
            target.setThreadNum(integer(profile, "engine.threads", 1, 256));
            target.setHashSize(integer(profile, "engine.hashMiB", 16, 1_048_576));
            target.setAnalysisModel(Engine.AnalysisModel.FIXED_TIME);
            target.setAnalysisValue(integer(profile,
                    "engine.moveTimeMs", 100, 600_000));
            for (String book : books) {
                if (!target.getOpenBookList().contains(book)) {
                    target.getOpenBookList().add(book);
                }
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

    private static EngineConfig engine(java.util.Properties profile, Path assets)
            throws IOException {
        String displayName = required(profile, "engine.displayName");
        if (displayName.length() > 128) {
            throw new IllegalArgumentException("engine display name exceeds 128 characters");
        }
        Path executable = containedFile(assets,
                required(profile, "engine.executable"), Long.MAX_VALUE);
        Path network = containedFile(assets,
                required(profile, "engine.network"), Long.MAX_VALUE);
        if (!executable.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe")) {
            throw new IllegalArgumentException("engine executable must be a Windows .exe file");
        }
        if (!network.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".nnue")) {
            throw new IllegalArgumentException("engine network must be an .nnue file");
        }
        LinkedHashMap<String, String> options = new LinkedHashMap<>();
        options.put("EvalFile", network.toString());
        options.put("MultiPV", "3");
        return new EngineConfig(displayName, executable.toString(), "uci", options);
    }

    private static List<String> books(java.util.Properties profile, Path assets)
            throws IOException {
        String value = profile.getProperty("book.files", "").trim();
        if (value.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String item : value.split(",")) {
            Path book = containedFile(assets, item.trim(), MAX_BOOK_BYTES);
            if (!book.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xqb")) {
                throw new IllegalArgumentException("packaged book must use the audited .xqb format");
            }
            result.add(book.toString());
        }
        return List.copyOf(result);
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
