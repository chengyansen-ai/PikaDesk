package com.sojourners.chess.linker.profile;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Product-scope guard for external automation targets.
 *
 * <p>This is intentionally independent from recognition and input delivery so
 * every connection path fails before calibration when a known public ranked
 * platform is selected. It is a safety backstop, not a claim that window text
 * can prove authorization.</p>
 */
final class AutomationTargetPolicy {

    private static final List<String> BLOCKED_MARKERS = List.of(
            "jj象棋",
            "jjgame",
            "\\jj\\",
            "天天象棋"
    );

    private AutomationTargetPolicy() {
    }

    static void requirePermitted(ConnectionWizardState.TargetObservation target) {
        Objects.requireNonNull(target, "target");
        String identity = normalize(String.join("\n",
                target.executableName(),
                target.windowClassName(),
                target.titleHint(),
                target.localExecutablePath()));
        if (BLOCKED_MARKERS.stream().anyMatch(identity::contains)) {
            throw new IllegalArgumentException(
                    "公共平台或排位客户端不能作为自动走棋目标；"
                            + "请使用 PikaDesk 本地测试棋盘、离线棋盘或明确授权的自建环境。");
        }
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace('/', '\\');
    }
}
