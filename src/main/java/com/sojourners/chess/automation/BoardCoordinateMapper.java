package com.sojourners.chess.automation;

import java.util.Objects;
import java.util.Optional;

/**
 * Converts canonical UCCI coordinates to screen points only after the current
 * target and user-input state still match the explicitly authorized baseline.
 */
public final class BoardCoordinateMapper {

    public MappingResult mapMove(AutomationSafetyKernel.Authorization authorization,
                                 Calibration calibration,
                                 TargetSnapshot currentTarget,
                                 ActivitySnapshot activityAtReady,
                                 ActivitySnapshot activityBeforeExecution,
                                 Move move) {
        Objects.requireNonNull(authorization, "authorization");
        Objects.requireNonNull(calibration, "calibration");
        Objects.requireNonNull(currentTarget, "currentTarget");
        Objects.requireNonNull(activityAtReady, "activityAtReady");
        Objects.requireNonNull(activityBeforeExecution, "activityBeforeExecution");
        Objects.requireNonNull(move, "move");

        if (!authorization.targetId().equals(calibration.targetId())
                || authorization.targetRevision() != calibration.targetRevision()) {
            return reject(RejectionReason.AUTHORIZATION_MISMATCH,
                    "authorization does not match calibration");
        }
        if (!currentTarget.targetId().equals(calibration.targetId())) {
            return reject(RejectionReason.TARGET_ID_CHANGED,
                    "selected target identity changed");
        }
        if (currentTarget.targetRevision() != calibration.targetRevision()) {
            return reject(RejectionReason.TARGET_REVISION_CHANGED,
                    "selected target revision changed");
        }
        if (!currentTarget.focused()) {
            return reject(RejectionReason.TARGET_NOT_FOCUSED,
                    "selected target is not focused");
        }
        if (!currentTarget.visible()) {
            return reject(RejectionReason.TARGET_NOT_VISIBLE,
                    "selected target is not visible");
        }
        if (!currentTarget.clientArea().equals(calibration.clientArea())) {
            return reject(RejectionReason.CLIENT_AREA_CHANGED,
                    "target client position or size changed");
        }
        if (currentTarget.dpi() != calibration.dpi()) {
            return reject(RejectionReason.DPI_CHANGED,
                    "target DPI changed");
        }
        if (!activityAtReady.equals(activityBeforeExecution)) {
            boolean pointerChanged = activityAtReady.pointerX()
                    != activityBeforeExecution.pointerX()
                    || activityAtReady.pointerY() != activityBeforeExecution.pointerY();
            boolean inputSequenceChanged = activityAtReady.inputSequence()
                    != activityBeforeExecution.inputSequence();
            return reject(RejectionReason.USER_INPUT_DETECTED,
                    "user input changed after execution became ready; pointerChanged="
                            + pointerChanged + ",inputSequenceChanged="
                            + inputSequenceChanged);
        }

        ScreenPoint from = mapSquare(calibration, move.fromFile(), move.fromRank());
        ScreenPoint to = mapSquare(calibration, move.toFile(), move.toRank());
        if (!calibration.boardBounds().containsScreenPoint(calibration.clientArea(), from)
                || !calibration.boardBounds().containsScreenPoint(calibration.clientArea(), to)
                || !calibration.clientArea().contains(from)
                || !calibration.clientArea().contains(to)) {
            return reject(RejectionReason.COORDINATE_OUTSIDE_BOUNDS,
                    "mapped point is outside the board or client area");
        }
        return MappingResult.accepted(new MovePoints(from, to));
    }

    private ScreenPoint mapSquare(Calibration calibration, int file, int rank) {
        int displayFile;
        int displayRow;
        if (calibration.orientation() == Orientation.RED_AT_BOTTOM) {
            displayFile = file;
            displayRow = 9 - rank;
        } else {
            displayFile = 8 - file;
            displayRow = rank;
        }

        BoardBounds board = calibration.boardBounds();
        ClientArea client = calibration.clientArea();
        long clientX = board.x() + Math.round(displayFile * board.width() / 8.0);
        long clientY = board.y() + Math.round(displayRow * board.height() / 9.0);
        long screenX = (long) client.screenX() + clientX;
        long screenY = (long) client.screenY() + clientY;
        if (screenX < Integer.MIN_VALUE || screenX > Integer.MAX_VALUE
                || screenY < Integer.MIN_VALUE || screenY > Integer.MAX_VALUE) {
            return new ScreenPoint(Integer.MIN_VALUE, Integer.MIN_VALUE);
        }
        return new ScreenPoint((int) screenX, (int) screenY);
    }

    private MappingResult reject(RejectionReason reason, String detail) {
        return MappingResult.rejected(new Rejection(reason, detail));
    }

    public enum Orientation {
        RED_AT_BOTTOM,
        BLACK_AT_BOTTOM
    }

