package com.sojourners.chess.linker;

import com.sojourners.chess.automation.BoardCoordinateMapper;
import com.sojourners.chess.config.Properties;
import com.sojourners.chess.jna.User32Extra;
import com.sojourners.chess.linker.profile.ConnectionProfile;
import com.sojourners.chess.linker.profile.ConnectionWizardState;
import com.sojourners.chess.mouse.GlobalMouseListener;
import com.sojourners.chess.mouse.MouseListenCallBack;
import com.sojourners.chess.util.PathUtils;
import com.sojourners.chess.yolo.OnnxModel;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.*;
import com.sun.jna.ptr.IntByReference;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

public class WindowsGraphLinker extends AbstractGraphLinker implements MouseListenCallBack {

    private WinDef.HWND hwnd;
    private GlobalMouseListener listener;
    private double screenScalingFactor;
    private boolean needScaling;
    private WindowsAutomationTarget automationTarget;
    private WindowsMoveCoordinator moveCoordinator;
    private ConnectionProfile approvedProfile;
    private BoardCoordinateMapper.TargetSnapshot approvedTarget;
    private LinkMode sessionMode = LinkMode.safeDefault();
    private final AtomicBoolean selectingTarget = new AtomicBoolean();

    public WindowsGraphLinker(LinkerCallBack callBack) throws AWTException {
        super(callBack);
        this.listener = new GlobalMouseListener(this);
        // 分辨率缩放系数
        this.screenScalingFactor = getScreenScalingFactor();
    }

    @Override
    public void getTargetWindowId() {
        if (!selectingTarget.compareAndSet(false, true)) {
            notifyConnectionConfigurationFailed("已经在等待选择目标窗口");
            return;
        }
        try {
            this.listener.startListenMouse();
            selectCursor();
        } catch (Exception e) {
            selectingTarget.set(false);
            endTargetSelection();
            String detail = e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage();
            notifyConnectionConfigurationFailed("无法开始窗口选择：" + detail);
        }
    }
    @Override
    public void mouseClick() {
        if (!selectingTarget.compareAndSet(true, false)) return;
        try {
            endTargetSelection();
            publishConnectionStatus(ConnectionStatus.State.CONFIGURING,
                    "已选中窗口，正在识别并校准棋盘");

            long[] getPos = new long[1];
            User32Extra.INSTANCE.GetCursorPos(getPos);
            this.hwnd = User32Extra.INSTANCE.WindowFromPoint(getPos[0]);
            WinDef.HWND rootWindow = User32.INSTANCE.GetAncestor(this.hwnd, 2);
            if (rootWindow != null) {
                this.hwnd = rootWindow;
            }

            this.needScaling = needScaling(this.hwnd);

            this.automationTarget = WindowsAutomationTarget.attach(this.hwnd);
            LinkMode requestedMode = connectionMode();
            ConnectionWizardState wizard = new ConnectionWizardState(requestedMode);
            ConnectionWizardState.TargetObservation selected = observeTarget();
            wizard.selectTarget(selected);
            if (findBoardPosition()) {
                BoardCoordinateMapper.BoardBounds detectedBounds = calibrationBoardBounds(
                        boardPosition(), boardFrameWidth(), boardFrameHeight(),
                        selected.clientArea());
                if (requestedMode == LinkMode.READ_ONLY_ADVISOR) {
                    wizard.prepareReadOnlyAdvisor(
                            selected, detectedBounds,
                            preferredScanIntervalMillis(),
                            preferredRecognitionThreads());
                } else {
                    wizard.setBoardBounds(detectedBounds);
                }
            }

            ConnectionProfile approved = requestConnectionConfiguration(wizard);
            if (approved == null) {
                throw new IllegalStateException("连接配置已取消，自动点击保持关闭");
            }
            ConnectionWizardState.TargetObservation current = observeTarget();
            ConnectionWizardState.TargetCheck targetCheck = wizard.observeTarget(current);
            if (targetCheck.recalibrationRequired()) {
                throw new IllegalStateException(targetCheck.message());
            }
            if (!wizard.canEnableAutomation() || !approved.equals(wizard.profile())) {
                throw new IllegalStateException("连接配置尚未完成干运行验证");
            }

            this.approvedProfile = approved;
            this.approvedTarget = currentSnapshot();
            this.sessionMode = requestedMode;
            if (sessionMode.externalInputAllowed()) {
                this.moveCoordinator = new WindowsMoveCoordinator(
                        this.automationTarget, this::recognizeNextVisualFrame);
                if (!this.moveCoordinator.arm()) {
                    throw new IllegalStateException("无法武装已验证的 Windows 目标");
                }
            } else {
                this.moveCoordinator = null;
            }

            publishConnectionStatus(ConnectionStatus.State.SYNCHRONIZING,
                    "校准已通过，正在同步当前局面");
            scan();

        } catch (Exception e) {
            onAutomationStopped();
            publishConnectionStatus(ConnectionStatus.State.STOPPED, "连接未启动");
            String detail = e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage();
            System.err.println("connection configuration stopped: " + detail);
            notifyConnectionConfigurationFailed(detail);
        }

    }

