package com.sojourners.chess.calibration;

import com.sojourners.chess.automation.BoardCoordinateMapper;

/**
 * Two-corner board calibration. The user clicks the top-left and the
 * bottom-right board intersections of any local board; the calibrator converts
 * the logical (AWT) screen points into client-relative physical bounds
 * compatible with {@link BoardCoordinateMapper.BoardBounds}.
 *
 * <p>Semantics: JNA window geometry is physical pixels, while AWT screenshot
 * coordinates are logical pixels on HiDPI displays. Multiplying the logical
 * point by the screen scaling factor yields the physical screen point; the
 * client origin is then subtracted to obtain client-relative bounds.</p>
 */
public final class BoardCalibrator {

    /** Minimal board extent in physical pixels. */
    public static final int MIN_BOARD_EXTENT_PIXELS = 5;

    private BoardCalibrator() {
    }

    public record ScreenPoint(double x, double y) {
        public ScreenPoint {
            if (!Double.isFinite(x) || !Double.isFinite(y)) {
                throw new IllegalArgumentException("screen point must be finite");
            }
        }
    }

    /**
     * Converts two logical screen corners into client-relative physical board
     * bounds. The first point must be the board's top-left intersection and the
     * second the bottom-right intersection.
     */
    public static BoardCoordinateMapper.BoardBounds fromTwoCorners(
            ScreenPoint topLeft,
            ScreenPoint bottomRight,
            BoardCoordinateMapper.ClientArea client,
            double screenScalingFactor) {
        if (topLeft == null || bottomRight == null) {
            throw new IllegalArgumentException("both corner points are required");
        }
        if (client == null) {
            throw new IllegalArgumentException("client area is required");
        }
        if (!Double.isFinite(screenScalingFactor) || screenScalingFactor <= 0) {
            throw new IllegalArgumentException("screen scaling factor must be positive");
        }
        long left = Math.round(topLeft.x() * screenScalingFactor) - client.screenX();
        long top = Math.round(topLeft.y() * screenScalingFactor) - client.screenY();
        long right = Math.round(bottomRight.x() * screenScalingFactor) - client.screenX();
        long bottom = Math.round(bottomRight.y() * screenScalingFactor) - client.screenY();
        long width = right - left;
        long height = bottom - top;
        if (width < MIN_BOARD_EXTENT_PIXELS || height < MIN_BOARD_EXTENT_PIXELS) {
            throw new IllegalArgumentException(
                    "请先点击棋盘最左上角的交叉点，再点击最右下角的交叉点");
        }
        if (left < 0 || top < 0 || right > client.width() || bottom > client.height()) {
            throw new IllegalArgumentException("标定点超出目标窗口客户区，请重试");
        }
        return new BoardCoordinateMapper.BoardBounds(
                (int) left, (int) top, (int) width, (int) height);
    }
}
