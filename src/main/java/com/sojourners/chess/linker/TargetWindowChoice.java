package com.sojourners.chess.linker;

import java.util.Objects;

/** Sanitized window metadata safe to render in the target chooser. */
public record TargetWindowChoice(String selectionToken,
                                 String title,
                                 String executableName,
                                 String windowClassName,
                                 int clientWidth,
                                 int clientHeight,
                                 boolean crosshairFallback) {

    public TargetWindowChoice {
        selectionToken = token(selectionToken);
        title = text(title, "title", 256, false);
        executableName = text(executableName, "executableName", 260,
                crosshairFallback);
        windowClassName = text(windowClassName, "windowClassName", 256,
                crosshairFallback);
        if (crosshairFallback) {
            if (clientWidth != 0 || clientHeight != 0) {
                throw new IllegalArgumentException(
                        "crosshair fallback must not carry window bounds");
            }
        } else {
            range(clientWidth, "clientWidth");
            range(clientHeight, "clientHeight");
        }
    }

    static TargetWindowChoice crosshair(String token) {
        return new TargetWindowChoice(token, "准星点选窗口（兼容方式）",
                "", "", 0, 0, true);
    }

    @Override
    public String toString() {
        return crosshairFallback
                ? title
                : title + " — " + executableName + " · "
                + clientWidth + "×" + clientHeight;
    }

    private static String token(String value) {
        String normalized = Objects.requireNonNull(value, "selectionToken").trim();
        if (!normalized.matches("[A-Za-z0-9_-]{1,80}")) {
            throw new IllegalArgumentException("invalid selection token");
        }
        return normalized;
    }

    private static String text(String value, String field,
                               int maximumLength, boolean allowEmpty) {
        String normalized = Objects.requireNonNull(value, field)
                .trim().replaceAll("\\s+", " ");
        if ((!allowEmpty && normalized.isEmpty())
                || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " is outside its bounds");
        }
        return normalized;
    }

    private static void range(int value, String field) {
        if (value < 64 || value > 32_768) {
            throw new IllegalArgumentException(
                    field + " must be between 64 and 32768");
        }
    }
}
