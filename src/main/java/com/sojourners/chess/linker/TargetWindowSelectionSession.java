package com.sojourners.chess.linker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** One bounded target-chooser snapshot. Tokens cannot be reused across scans. */
public final class TargetWindowSelectionSession {

    public static final int MAX_WINDOW_CANDIDATES = 200;

    private final List<TargetWindowChoice> choices;
    private final Map<String, KnownSelection> selections;

    private TargetWindowSelectionSession(List<TargetWindowChoice> choices,
                                         Map<String, KnownSelection> selections) {
        this.choices = List.copyOf(choices);
        this.selections = Map.copyOf(selections);
    }

    public static TargetWindowSelectionSession create(
            List<NativeWindow> sourceWindows) {
        Objects.requireNonNull(sourceWindows, "sourceWindows");
        String nonce = UUID.randomUUID().toString().replace("-", "");
        List<TargetWindowChoice> choices = new ArrayList<>();
        Map<String, KnownSelection> selections = new LinkedHashMap<>();

        TargetWindowChoice crosshair = TargetWindowChoice.crosshair(
                "crosshair-" + nonce);
        choices.add(crosshair);
        selections.put(crosshair.selectionToken(),
                new KnownSelection(crosshair, 0));

        Map<Long, NativeWindow> uniqueWindows = new LinkedHashMap<>();
        for (NativeWindow source : sourceWindows) {
            NativeWindow window = Objects.requireNonNull(source, "sourceWindow");
            uniqueWindows.putIfAbsent(window.nativeHandle(), window);
            if (uniqueWindows.size() == MAX_WINDOW_CANDIDATES) break;
        }
        int index = 0;
        for (NativeWindow window : uniqueWindows.values()) {
            String token = "window-" + nonce + "-" + index++;
            TargetWindowChoice choice = new TargetWindowChoice(
                    token, window.title(), window.executableName(),
                    window.windowClassName(), window.clientWidth(),
                    window.clientHeight(), false);
            choices.add(choice);
            selections.put(token, new KnownSelection(choice, window.nativeHandle()));
        }
        return new TargetWindowSelectionSession(choices, selections);
    }

    public List<TargetWindowChoice> choices() {
        return choices;
    }

    public Resolution resolve(TargetWindowChoice selected) {
        TargetWindowChoice requested = Objects.requireNonNull(selected, "selected");
        KnownSelection known = selections.get(requested.selectionToken());
        if (known == null || !known.choice().equals(requested)) {
            throw new IllegalArgumentException(
                    "window selection is forged or no longer current");
        }
        return new Resolution(known.choice().crosshairFallback(),
                known.nativeHandle());
    }

    public record NativeWindow(long nativeHandle,
                               String title,
                               String executableName,
                               String windowClassName,
                               int clientWidth,
                               int clientHeight) {
        public NativeWindow {
            if (nativeHandle == 0) {
                throw new IllegalArgumentException("native window handle is required");
            }
            TargetWindowChoice validated = new TargetWindowChoice(
                    "validation", title, executableName, windowClassName,
                    clientWidth, clientHeight, false);
            title = validated.title();
            executableName = validated.executableName();
            windowClassName = validated.windowClassName();
        }
    }

    public record Resolution(boolean crosshairFallback, long nativeHandle) {
        public Resolution {
            if (crosshairFallback != (nativeHandle == 0)) {
                throw new IllegalArgumentException("invalid target resolution");
            }
        }
    }

    private record KnownSelection(TargetWindowChoice choice, long nativeHandle) { }
}