    public enum RejectionReason {
        AUTHORIZATION_MISMATCH,
        TARGET_ID_CHANGED,
        TARGET_REVISION_CHANGED,
        TARGET_NOT_FOCUSED,
        TARGET_NOT_VISIBLE,
        CLIENT_AREA_CHANGED,
        DPI_CHANGED,
        USER_INPUT_DETECTED,
        COORDINATE_OUTSIDE_BOUNDS
    }

    public record ClientArea(int screenX, int screenY, int width, int height) {
        public ClientArea {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("client width and height must be positive");
            }
        }

        public boolean contains(ScreenPoint point) {
            Objects.requireNonNull(point, "point");
            long rightExclusive = (long) screenX + width;
            long bottomExclusive = (long) screenY + height;
            return point.x() >= screenX && point.x() < rightExclusive
                    && point.y() >= screenY && point.y() < bottomExclusive;
        }
    }

    /**
     * Bounds of the first-to-last board intersections in client coordinates.
     */
    public record BoardBounds(int x, int y, int width, int height) {
        public BoardBounds {
            if (x < 0 || y < 0 || width <= 0 || height <= 0) {
                throw new IllegalArgumentException("board bounds must be positive client coordinates");
            }
        }

        private boolean fitsInside(ClientArea client) {
            return (long) x + width < client.width()
                    && (long) y + height < client.height();
        }

        public boolean containsScreenPoint(ClientArea client, ScreenPoint point) {
            Objects.requireNonNull(client, "client");
            Objects.requireNonNull(point, "point");
            long left = (long) client.screenX() + x;
            long top = (long) client.screenY() + y;
            long right = left + width;
            long bottom = top + height;
            return point.x() >= left && point.x() <= right
                    && point.y() >= top && point.y() <= bottom;
        }
    }

    public record Calibration(String targetId,
                              long targetRevision,
                              ClientArea clientArea,
                              BoardBounds boardBounds,
                              int dpi,
                              Orientation orientation) {
        public Calibration {
            targetId = requireTarget(targetId);
            if (targetRevision < 1) {
                throw new IllegalArgumentException("targetRevision must be positive");
            }
            Objects.requireNonNull(clientArea, "clientArea");
            Objects.requireNonNull(boardBounds, "boardBounds");
            Objects.requireNonNull(orientation, "orientation");
            if (dpi <= 0) {
                throw new IllegalArgumentException("dpi must be positive");
            }
            if (!boardBounds.fitsInside(clientArea)) {
                throw new IllegalArgumentException("board bounds must fit inside client area");
            }
        }
    }

    public record TargetSnapshot(String targetId,
                                 long targetRevision,
                                 ClientArea clientArea,
                                 int dpi,
                                 boolean focused,
                                 boolean visible) {
        public TargetSnapshot {
            targetId = requireTarget(targetId);
            if (targetRevision < 1) {
                throw new IllegalArgumentException("targetRevision must be positive");
            }
            Objects.requireNonNull(clientArea, "clientArea");
            if (dpi <= 0) {
                throw new IllegalArgumentException("dpi must be positive");
            }
        }
    }

    public record ActivitySnapshot(int pointerX, int pointerY, long inputSequence) {
        public ActivitySnapshot {
            if (inputSequence < 0) {
                throw new IllegalArgumentException("inputSequence must not be negative");
            }
        }
    }

    public record Move(int fromFile, int fromRank, int toFile, int toRank) {
        public Move {
            requireSquare(fromFile, fromRank);
            requireSquare(toFile, toRank);
            if (fromFile == toFile && fromRank == toRank) {
                throw new IllegalArgumentException("move source and target must differ");
            }
        }
    }

    public record ScreenPoint(int x, int y) { }

    public record MovePoints(ScreenPoint from, ScreenPoint to) {
        public MovePoints {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }
    }

    public record Rejection(RejectionReason reason, String detail) {
        public Rejection {
            Objects.requireNonNull(reason, "reason");
            if (detail == null || detail.isBlank()) {
                throw new IllegalArgumentException("rejection detail must not be blank");
            }
            detail = detail.trim();
        }
    }

    public static final class MappingResult {
        private final MovePoints points;
        private final Rejection rejection;

        private MappingResult(MovePoints points, Rejection rejection) {
            this.points = points;
            this.rejection = rejection;
        }

        private static MappingResult accepted(MovePoints points) {
            return new MappingResult(Objects.requireNonNull(points, "points"), null);
        }

        private static MappingResult rejected(Rejection rejection) {
            return new MappingResult(null, Objects.requireNonNull(rejection, "rejection"));
        }

        public boolean accepted() {
            return points != null;
        }

        public Optional<MovePoints> points() {
            return Optional.ofNullable(points);
        }

        public Optional<Rejection> rejection() {
            return Optional.ofNullable(rejection);
        }
    }

    private static String requireTarget(String targetId) {
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("targetId must not be blank");
        }
        return targetId.trim();
    }

    private static void requireSquare(int file, int rank) {
        if (file < 0 || file > 8 || rank < 0 || rank > 9) {
            throw new IllegalArgumentException("square must be within files 0-8 and ranks 0-9");
        }
    }
}
