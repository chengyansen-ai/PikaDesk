package com.sojourners.chess.linker;

import com.sojourners.chess.linker.profile.ConnectionProfile;
import com.sojourners.chess.linker.profile.ConnectionWizardState;
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

    @Test
    void callbackCarriesTheReadOnlyCapabilityToPlatformLinkers() {
        LinkerCallBack callback = new StubCallback(true);

        assertEquals(LinkMode.READ_ONLY_ADVISOR, callback.connectionMode());
        assertFalse(callback.connectionMode().externalInputAllowed());
    }

    private record StubCallback(boolean isWatchMode) implements LinkerCallBack {
        @Override public void linkerInitChessBoard(String fenCode, boolean isReverse) { }
        @Override public char[][] getEngineBoard() { return new char[10][9]; }
        @Override public boolean isThinking() { return false; }
        @Override public void linkerMove(int x1, int y1, int x2, int y2) { }
        @Override public ConnectionProfile configureConnection(ConnectionWizardState wizard) {
            return null;
        }
    }
}
