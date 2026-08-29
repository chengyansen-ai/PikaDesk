package com.sojourners.chess.linker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ObservedTurnAlignmentTest {

    @Test
    void correctsAnInitialRedTurnGuessWhenTheObservedMoverIsBlack() {
        ObservedTurnAlignment alignment = ObservedTurnAlignment.fromMove(true, 'n');

        assertTrue(alignment.corrected());
        assertFalse(alignment.moverRed());
        assertTrue(alignment.nextRedToMove());
    }

    @Test
    void correctsAnInitialBlackTurnGuessWhenTheObservedMoverIsRed() {
        ObservedTurnAlignment alignment = ObservedTurnAlignment.fromMove(false, 'P');

        assertTrue(alignment.corrected());
        assertTrue(alignment.moverRed());
        assertFalse(alignment.nextRedToMove());
    }

    @Test
    void leavesAnAlreadyCorrectTurnUnchanged() {
        assertFalse(ObservedTurnAlignment.fromMove(true, 'R').corrected());
        assertFalse(ObservedTurnAlignment.fromMove(false, 'c').corrected());
    }

    @Test
    void rejectsAnEmptyOrUnknownSourceSquare() {
        assertThrows(IllegalArgumentException.class,
                () -> ObservedTurnAlignment.fromMove(true, ' '));
        assertThrows(IllegalArgumentException.class,
                () -> ObservedTurnAlignment.fromMove(true, '?'));
    }

    @Test
    void duplicateQueuedFrameCanBeDroppedWithoutThrowingOnTheUiThread() {
        assertTrue(ObservedTurnAlignment.tryFromMove(true, ' ').isEmpty());
        assertTrue(ObservedTurnAlignment.tryFromMove(false, '?').isEmpty());
        assertTrue(ObservedTurnAlignment.tryFromMove(true, 'P').isPresent());
    }
}
