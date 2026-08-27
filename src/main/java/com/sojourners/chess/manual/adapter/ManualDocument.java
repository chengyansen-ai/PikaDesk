package com.sojourners.chess.manual.adapter;

import com.sojourners.chess.game.tree.GameTree;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Shared, format-neutral game record used by bounded import/export adapters. */
public record ManualDocument(GameTree tree, Result result, Map<String, String> metadata) {

    public static final String STANDARD_FEN =
            "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w";
    private static final Pattern TAG_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,31}");

    public ManualDocument {
        Objects.requireNonNull(tree, "tree");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(metadata, "metadata");
        if (metadata.size() > 64) {
            throw new IllegalArgumentException("metadata tag limit exceeded");
        }
        TreeMap<String, String> normalized = new TreeMap<>();
        metadata.forEach((rawName, rawValue) -> {
            String name = Objects.requireNonNull(rawName, "metadata name").trim();
            String value = Objects.requireNonNull(rawValue, "metadata value");
            if (!TAG_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("invalid metadata tag: " + name);
            }
            if (name.equals("Game") || name.equals("FEN")
                    || name.equals("Format") || name.equals("Result")) {
                throw new IllegalArgumentException("reserved metadata tag: " + name);
            }
            if (value.length() > 1_024 || value.indexOf('\0') >= 0
                    || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("metadata value is outside its limits");
            }
            if (normalized.put(name, value) != null) {
                throw new IllegalArgumentException("duplicate metadata tag: " + name);
            }
        });
        metadata = Collections.unmodifiableMap(normalized);
    }

    public enum Result {
        RED_WIN("1-0"),
        BLACK_WIN("0-1"),
        DRAW("1/2-1/2"),
        ONGOING("*");

        private final String token;

        Result(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }

        public static Result fromToken(String token) {
            for (Result result : values()) {
                if (result.token.equals(token)) return result;
            }
            throw new IllegalArgumentException("unsupported result token: " + token);
        }
    }
}
