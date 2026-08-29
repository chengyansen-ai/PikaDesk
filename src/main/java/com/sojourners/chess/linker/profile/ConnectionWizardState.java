package com.sojourners.chess.linker.profile;

import com.sojourners.chess.automation.AutomationSafetyKernel;
import com.sojourners.chess.automation.BoardCoordinateMapper;
import com.sojourners.chess.linker.LinkMode;

import java.util.Objects;

/**
 * Fail-closed configuration wizard. It can only dry-run coordinate mapping and
 * deliberately has no low-level input or process-control API.
 */
public final class ConnectionWizardState {

    private final BoardCoordinateMapper mapper = new BoardCoordinateMapper();
    private final LinkMode connectionMode;
    private Step step = Step.TARGET;
    private String profileName = "本地棋盘";
    private ConnectionProfile importedTemplate;
    private TargetObservation target;
    private BoardCoordinateMapper.BoardBounds boardBounds;
    private BoardCoordinateMapper.Orientation orientation;
    private String themeId;
    private String modelId;
    private int scanIntervalMillis;
    private int recognitionThreads;
    private int clickDelayMillis;
    private int moveDelayMillis;
    private boolean dryRunVerified;

    public ConnectionWizardState() {
        this(LinkMode.safeDefault());
    }

    public ConnectionWizardState(LinkMode connectionMode) {
        this.connectionMode = Objects.requireNonNull(connectionMode, "connectionMode");
    }

    public static ConnectionWizardState fromImported(ConnectionProfile profile) {
        ConnectionWizardState state = new ConnectionWizardState();
        state.importedTemplate = Objects.requireNonNull(profile, "profile");
        state.profileName = profile.name();
        state.boardBounds = profile.boardBounds();
        state.orientation = profile.orientation();
        state.themeId = profile.themeId();
        state.modelId = profile.modelId();
        state.scanIntervalMillis = profile.scanIntervalMillis();
        state.recognitionThreads = profile.recognitionThreads();
        state.clickDelayMillis = profile.clickDelayMillis();
        state.moveDelayMillis = profile.moveDelayMillis();
        state.dryRunVerified = false;
        state.step = Step.TARGET;
        return state;
    }

    public void setProfileName(String profileName) {
        String safe = Objects.requireNonNull(profileName, "profileName").trim();
        if (safe.isEmpty() || safe.length() > 80) {
            throw new IllegalArgumentException("profile name must contain 1 to 80 characters");
        }
        this.profileName = safe;
    }

    public void selectTarget(TargetObservation selected) {
        TargetObservation candidate = Objects.requireNonNull(selected, "selected");
        AutomationTargetPolicy.requirePermitted(candidate);
        target = candidate;
        dryRunVerified = false;
        if (importedTemplate != null && reusableFor(importedTemplate, selected)) {
            boardBounds = importedTemplate.boardBounds();
            orientation = importedTemplate.orientation();
            themeId = importedTemplate.themeId();
            modelId = importedTemplate.modelId();
            scanIntervalMillis = importedTemplate.scanIntervalMillis();
            recognitionThreads = importedTemplate.recognitionThreads();
            clickDelayMillis = importedTemplate.clickDelayMillis();
            moveDelayMillis = importedTemplate.moveDelayMillis();
            step = Step.TEST;
        } else {
            boardBounds = null;
            orientation = null;
            themeId = null;
            modelId = null;
            step = Step.BOARD;
        }
    }

    public void setBoardBounds(BoardCoordinateMapper.BoardBounds bounds) {
        requireTarget();
        new BoardCoordinateMapper.Calibration(
                target.targetId(), target.targetRevision(), target.clientArea(),
                Objects.requireNonNull(bounds, "bounds"), target.dpi(),
                BoardCoordinateMapper.Orientation.RED_AT_BOTTOM);
        boardBounds = bounds;
        orientation = null;
        themeId = null;
        modelId = null;
        dryRunVerified = false;
        step = Step.DISPLAY;
    }

    public void setDisplay(BoardCoordinateMapper.Orientation selectedOrientation,
                           String selectedThemeId) {
        if (target == null || boardBounds == null) {
            throw new IllegalStateException("select target and board bounds first");
        }
        orientation = Objects.requireNonNull(selectedOrientation, "selectedOrientation");
        themeId = identifier(selectedThemeId, "themeId");
        modelId = null;
        dryRunVerified = false;
        step = Step.MODEL;
    }

