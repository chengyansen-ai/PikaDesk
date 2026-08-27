package com.sojourners.chess.testboard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LocalTestBoardStateTest {

    @Test
    void loadsAndExportsTheStandardPositionDeterministically() {
        LocalTestBoardState state = new LocalTestBoardState();

        assertAll(
                () -> assertEquals('r', state.pieceAt(0, 0)),
                () -> assertEquals('k', state.pieceAt(4, 0)),
                () -> assertEquals('K', state.pieceAt(4, 9)),
                () -> assertEquals('R', state.pieceAt(8, 9)),
                () -> assertEquals(LocalTestBoardState.STANDARD_FEN, state.fen())
        );
    }

    @Test
    void recordsTheActualMoveReceivedFromARedBottomBoard() {
        LocalTestBoardState state = new LocalTestBoardState();

        LocalTestBoardState.ClickResult selected = state.clickDisplaySquare(0, 9);
        LocalTestBoardState.ClickResult moved = state.clickDisplaySquare(0, 8);

        assertAll(
                () -> assertEquals(LocalTestBoardState.ClickKind.SELECTED, selected.kind()),
                () -> assertEquals(LocalTestBoardState.ClickKind.MOVED, moved.kind()),
                () -> assertEquals("a0a1", moved.move().orElseThrow().ucci()),
                () -> assertEquals(' ', state.pieceAt(0, 9)),
                () -> assertEquals('R', state.pieceAt(0, 8)),
                () -> assertEquals(1, state.receivedMoves().size())
        );
    }

    @Test
    void rotatesDisplayCoordinatesWithoutChangingCanonicalMoveNotation() {
        LocalTestBoardState state = new LocalTestBoardState();
        state.setOrientation(LocalTestBoardState.Orientation.BLACK_AT_BOTTOM);

        state.clickDisplaySquare(8, 0);
        LocalTestBoardState.ClickResult moved = state.clickDisplaySquare(8, 1);

        assertEquals("a0a1", moved.move().orElseThrow().ucci());
    }

    @Test
    void recordsCapturesAndReselectsAFriendlyPiece() {
        LocalTestBoardState state = new LocalTestBoardState();
        state.setFen("4k4/9/9/9/p8/R8/9/9/9/4K4 w");

        state.clickDisplaySquare(0, 5);
        LocalTestBoardState.ClickResult reselected = state.clickDisplaySquare(4, 9);
        state.clickDisplaySquare(0, 5);
        LocalTestBoardState.ClickResult captured = state.clickDisplaySquare(0, 4);

        assertAll(
                () -> assertEquals(LocalTestBoardState.ClickKind.SELECTED, reselected.kind()),
                () -> assertEquals("a4a5", captured.move().orElseThrow().ucci()),
                () -> assertEquals('p', captured.move().orElseThrow().capturedPiece()),
                () -> assertEquals('R', state.pieceAt(0, 4))
        );
    }

    @Test
    void validatesFenScaleAndAnimationBounds() {
        LocalTestBoardState state = new LocalTestBoardState();

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> state.setFen("9/9/9")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> state.setFen("4x4/9/9/9/9/9/9/9/9/4K4 w")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> state.setScale(0.5)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> state.setAnimationMillis(2_001))
        );

        state.setScale(1.5);
        state.setAnimationMillis(400);
        state.setTheme(LocalTestBoardState.Theme.HIGH_CONTRAST);

        assertAll(
                () -> assertEquals(1.5, state.scale()),
                () -> assertEquals(400, state.animationMillis()),
                () -> assertEquals(LocalTestBoardState.Theme.HIGH_CONTRAST, state.theme()),
                () -> assertTrue(state.receivedMoves().isEmpty())
        );
    }
}
