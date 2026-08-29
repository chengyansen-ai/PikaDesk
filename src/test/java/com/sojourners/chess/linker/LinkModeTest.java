package com.sojourners.chess.linker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LinkModeTest {

    @Test
    void readOnlyAdvisorIsTheSafeDefaultAndCannotSendExternalInput() {
        assertEquals(LinkMode.READ_ONLY_ADVISOR, LinkMode.safeDefault());
        assertEquals(LinkMode.READ_ONLY_ADVISOR, LinkMode.fromDisplayName("只读陪练"));
        assertEquals("只读陪练", LinkMode.READ_ONLY_ADVISOR.displayName());
        assertFalse(LinkMode.READ_ONLY_ADVISOR.externalInputAllowed());
    }

    @Test
    void authorizedAutomationRemainsAnExplicitInputCapableChoice() {
        assertEquals(LinkMode.AUTHORIZED_AUTOMATION, LinkMode.fromDisplayName("自动走棋"));
        assertTrue(LinkMode.AUTHORIZED_AUTOMATION.externalInputAllowed());
    }

    @Test
    void legacyWatchLabelMigratesToReadOnlyAdvisor() {
        assertEquals(LinkMode.READ_ONLY_ADVISOR, LinkMode.fromDisplayName("观战模式"));
    }
}