    private boolean needScaling(WinDef.HWND hwnd) {
        // 获取系统DPI
        int systemDpi = User32Extra.INSTANCE.GetDpiForSystem();
        // 通过窗口句柄获取当前窗口的DPI
        int windowDpi = User32Extra.INSTANCE.GetDpiForWindow(hwnd);
        // 比较系统DPI和窗口DPI是否相同，如果不同则需要缩放处理
        return systemDpi != windowDpi;
    }

    @Override
    public Rectangle getTargetWindowPosition() {
        if (automationTarget != null) {
            BoardCoordinateMapper.ClientArea client = automationTarget.currentTarget()
                    .orElseThrow(() -> new IllegalStateException("selected target disappeared"))
                    .clientArea();
            return new Rectangle(
                    (int) Math.round(client.screenX() / screenScalingFactor),
                    (int) Math.round(client.screenY() / screenScalingFactor),
                    (int) Math.round(client.width() / screenScalingFactor),
                    (int) Math.round(client.height() / screenScalingFactor)
            );
        }
        WinDef.RECT rect = new WinDef.RECT();
        User32.INSTANCE.GetWindowRect(hwnd, rect);
        Rectangle rectangle = rect.toRectangle();
        // windows缩放处理
        rectangle.x /= screenScalingFactor;
        rectangle.y /= screenScalingFactor;
        rectangle.width /= screenScalingFactor;
        rectangle.height /= screenScalingFactor;
        return rectangle;
    }

    @Override
    protected boolean executeAuthorizedMove(int fromFile, int fromRow,
                                            int toFile, int toRow,
                                            boolean reversed,
                                            char[][] canonicalPositionBeforeMove,
                                            char[][] visualPositionBeforeMove) {
        if (!sessionMode.externalInputAllowed()) return false;
        WindowsAutomationTarget targetAdapter = automationTarget;
        WindowsMoveCoordinator coordinator = moveCoordinator;
        ConnectionProfile profile = approvedProfile;
        BoardCoordinateMapper.TargetSnapshot targetAtApproval = approvedTarget;
        if (targetAdapter == null || coordinator == null
                || profile == null || targetAtApproval == null) {
            return false;
        }
        try {
            BoardCoordinateMapper.Orientation recognizedOrientation = reversed
                    ? BoardCoordinateMapper.Orientation.BLACK_AT_BOTTOM
                    : BoardCoordinateMapper.Orientation.RED_AT_BOTTOM;
            if (profile.orientation() != recognizedOrientation) {
                coordinator.stop("configured orientation no longer matches recognition");
                return false;
            }
            BoardCoordinateMapper.Calibration calibration = new BoardCoordinateMapper.Calibration(
                    targetAdapter.authorization().targetId(),
                    targetAdapter.authorization().targetRevision(),
                    targetAtApproval.clientArea(),
                    profile.boardBounds(),
                    profile.dpi(),
                    profile.orientation()
            );
            BoardCoordinateMapper.Move canonicalMove = new BoardCoordinateMapper.Move(
                    fromFile, 9 - fromRow, toFile, 9 - toRow);
            MoveConfirmationTracker.GridMove visualMove = reversed
                    ? new MoveConfirmationTracker.GridMove(
                            8 - fromFile, 9 - fromRow, 8 - toFile, 9 - toRow)
                    : new MoveConfirmationTracker.GridMove(
                            fromFile, fromRow, toFile, toRow);
            WindowsMoveCoordinator.ExecutionOutcome outcome =
                    coordinator.executeAndConfirm(
                            calibration,
                            canonicalPositionBeforeMove,
                            visualPositionBeforeMove,
                            canonicalMove,
                            visualMove,
                            profile.clickDelayMillis(),
                            profile.moveDelayMillis(),
                            3_000
                    );
            if (!outcome.confirmed()) {
                System.err.println("automation paused: " + outcome.detail());
                publishConnectionStatus(ConnectionStatus.State.PAUSED,
                        "走子确认失败，连接已暂停");
            }
            return outcome.confirmed();
        } catch (RuntimeException failure) {
            coordinator.stop("automation calibration failed: "
                    + (failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage()));
            publishConnectionStatus(ConnectionStatus.State.PAUSED,
                    "校准状态变化，连接已暂停");
            return false;
        }
    }

