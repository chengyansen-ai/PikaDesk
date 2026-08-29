package com.sojourners.chess.linker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ConnectionStatusTest {

    @Test
    void carriesOnlyABoundedUserFacingLifecycleMessage() {
        ConnectionStatus status = ConnectionStatus.of(
                ConnectionStatus.State.OBSERVING,
                "  正在识别棋盘\n并等待走子  ");

        assertEquals(ConnectionStatus.State.OBSERVING, status.state());
        assertEquals("正在识别棋盘 并等待走子", status.message());
    }

    @Test
    void rejectsBlankOrUnboundedDetails() {
        assertThrows(IllegalArgumentException.class,
                () -> ConnectionStatus.of(ConnectionStatus.State.PAUSED, "  "));
        assertThrows(IllegalArgumentException.class,
                () -> ConnectionStatus.of(ConnectionStatus.State.PAUSED, "x".repeat(121)));
    }

    @Test
    void providesAStableInitialStatus() {
        assertEquals(ConnectionStatus.State.IDLE, ConnectionStatus.idle().state());
        assertEquals("未连接", ConnectionStatus.idle().message());
    }
}
