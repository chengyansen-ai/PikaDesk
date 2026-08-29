package com.sojourners.chess.linker;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WindowsGraphLinkerResyncTest {

    @Test
    void timeoutWithChangedBoardResyncsInsteadOfPausing() {
        assertTrue(WindowsGraphLinker.shouldResyncOnConfirmationTimeout(
                Optional.of(MoveConfirmationTracker.Status.ANIMATION_TIMEOUT),
                true));
    }

    @Test
    void timeoutWithUnchangedBoardStillPauses() {
        assertFalse(WindowsGraphLinker.shouldResyncOnConfirmationTimeout(
                Optional.of(MoveConfirmationTracker.Status.ANIMATION_TIMEOUT),
                false));
    }

    @Test
    void nonTimeoutOutcomesAlwaysPauseEvenIfBoardChanged() {
        assertFalse(WindowsGraphLinker.shouldResyncOnConfirmationTimeout(
                Optional.of(MoveConfirmationTracker.Status.POSITION_MISMATCH),
                true));
        assertFalse(WindowsGraphLinker.shouldResyncOnConfirmationTimeout(
                Optional.of(MoveConfirmationTracker.Status.TARGET_CHANGED),
                true));
        assertFalse(WindowsGraphLinker.shouldResyncOnConfirmationTimeout(
                Optional.empty(), true));
    }
}
