package com.sojourners.chess.media;

/** User-controlled categories for local speech. */
public record VoicePreferences(boolean enabled,
                               boolean moves,
                               boolean warnings,
                               boolean results) {

    public static VoicePreferences disabled() {
        return new VoicePreferences(false, true, true, true);
    }

    public static VoicePreferences allEnabled() {
        return new VoicePreferences(true, true, true, true);
    }

    public boolean allows(VoiceAnnouncement.Category category) {
        if (!enabled) {
            return false;
        }
        return switch (category) {
            case MOVE -> moves;
            case WARNING -> warnings;
            case RESULT -> results;
        };
    }
}
