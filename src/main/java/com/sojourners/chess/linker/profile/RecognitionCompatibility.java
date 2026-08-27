package com.sojourners.chess.linker.profile;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Allow-list of recognition configurations that passed the local-board
 * capture, move, and visual-confirmation probe. New combinations must be added
 * only after the same end-to-end probe passes; UI selection alone is not
 * evidence of compatibility.
 */
public final class RecognitionCompatibility {

    public static final String CLASSIC_THEME = "CLASSIC";
    public static final String XIANGQI_YOLO11_MODEL = "yolo11-xiangqi";

    private static final Set<Combination> VERIFIED = Set.of(
            new Combination(CLASSIC_THEME, XIANGQI_YOLO11_MODEL)
    );

    private RecognitionCompatibility() { }

    public static boolean isVerified(String themeId, String modelId) {
        if (themeId == null || modelId == null) {
            return false;
        }
        return VERIFIED.contains(new Combination(
                themeId.trim().toUpperCase(Locale.ROOT), modelId.trim()));
    }

    public static void requireVerified(String themeId, String modelId) {
        if (!isVerified(themeId, modelId)) {
            throw new IllegalArgumentException(
                    "主题与模型组合尚未通过端到端验证；当前仅支持 CLASSIC + yolo11-xiangqi");
        }
    }

    private record Combination(String themeId, String modelId) {
        private Combination {
            Objects.requireNonNull(themeId, "themeId");
            Objects.requireNonNull(modelId, "modelId");
        }
    }
}