    static BoardCoordinateMapper.BoardBounds calibrationBoardBounds(
            Rectangle recognizedBoard,
            int frameWidth,
            int frameHeight,
            BoardCoordinateMapper.ClientArea client) {
        if (recognizedBoard == null || recognizedBoard.width <= 0 || recognizedBoard.height <= 0
                || frameWidth <= 0 || frameHeight <= 0) {
            throw new IllegalArgumentException("recognized board and frame dimensions are required");
        }
        double cellWidth = recognizedBoard.width / (8 + OnnxModel.PADDING * 2);
        double cellHeight = recognizedBoard.height / (9 + OnnxModel.PADDING * 2);
        double firstX = recognizedBoard.x + (OnnxModel.PADDING + 0.2) * cellWidth;
        double lastX = recognizedBoard.x + (OnnxModel.PADDING + 8 - 0.2) * cellWidth;
        double firstY = recognizedBoard.y + (OnnxModel.PADDING + 0.2) * cellHeight;
        double lastY = recognizedBoard.y + (OnnxModel.PADDING + 9 - 0.2) * cellHeight;
        double scaleX = client.width() / (double) frameWidth;
        double scaleY = client.height() / (double) frameHeight;
        int left = (int) Math.round(firstX * scaleX);
        int top = (int) Math.round(firstY * scaleY);
        int right = (int) Math.round(lastX * scaleX);
        int bottom = (int) Math.round(lastY * scaleY);
        return new BoardCoordinateMapper.BoardBounds(left, top, right - left, bottom - top);
    }

    @Override
    protected void onAutomationStopped() {
        if (selectingTarget.getAndSet(false)) {
            endTargetSelection();
        }
        if (moveCoordinator != null) {
            moveCoordinator.stop("graph link stopped");
        }
        moveCoordinator = null;
        approvedProfile = null;
        approvedTarget = null;
        sessionMode = LinkMode.safeDefault();
    }

    private void endTargetSelection() {
        try {
            listener.stopListenMouse();
        } catch (Exception failure) {
            System.err.println("could not stop target selection listener: "
                    + (failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage()));
        } finally {
            restoreCursor();
        }
    }

    private int preferredScanIntervalMillis() {
        long configured = Properties.getInstance().getLinkScanTime();
        return configured >= 20 && configured <= 10_000
                ? (int) configured : 100;
    }

    private int preferredRecognitionThreads() {
        int configured = Properties.getInstance().getLinkThreadNum();
        return configured >= 1 && configured <= 64 ? configured : 2;
    }

    private BoardCoordinateMapper.TargetSnapshot currentSnapshot() {
        return automationTarget.currentTarget()
                .orElseThrow(() -> new IllegalStateException("选定窗口已经消失"));
    }

    private ConnectionWizardState.TargetObservation observeTarget() {
        BoardCoordinateMapper.TargetSnapshot snapshot = currentSnapshot();
        IntByReference processId = new IntByReference();
        User32.INSTANCE.GetWindowThreadProcessId(hwnd, processId);
        String executablePath = executablePath(processId.getValue());
        String executableName = executableName(executablePath, processId.getValue());
        String windowClass = windowClassName();
        String title = windowTitle();
        if (title.isBlank()) title = executableName;
        return new ConnectionWizardState.TargetObservation(
                snapshot.targetId(), snapshot.targetRevision(), executableName,
                windowClass, title, executablePath, snapshot.clientArea(),
                snapshot.dpi(), snapshot.focused(), snapshot.visible());
    }

    private String executablePath(int processId) {
        WinNT.HANDLE process = Kernel32.INSTANCE.OpenProcess(
                WinNT.PROCESS_QUERY_LIMITED_INFORMATION, false, processId);
        if (process == null) return "";
        try {
            char[] path = new char[4_096];
            IntByReference length = new IntByReference(path.length);
            return Kernel32.INSTANCE.QueryFullProcessImageName(process, 0, path, length)
                    ? new String(path, 0, length.getValue()) : "";
        } finally {
            Kernel32.INSTANCE.CloseHandle(process);
        }
    }