    public void setModelAndTiming(String selectedModelId,
                                  int selectedScanIntervalMillis,
                                  int selectedRecognitionThreads,
                                  int selectedClickDelayMillis,
                                  int selectedMoveDelayMillis) {
        if (target == null || boardBounds == null
                || orientation == null || themeId == null) {
            throw new IllegalStateException("complete target, board, and display steps first");
        }
        String candidateModelId = identifier(selectedModelId, "modelId");
        RecognitionCompatibility.requireVerified(themeId, candidateModelId);
        modelId = candidateModelId;
        scanIntervalMillis = range(
                selectedScanIntervalMillis, 20, 10_000, "scanIntervalMillis");
        recognitionThreads = range(
                selectedRecognitionThreads, 1, 64, "recognitionThreads");
        clickDelayMillis = range(
                selectedClickDelayMillis, 0, 2_000, "clickDelayMillis");
        moveDelayMillis = range(
                selectedMoveDelayMillis, 0, 2_000, "moveDelayMillis");
        dryRunVerified = false;
        step = Step.TEST;
    }

    /**
     * Completes the coordinate-only read-only preflight from freshly detected
     * model bounds. This path never carries click or move delays and is not
     * available to an input-capable connection.
     */
    public BoardCoordinateMapper.MovePoints prepareReadOnlyAdvisor(
            TargetObservation current,
            BoardCoordinateMapper.BoardBounds detectedBounds,
            int selectedScanIntervalMillis,
            int selectedRecognitionThreads) {
        if (connectionMode != LinkMode.READ_ONLY_ADVISOR) {
            throw new IllegalStateException(
                    "read-only preflight is unavailable to input-capable connections");
        }
        setBoardBounds(Objects.requireNonNull(detectedBounds, "detectedBounds"));
        setDisplay(BoardCoordinateMapper.Orientation.RED_AT_BOTTOM,
                RecognitionCompatibility.CLASSIC_THEME);
        setModelAndTiming(RecognitionCompatibility.XIANGQI_YOLO11_MODEL,
                selectedScanIntervalMillis, selectedRecognitionThreads, 0, 0);
        return verifyMapping(current,
                new BoardCoordinateMapper.Move(0, 0, 0, 1), false);
    }

    public BoardCoordinateMapper.MovePoints verifyDryRun(
            TargetObservation current,
            BoardCoordinateMapper.Move sampleMove) {
        return verifyMapping(current, sampleMove, true);
    }

    private BoardCoordinateMapper.MovePoints verifyMapping(
            TargetObservation current,
            BoardCoordinateMapper.Move sampleMove,
            boolean requireFocus) {
        if (step != Step.TEST || target == null || boardBounds == null
                || orientation == null || modelId == null) {
            throw new IllegalStateException("wizard is not ready for dry-run verification");
        }
        RecognitionCompatibility.requireVerified(themeId, modelId);
        TargetCheck check = checkTarget(Objects.requireNonNull(current, "current"));
        if (check.recalibrationRequired()) {
            step = Step.RECALIBRATION_REQUIRED;
            dryRunVerified = false;
            throw new IllegalStateException(check.message());
        }
        if (!current.visible() || requireFocus && !current.focused()) {
            throw new IllegalStateException(requireFocus
                    ? "测试前目标窗口必须可见并获得焦点"
                    : "只读预校准前目标窗口必须可见");
        }
        BoardCoordinateMapper.Calibration calibration = calibration();
        BoardCoordinateMapper.MappingResult result;
        if (requireFocus) {
            AutomationSafetyKernel.Authorization authorization =
                    new AutomationSafetyKernel.Authorization(
                            target.targetId(), target.targetRevision());
            BoardCoordinateMapper.TargetSnapshot snapshot = current.snapshot();
            BoardCoordinateMapper.ActivitySnapshot activity =
                    new BoardCoordinateMapper.ActivitySnapshot(0, 0, 0);
            result = mapper.mapMove(
                    authorization, calibration, snapshot, activity, activity,
                    Objects.requireNonNull(sampleMove, "sampleMove"));
        } else {
            result = mapper.previewMove(
                    calibration, Objects.requireNonNull(sampleMove, "sampleMove"));
        }
        if (!result.accepted()) {
            BoardCoordinateMapper.Rejection rejection = result.rejection().orElseThrow();
            throw new IllegalStateException(
                    "干运行坐标验证失败：" + rejection.reason() + " " + rejection.detail());
        }
        dryRunVerified = true;
        step = Step.READY;
        return result.points().orElseThrow();
    }

    public TargetCheck observeTarget(TargetObservation current) {
        requireTarget();
        TargetCheck check = checkTarget(Objects.requireNonNull(current, "current"));
        if (check.recalibrationRequired()) {
            dryRunVerified = false;
            step = Step.RECALIBRATION_REQUIRED;
        }
        return check;
    }

    public boolean canEnableAutomation() {
        return step == Step.READY && dryRunVerified
                && RecognitionCompatibility.isVerified(themeId, modelId);
    }

