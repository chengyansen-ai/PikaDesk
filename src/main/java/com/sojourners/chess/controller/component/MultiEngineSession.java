package com.sojourners.chess.controller.component;

import com.sojourners.chess.analysis.MultiEngineAnalysisWorkspace;
import com.sojourners.chess.enginee.EngineRegistry;
import com.sojourners.chess.model.EngineConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class MultiEngineSession implements AutoCloseable {

    private final EngineRegistry registry;
    private final MultiEngineAnalysisWorkspace workspace;

    MultiEngineSession(List<EngineConfig> configuredEngines,
                       int totalThreads,
                       int totalHashMiB,
                       MultiEngineAnalysisWorkspace.ChangeListener listener) throws IOException {
        List<EngineConfig> engines = configuredEngines.stream()
                .limit(EngineRegistry.MAX_ENGINES).toList();
        if (engines.isEmpty()) {
            throw new IllegalArgumentException("请先在“引擎管理”中添加至少一个本地引擎");
        }
        if (totalThreads < engines.size() || totalHashMiB < engines.size()) {
            throw new IllegalArgumentException("总线程和 Hash 预算不能小于引擎数量");
        }

        registry = new EngineRegistry(
                new EngineRegistry.ResourceBudget(totalThreads, totalHashMiB),
                (engineId, line) -> { });
        registry.registerAll(specs(engines, totalThreads, totalHashMiB));
        workspace = new MultiEngineAnalysisWorkspace(registry, listener);
    }

    List<EngineRegistry.EngineSnapshot> start() throws InterruptedException {
        return registry.startAll();
    }

    MultiEngineAnalysisWorkspace workspace() {
        return workspace;
    }

    @Override
    public void close() {
        workspace.close();
        registry.close();
    }

    private List<EngineRegistry.EngineSpec> specs(List<EngineConfig> engines,
                                                   int totalThreads,
                                                   int totalHashMiB) {
        List<EngineRegistry.EngineSpec> result = new ArrayList<>(engines.size());
        for (int index = 0; index < engines.size(); index++) {
            EngineConfig config = engines.get(index);
            EngineRegistry.Protocol protocol = protocol(config.getProtocol());
            result.add(new EngineRegistry.EngineSpec(
                    "engine-" + (index + 1),
                    displayName(config, index),
                    Path.of(config.getPath()),
                    List.of(),
                    protocol,
                    share(totalThreads, engines.size(), index),
                    share(totalHashMiB, engines.size(), index),
                    multiPv(config.getOptions()),
                    Duration.ofSeconds(5)
            ));
        }
        return result;
    }

    private EngineRegistry.Protocol protocol(String value) {
        if (value == null) {
            throw new IllegalArgumentException("引擎协议不能为空");
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "uci" -> EngineRegistry.Protocol.UCI;
            case "ucci" -> EngineRegistry.Protocol.UCCI;
            default -> throw new IllegalArgumentException("不支持的引擎协议: " + value);
        };
    }

    private String displayName(EngineConfig config, int index) {
        String name = config.getName();
        return name == null || name.isBlank() ? "本地引擎 " + (index + 1) : name.trim();
    }

    private int multiPv(LinkedHashMap<String, String> options) {
        Map<String, String> safeOptions = options == null ? Map.of() : options;
        String value = safeOptions.get("MultiPV");
        if (value == null) {
            return 1;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > 256) {
                throw new IllegalArgumentException("MultiPV 必须在 1 到 256 之间");
            }
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("MultiPV 不是有效整数: " + value, invalid);
        }
    }

    private int share(int total, int count, int index) {
        return total / count + (index < total % count ? 1 : 0);
    }
}