    private String executableName(String executablePath, int processId) {
        if (!executablePath.isBlank()) {
            try {
                Path name = Path.of(executablePath).getFileName();
                if (name != null && !name.toString().isBlank()) return name.toString();
            } catch (InvalidPathException ignored) { }
        }
        return "process-" + Integer.toUnsignedLong(processId) + ".exe";
    }

    private String windowClassName() {
        char[] text = new char[512];
        int length = User32.INSTANCE.GetClassName(hwnd, text, text.length);
        return length > 0 ? Native.toString(text) : "unknown-window-class";
    }

    private String windowTitle() {
        int capacity = Math.min(Math.max(User32.INSTANCE.GetWindowTextLength(hwnd) + 1, 2), 512);
        char[] text = new char[capacity];
        int length = User32.INSTANCE.GetWindowText(hwnd, text, text.length);
        return length > 0 ? Native.toString(text) : "";
    }

    private double getScreenScalingFactor() {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice gd = ge.getDefaultScreenDevice();
        return gd.getDefaultConfiguration().getDefaultTransform().getScaleX();
    }

    @Override
    public BufferedImage screenshotByBack(Rectangle windowPos) {
        return capture(this.hwnd, windowPos);
    }

    private BufferedImage capture(WinDef.HWND hWnd, Rectangle rect) {
        // 创建与窗口相关联的设备上下文和一个内存设备上下文以执行离屏渲染
        WinDef.HDC hdcWindow = User32.INSTANCE.GetDC(hWnd);
        WinDef.HDC hdcMemDC = GDI32.INSTANCE.CreateCompatibleDC(hdcWindow);
        try {
            int width, height;
            WinDef.RECT bounds = new WinDef.RECT();
            User32.INSTANCE.GetClientRect(hWnd, bounds);
            width = bounds.right - bounds.left;
            height = bounds.bottom - bounds.top;
            // 处理windows缩放问题
            if (needScaling) {
                width /= screenScalingFactor;
                height /= screenScalingFactor;
            }
            // 创建兼容的位图，并且将其选入内存设备上下文
            WinDef.HBITMAP hBitmap = GDI32.INSTANCE.CreateCompatibleBitmap(hdcWindow, width, height);
            WinNT.HANDLE hOld = GDI32.INSTANCE.SelectObject(hdcMemDC, hBitmap);
            // 请求窗口自行完成绘制工作
            if (!User32.INSTANCE.PrintWindow(hWnd, hdcMemDC, 0x1 | 0x2)) {
                return null;
            }

            // 将所绘制的位图转化为Java缓冲图片（BufferedImage）
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            WinGDI.BITMAPINFO bmi = new WinGDI.BITMAPINFO();
            bmi.bmiHeader.biWidth = width;
            bmi.bmiHeader.biHeight = -height; // 注意：biHeight为负表示顶向下DIB
            bmi.bmiHeader.biPlanes = 1;
            bmi.bmiHeader.biBitCount = 32;
            bmi.bmiHeader.biCompression = WinGDI.BI_RGB;

            Memory buffer = new Memory(width * height * 4);
            GDI32.INSTANCE.GetDIBits(hdcMemDC, hBitmap, 0, height, buffer, bmi, WinGDI.DIB_RGB_COLORS);

            int[] data = buffer.getIntArray(0, width * height);
            image.setRGB(0, 0, width, height, data, 0, width);

            // 清理资源
            GDI32.INSTANCE.SelectObject(hdcMemDC, hOld);
            GDI32.INSTANCE.DeleteObject(hBitmap);

            if (rect != null) {
                width = (int) rect.getWidth();
                height = (int) rect.getHeight();
                int x = rect.x;
                int y = rect.y;
                image = image.getSubimage(x, y, width, height);
            }

            return image;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            // 清理设备上下文对象
            GDI32.INSTANCE.DeleteDC(hdcMemDC);
            User32.INSTANCE.ReleaseDC(hWnd, hdcWindow);
        }
    }

    private void selectCursor() {
        WinDef.HCURSOR h = User32Extra.INSTANCE.LoadCursorFromFileA(PathUtils.getJarPath() + "ui/circle.ico");
        User32Extra.INSTANCE.SetSystemCursor(h, new WinDef.DWORD(32512));
    }

    private void restoreCursor() {
        User32Extra.INSTANCE.SystemParametersInfoA(87, 0, 0, 2);
    }
}
