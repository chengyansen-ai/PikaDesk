package com.sojourners.chess.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ZobristMoveCodecTest {

    @Test
    void convertsUcciAndC90MovesInBothDirections() {
        assertEquals(0xaaa7, ZobristUtils.getVmoveFromMove("h2e2", false));
        assertEquals("h2e2", ZobristUtils.getMoveFromVmove(0xaaa7, false));
        assertEquals(0x3a59, ZobristUtils.getVmoveFromMove("h9g7", false));

        int mirrored = ZobristUtils.getVmoveFromMove("h2e2", true);
        assertEquals("h2e2", ZobristUtils.getMoveFromVmove(mirrored, true));
    }

    @Test
    void rejectsMalformedCoordinates() {
        assertThrows(IllegalArgumentException.class,
                () -> ZobristUtils.getVmoveFromMove("h2e2.exe", false));
        assertThrows(IllegalArgumentException.class,
                () -> ZobristUtils.getVmoveFromMove("j2e2", false));
        assertThrows(IllegalArgumentException.class,
                () -> ZobristUtils.getVmoveFromMove("h2h2", false));
    }
}
