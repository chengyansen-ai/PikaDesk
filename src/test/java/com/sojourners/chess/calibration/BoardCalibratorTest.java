package com.sojourners.chess.calibration;

import com.sojourners.chess.automation.BoardCoordinateMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoardCalibratorTest {

    private static final BoardCoordinateMapper.ClientArea CLIENT =
            new BoardCoordinateMapper.ClientArea(100, 200, 1600, 900);

    @Test
    void convertsLogicalCornersToClientPhysicalBounds() {
        // scaling 1.5: logical (200,200) -> physical (300,300) -> client (200,100)
        BoardCoordinateMapper.BoardBounds bounds = BoardCalibrator.fromTwoCorners(
                point(200, 200), point(1000, 700), CLIENT, 1.5);
        assertEquals(200, bounds.x());
        assertEquals(100, bounds.y());
        assertEquals(1200, bounds.width());
        assertEquals(750, bounds.height());
    }

    @Test
    void noScalingKeepsLogicalEqualsPhysical() {
        BoardCoordinateMapper.BoardBounds bounds = BoardCalibrator.fromTwoCorners(
                point(200, 200), point(1200, 800), CLIENT, 1.0);
        assertEquals(100, bounds.x());
        assertEquals(0, bounds.y());
        assertEquals(1000, bounds.width());
        assertEquals(600, bounds.height());
    }

    @Test
    void physicalHiDpiSnapshotUsesUnitFactor() {
        // HiDPI: Robot snapshot is physical-resolution, so the effective
        // factor derived from (logicalWidth * scale / imageWidth) is 1.0
        // even though the display scale is 1.5. Points are already physical.
        BoardCoordinateMapper.BoardBounds bounds = BoardCalibrator.fromTwoCorners(
                point(200, 200), point(1200, 800), CLIENT, 1.0);
        assertEquals(100, bounds.x());
        assertEquals(0, bounds.y());
        assertEquals(1000, bounds.width());
        assertEquals(600, bounds.height());
    }

    @Test
    void reversedCornersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> BoardCalibrator.fromTwoCorners(
                point(1200, 800), point(200, 200), CLIENT, 1.0));
    }

    @Test
    void tooSmallSelectionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> BoardCalibrator.fromTwoCorners(
                point(200, 200), point(201, 201), CLIENT, 1.0));
    }

    @Test
    void outsideClientAreaIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> BoardCalibrator.fromTwoCorners(
                point(10, 10), point(1200, 800), CLIENT, 1.0));
    }

    @Test
    void invalidScalingIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> BoardCalibrator.fromTwoCorners(
                point(200, 200), point(1200, 800), CLIENT, 0.0));
        assertThrows(IllegalArgumentException.class, () -> BoardCalibrator.fromTwoCorners(
                point(200, 200), point(1200, 800), CLIENT, Double.NaN));
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> BoardCalibrator.fromTwoCorners(
                null, point(1200, 800), CLIENT, 1.0));
        assertThrows(IllegalArgumentException.class, () -> BoardCalibrator.fromTwoCorners(
                point(200, 200), null, CLIENT, 1.0));
        assertThrows(IllegalArgumentException.class, () -> BoardCalibrator.fromTwoCorners(
                point(200, 200), point(1200, 800), null, 1.0));
    }

    private static BoardCalibrator.ScreenPoint point(double x, double y) {
        return new BoardCalibrator.ScreenPoint(x, y);
    }
}
