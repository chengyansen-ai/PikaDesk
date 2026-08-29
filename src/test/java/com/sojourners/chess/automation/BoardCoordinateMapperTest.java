package com.sojourners.chess.automation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BoardCoordinateMapperTest {

    private static final String TARGET_ID = "local-test-board:4242";
    private static final long REVISION = 7;
    private static final BoardCoordinateMapper.ClientArea CLIENT =
            new BoardCoordinateMapper.ClientArea(100, 200, 1_000, 800);
    private static final BoardCoordinateMapper.BoardBounds BOARD =
            new BoardCoordinateMapper.BoardBounds(50, 40, 800, 630);
    private static final BoardCoordinateMapper.ActivitySnapshot QUIET =
            new BoardCoordinateMapper.ActivitySnapshot(20, 30, 100);

    private final BoardCoordinateMapper mapper = new BoardCoordinateMapper();
    private final AutomationSafetyKernel.Authorization authorization =
            new AutomationSafetyKernel.Authorization(TARGET_ID, REVISION);

    @Test
    void mapsCanonicalUcciSquaresForRedAtBottom() {
        BoardCoordinateMapper.MappingResult result = mapper.mapMove(
                authorization,
                calibration(BoardCoordinateMapper.Orientation.RED_AT_BOTTOM),
                currentTarget(true, true),
                QUIET,
                QUIET,
                new BoardCoordinateMapper.Move(0, 0, 0, 1)
        );

        assertAll(
                () -> assertTrue(result.accepted()),
                () -> assertEquals(new BoardCoordinateMapper.ScreenPoint(150, 870),
                        result.points().orElseThrow().from()),
                () -> assertEquals(new BoardCoordinateMapper.ScreenPoint(150, 800),
                        result.points().orElseThrow().to())
        );
    }

    @Test
    void rotatesCanonicalUcciSquaresForBlackAtBottom() {
        BoardCoordinateMapper.MappingResult result = mapper.mapMove(
                authorization,
                calibration(BoardCoordinateMapper.Orientation.BLACK_AT_BOTTOM),
                currentTarget(true, true),
                QUIET,
                QUIET,
                new BoardCoordinateMapper.Move(0, 0, 0, 1)
        );

        assertAll(
                () -> assertTrue(result.accepted()),
                () -> assertEquals(new BoardCoordinateMapper.ScreenPoint(950, 240),
                        result.points().orElseThrow().from()),
                () -> assertEquals(new BoardCoordinateMapper.ScreenPoint(950, 310),
                        result.points().orElseThrow().to())
        );
    }

    @Test
    void previewsCoordinatesWithoutRequiringInputAuthorizationOrWindowFocus() {
        BoardCoordinateMapper.MappingResult result = mapper.previewMove(
                calibration(BoardCoordinateMapper.Orientation.RED_AT_BOTTOM),
                new BoardCoordinateMapper.Move(0, 0, 0, 1));

        assertAll(
                () -> assertTrue(result.accepted()),
                () -> assertEquals(new BoardCoordinateMapper.ScreenPoint(150, 870),
                        result.points().orElseThrow().from()),
                () -> assertEquals(new BoardCoordinateMapper.ScreenPoint(150, 800),
                        result.points().orElseThrow().to())
        );
    }

    @Test
    void rejectsAuthorizationIdentityAndRevisionChanges() {
        BoardCoordinateMapper.Calibration calibration =
                calibration(BoardCoordinateMapper.Orientation.RED_AT_BOTTOM);

        assertAll(
                () -> assertRejected(mapper.mapMove(
                                new AutomationSafetyKernel.Authorization("other-window", REVISION),
                                calibration, currentTarget(true, true), QUIET, QUIET, validMove()),
                        BoardCoordinateMapper.RejectionReason.AUTHORIZATION_MISMATCH),
                () -> assertRejected(mapper.mapMove(
                                authorization, calibration,
                                new BoardCoordinateMapper.TargetSnapshot(
                                        TARGET_ID, REVISION + 1, CLIENT, 144, true, true),
                                QUIET, QUIET, validMove()),
                        BoardCoordinateMapper.RejectionReason.TARGET_REVISION_CHANGED)
        );
    }

    @Test
    void rejectsFocusVisibilityGeometryAndDpiChanges() {
        BoardCoordinateMapper.Calibration calibration =
                calibration(BoardCoordinateMapper.Orientation.RED_AT_BOTTOM);

        assertAll(
                () -> assertRejected(mapper.mapMove(authorization, calibration,
                                currentTarget(false, true), QUIET, QUIET, validMove()),
                        BoardCoordinateMapper.RejectionReason.TARGET_NOT_FOCUSED),
                () -> assertRejected(mapper.mapMove(authorization, calibration,
                                currentTarget(true, false), QUIET, QUIET, validMove()),
                        BoardCoordinateMapper.RejectionReason.TARGET_NOT_VISIBLE),
                () -> assertRejected(mapper.mapMove(authorization, calibration,
                                new BoardCoordinateMapper.TargetSnapshot(
                                        TARGET_ID, REVISION,
                                        new BoardCoordinateMapper.ClientArea(101, 200, 1_000, 800),
                                        144, true, true), QUIET, QUIET, validMove()),
                        BoardCoordinateMapper.RejectionReason.CLIENT_AREA_CHANGED),
                () -> assertRejected(mapper.mapMove(authorization, calibration,
                                new BoardCoordinateMapper.TargetSnapshot(
                                        TARGET_ID, REVISION, CLIENT, 192, true, true),
                                QUIET, QUIET, validMove()),
                        BoardCoordinateMapper.RejectionReason.DPI_CHANGED)
        );
    }

    @Test
    void rejectsAnyUserActivityBetweenReadyAndExecution() {
        BoardCoordinateMapper.Calibration calibration =
                calibration(BoardCoordinateMapper.Orientation.RED_AT_BOTTOM);

        BoardCoordinateMapper.MappingResult movedPointer = mapper.mapMove(
                authorization, calibration, currentTarget(true, true), QUIET,
                new BoardCoordinateMapper.ActivitySnapshot(21, 30, 100), validMove());
        BoardCoordinateMapper.MappingResult inputWhilePointerReturned = mapper.mapMove(
                authorization, calibration, currentTarget(true, true), QUIET,
                new BoardCoordinateMapper.ActivitySnapshot(20, 30, 101), validMove());

        assertAll(
                () -> assertRejected(movedPointer,
                        BoardCoordinateMapper.RejectionReason.USER_INPUT_DETECTED),
                () -> assertRejected(inputWhilePointerReturned,
                        BoardCoordinateMapper.RejectionReason.USER_INPUT_DETECTED),
                () -> assertEquals(
                        "user input changed after execution became ready; "
                                + "pointerChanged=true,inputSequenceChanged=false",
                        movedPointer.rejection().orElseThrow().detail()),
                () -> assertEquals(
                        "user input changed after execution became ready; "
                                + "pointerChanged=false,inputSequenceChanged=true",
                        inputWhilePointerReturned.rejection().orElseThrow().detail())
        );
    }

    @Test
    void rejectsMalformedCalibrationAndSquaresBeforeMapping() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new BoardCoordinateMapper.Calibration(
                                TARGET_ID, REVISION, CLIENT,
                                new BoardCoordinateMapper.BoardBounds(200, 100, 800, 630),
                                144, BoardCoordinateMapper.Orientation.RED_AT_BOTTOM)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new BoardCoordinateMapper.Move(-1, 0, 0, 1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new BoardCoordinateMapper.Move(0, 0, 0, 10)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new BoardCoordinateMapper.Move(0, 0, 0, 0))
        );
    }

    @Test
    void mapsEverySquareInsideBothBoardAndClientBounds() {
        BoardCoordinateMapper.Calibration calibration =
                calibration(BoardCoordinateMapper.Orientation.RED_AT_BOTTOM);

        for (int file = 0; file < 9; file++) {
            for (int rank = 0; rank < 10; rank++) {
                BoardCoordinateMapper.Move fromEverySquare =
                        new BoardCoordinateMapper.Move(file, rank, (file + 1) % 9, rank);
                BoardCoordinateMapper.MappingResult result = mapper.mapMove(
                        authorization, calibration, currentTarget(true, true),
                        QUIET, QUIET, fromEverySquare);
                assertTrue(result.accepted());
                assertTrue(BOARD.containsScreenPoint(CLIENT,
                        result.points().orElseThrow().from()));
                assertTrue(CLIENT.contains(result.points().orElseThrow().from()));
            }
        }
    }

    private BoardCoordinateMapper.Calibration calibration(
            BoardCoordinateMapper.Orientation orientation) {
        return new BoardCoordinateMapper.Calibration(
                TARGET_ID, REVISION, CLIENT, BOARD, 144, orientation);
    }

    private BoardCoordinateMapper.TargetSnapshot currentTarget(boolean focused,
                                                                boolean visible) {
        return new BoardCoordinateMapper.TargetSnapshot(
                TARGET_ID, REVISION, CLIENT, 144, focused, visible);
    }

    private BoardCoordinateMapper.Move validMove() {
        return new BoardCoordinateMapper.Move(0, 0, 0, 1);
    }

    private void assertRejected(BoardCoordinateMapper.MappingResult result,
                                BoardCoordinateMapper.RejectionReason reason) {
        assertAll(
                () -> assertFalse(result.accepted()),
                () -> assertEquals(reason, result.rejection().orElseThrow().reason()),
                () -> assertTrue(result.points().isEmpty())
        );
    }
}
