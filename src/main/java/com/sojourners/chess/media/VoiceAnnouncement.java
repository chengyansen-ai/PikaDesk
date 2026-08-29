package com.sojourners.chess.media;

import java.util.Objects;

/**
 * A validated, plain-text announcement that is safe to pass to a local speech
 * backend. The final text is deliberately small and excludes markup.
 */
public record VoiceAnnouncement(Category category, String text) {

    public static final int MAX_TEXT_LENGTH = 120;

    public VoiceAnnouncement {
        Objects.requireNonNull(category, "category");
        text = normalizeAndValidate(text);
    }

    public static VoiceAnnouncement move(String detail) {
        return create(Category.MOVE, "走法，", detail);
    }

    public static VoiceAnnouncement warning(String detail) {
        return create(Category.WARNING, "警告，", detail);
    }

    public static VoiceAnnouncement result(String detail) {
        return create(Category.RESULT, "结果，", detail);
    }

    private static VoiceAnnouncement create(Category category,
                                             String prefix,
                                             String detail) {
        String normalizedDetail = normalizeAndValidate(detail);
        return new VoiceAnnouncement(category, prefix + normalizedDetail);
    }

    private static String normalizeAndValidate(String source) {
        if (source == null) {
            throw new IllegalArgumentException("Voice text is required");
        }
        for (int index = 0; index < source.length(); index++) {
            char character = source.charAt(index);
            if ((Character.isISOControl(character)
                    && !Character.isWhitespace(character))
                    || character == '<' || character == '>') {
                throw new IllegalArgumentException(
                        "Voice text must be plain text");
            }
        }
        String normalized = source.strip().replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Voice text must not be blank");
        }
        if (normalized.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("Voice text is too long");
        }
        return normalized;
    }

    public enum Category {
        MOVE,
        WARNING,
        RESULT
    }
}