    public ConnectionProfile profile() {
        if (!canEnableAutomation()) {
            throw new IllegalStateException("wizard is not complete and verified");
        }
        return new ConnectionProfile(
                ConnectionProfile.CURRENT_VERSION,
                profileName,
                new ConnectionProfile.TargetDescriptor(
                        target.executableName(), target.windowClassName(),
                        target.titleHint(), target.localExecutablePath(),
                        target.clientArea().width(), target.clientArea().height()),
                boardBounds, target.dpi(), orientation, themeId, modelId,
                scanIntervalMillis, recognitionThreads,
                clickDelayMillis, moveDelayMillis, true);
    }

    public Step step() {
        return step;
    }

    public LinkMode connectionMode() {
        return connectionMode;
    }

    public String profileName() {
        return profileName;
    }

    public TargetObservation target() {
        return target;
    }

    public BoardCoordinateMapper.BoardBounds boardBounds() {
        return boardBounds;
    }

    public BoardCoordinateMapper.Orientation orientation() {
        return orientation;
    }

    public String themeId() {
        return themeId;
    }

    public String modelId() {
        return modelId;
    }

    public int scanIntervalMillis() {
        return scanIntervalMillis;
    }

    public int recognitionThreads() {
        return recognitionThreads;
    }

    public int clickDelayMillis() {
        return clickDelayMillis;
    }

    public int moveDelayMillis() {
        return moveDelayMillis;
    }

    private TargetCheck checkTarget(TargetObservation current) {
        boolean changed = !target.targetId().equals(current.targetId())
                || target.targetRevision() != current.targetRevision()
                || !target.executableName().equalsIgnoreCase(current.executableName())
                || !target.windowClassName().equals(current.windowClassName())
                || !target.clientArea().equals(current.clientArea())
                || target.dpi() != current.dpi();
        return changed
                ? new TargetCheck(true, "目标身份、窗口尺寸或 DPI 已变化，请重新校准。")
                : new TargetCheck(false, "目标与已验证校准一致。");
    }

    private BoardCoordinateMapper.Calibration calibration() {
        return new BoardCoordinateMapper.Calibration(
                target.targetId(), target.targetRevision(), target.clientArea(),
                boardBounds, target.dpi(), orientation);
    }

    private boolean reusableFor(ConnectionProfile profile, TargetObservation selected) {
        ConnectionProfile.TargetDescriptor descriptor = profile.target();
        return descriptor.executableName().equalsIgnoreCase(selected.executableName())
                && descriptor.windowClassName().equals(selected.windowClassName())
                && descriptor.clientWidth() == selected.clientArea().width()
                && descriptor.clientHeight() == selected.clientArea().height()
                && profile.dpi() == selected.dpi();
    }

    private void requireTarget() {
        if (target == null) throw new IllegalStateException("select a target window first");
    }

    private static int range(int value, int min, int max, String field) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(field + " must be between " + min + " and " + max);
        }
        return value;
    }

    private static String identifier(String value, String field) {
        String safe = Objects.requireNonNull(value, field).trim();
        if (safe.isEmpty() || safe.length() > 128
                || !safe.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(field + " contains unsupported characters");
        }
        return safe;
    }

    public enum Step {
        TARGET,
        BOARD,
        DISPLAY,
        MODEL,
        TEST,
        READY,
        RECALIBRATION_REQUIRED
    }

    public record TargetCheck(boolean recalibrationRequired, String message) {
        public TargetCheck {
            Objects.requireNonNull(message, "message");
        }
    }

    public record TargetObservation(String targetId,
                                    long targetRevision,
                                    String executableName,
                                    String windowClassName,
                                    String titleHint,
                                    String localExecutablePath,
                                    BoardCoordinateMapper.ClientArea clientArea,
                                    int dpi,
                                    boolean focused,
                                    boolean visible) {
        public TargetObservation {
            targetId = bounded(targetId, "targetId", 512);
            if (targetRevision < 1) {
                throw new IllegalArgumentException("targetRevision must be positive");
            }
            executableName = bounded(executableName, "executableName", 260);
            windowClassName = bounded(windowClassName, "windowClassName", 256);
            titleHint = bounded(titleHint, "titleHint", 256);
            localExecutablePath = Objects.requireNonNull(
                    localExecutablePath, "localExecutablePath").trim();
            if (localExecutablePath.length() > 4_096) {
                throw new IllegalArgumentException("localExecutablePath exceeds limit");
            }
            Objects.requireNonNull(clientArea, "clientArea");
            range(dpi, 48, 960, "dpi");
        }

        private BoardCoordinateMapper.TargetSnapshot snapshot() {
            return new BoardCoordinateMapper.TargetSnapshot(
                    targetId, targetRevision, clientArea, dpi, focused, visible);
        }
    }

    private static String bounded(String value, String field, int maxLength) {
        String safe = Objects.requireNonNull(value, field).trim();
        if (safe.isEmpty() || safe.length() > maxLength) {
            throw new IllegalArgumentException(field + " must contain 1 to "
                    + maxLength + " characters");
        }
        return safe;
    }
}
