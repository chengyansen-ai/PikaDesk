package com.sojourners.chess.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class VoiceAnnouncementTest {

    @Test
    void normalizesPlainTextAndAddsABoundedCategoryPrefix() {
        VoiceAnnouncement announcement = VoiceAnnouncement.move(
                "  炮二\n平五  ");

        assertEquals(VoiceAnnouncement.Category.MOVE,
                announcement.category());
        assertEquals("走法，炮二 平五", announcement.text());
    }

    @Test
    void rejectsBlankOversizedControlAndMarkupInput() {
        assertThrows(IllegalArgumentException.class,
                () -> VoiceAnnouncement.warning("   "));
        assertThrows(IllegalArgumentException.class,
                () -> VoiceAnnouncement.result("x".repeat(121)));
        assertThrows(IllegalArgumentException.class,
                () -> VoiceAnnouncement.warning("警告\u0000停止"));
        assertThrows(IllegalArgumentException.class,
                () -> VoiceAnnouncement.move("<speak>炮二平五</speak>"));
        assertThrows(IllegalArgumentException.class,
                () -> VoiceAnnouncement.warning(" ".repeat(10_000)));
    }
}
