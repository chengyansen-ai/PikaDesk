package com.sojourners.chess.linker;

import com.sojourners.chess.automation.AutomationSafetyKernel;
import com.sojourners.chess.automation.BoardCoordinateMapper;
import com.sojourners.chess.jna.User32Extra;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded Windows adapter for one explicitly selected window handle. It never
 * discovers or changes the target on its own, and it revalidates the target and
 * user activity immediately before every low-level window message.
 */
public final class WindowsAutomationTarget {

    private static final int WM_MOUSEMOVE = 0x0200;
    private static final int WM_LBUTTONDOWN = 0x0201;
    private static final int WM_LBUTTONUP = 0x0202;
    private static final int MK_LBUTTON = 0x0001;
    private static final int SEND_TIMEOUT_MILLIS = 100;
    private static final int MAX_DELAY_MILLIS = 2_000;

    private final WinDef.HWND handle;
    private final NativeBridge bridge;
    private final Delay delay;
    private final AutomationSafetyKernel.Authorization authorization;

    public static WindowsAutomationTarget attach(WinDef.HWND selectedHandle) {
        return new WindowsAutomationTarget(selectedHandle, new JnaNativeBridge(), Thread::sleep);
    }

    WindowsAutomationTarget(WinDef.HWND selectedHandle, NativeBridge bridge, Delay delay) {
        this.handle = requireHandle(selectedHandle);
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.delay = Objects.requireNonNull(delay, "delay");
        WindowObservation initial = Objects.requireNonNull(
                bridge.inspect(handle), "initial window observation");
        if (!initial.valid()) {
            throw new IllegalArgumentException("selected window handle is not valid");
        }
        this.authorization = new AutomationSafetyKernel.Authorization(
                targetId(handle, initial.processId()), revision(initial.windowThreadId()));
    }

    public AutomationSafetyKernel.Authorization authorization() {
        return authorization;
    }

    public Optional<BoardCoordinateMapper.TargetSnapshot> currentTarget() {
        WindowObservation current = bridge.inspect(handle);
        if (current == null || !current.valid()) {
            return Optional.empty();
        }
        return Optional.of(new BoardCoordinateMapper.TargetSnapshot(
                targetId(handle, current.processId()),
                revision(current.windowThreadId()),
                current.clientArea(),
                current.dpi(),
                current.focused(),
                current.visible()
        ));
    }

    public BoardCoordinateMapper.ActivitySnapshot currentActivity() {
        return Objects.requireNonNull(bridge.activity(), "activity snapshot");
    }

    /**
     * Emits at most one source/target click pair. Success leaves the safety
     * kernel in CONFIRMING; visual confirmation is deliberately a later stage.
     */
    public boolean executeOne(AutomationSafetyKernel kernel,
                              BoardCoordinateMapper mapper,
                              BoardCoordinateMapper.Calibration calibration,
                              BoardCoordinateMapper.ActivitySnapshot activityAtReady,
                              BoardCoordinateMapper.Move move,
                              int clickDelayMillis,
                              int moveDelayMillis) {
        Objects.requireNonNull(kernel, "kernel");
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(calibration, "calibration");
        Objects.requireNonNull(activityAtReady, "activityAtReady");
        Objects.requireNonNull(move, "move");
        requireDelay(clickDelayMillis, "clickDelayMillis");
        requireDelay(moveDelayMillis, "moveDelayMillis");

        return kernel.executeOne(permit -> {
            sendValidated(kernel, permit, mapper, calibration, activityAtReady,
                    move, true, WM_MOUSEMOVE, 0);
            sendValidated(kernel, permit, mapper, calibration, activityAtReady,
                    move, true, WM_LBUTTONDOWN, MK_LBUTTON);
            waitOutsidePermit(clickDelayMillis);
            sendValidated(kernel, permit, mapper, calibration, activityAtReady,
                    move, true, WM_LBUTTONUP, 0);
            waitOutsidePermit(moveDelayMillis);
            sendValidated(kernel, permit, mapper, calibration, activityAtReady,
                    move, false, WM_MOUSEMOVE, 0);
            sendValidated(kernel, permit, mapper, calibration, activityAtReady,
                    move, false, WM_LBUTTONDOWN, MK_LBUTTON);
            waitOutsidePermit(clickDelayMillis);
            sendValidated(kernel, permit, mapper, calibration, activityAtReady,
                    move, false, WM_LBUTTONUP, 0);
        });
    }

    private void sendValidated(AutomationSafetyKernel kernel,
                               AutomationSafetyKernel.EventPermit permit,
                               BoardCoordinateMapper mapper,
                               BoardCoordinateMapper.Calibration calibration,
                               BoardCoordinateMapper.ActivitySnapshot activityAtReady,
                               BoardCoordinateMapper.Move move,
                               boolean source,
                               int message,
                               int flags) throws Exception {
        if (!permit.send(() -> {
            ValidatedMove validated = validate(
                    kernel, mapper, calibration, activityAtReady, move);
            BoardCoordinateMapper.ScreenPoint point = source
                    ? validated.points().from() : validated.points().to();
            MouseEvent event = toClientEvent(
                    validated.target().clientArea(), point, message, flags);
            if (!bridge.send(handle, event, SEND_TIMEOUT_MILLIS)) {
                throw new IOException("Windows message timed out or failed");
            }
        })) {
            throw new SequenceHalted();
        }
    }

    private ValidatedMove validate(
            AutomationSafetyKernel kernel,
            BoardCoordinateMapper mapper,
            BoardCoordinateMapper.Calibration calibration,
            BoardCoordinateMapper.ActivitySnapshot activityAtReady,
            BoardCoordinateMapper.Move move) throws SequenceHalted {
        Optional<AutomationSafetyKernel.Authorization> activeAuthorization = kernel.authorization();
        if (activeAuthorization.isEmpty()) {
            throw halt(kernel, "AUTHORIZATION_MISSING: authorization was cleared");
        }
        Optional<BoardCoordinateMapper.TargetSnapshot> currentTarget = currentTarget();
        if (currentTarget.isEmpty()) {
            throw halt(kernel, "TARGET_UNAVAILABLE: selected window disappeared");
        }
        BoardCoordinateMapper.MappingResult result = mapper.mapMove(
                activeAuthorization.orElseThrow(),
                calibration,
                currentTarget.orElseThrow(),
                activityAtReady,
                currentActivity(),
                move
        );
        if (!result.accepted()) {
            BoardCoordinateMapper.Rejection rejection = result.rejection().orElseThrow();
            throw halt(kernel, rejection.reason() + ": " + rejection.detail());
        }
        return new ValidatedMove(result.points().orElseThrow(), currentTarget.orElseThrow());
    }

    private MouseEvent toClientEvent(BoardCoordinateMapper.ClientArea client,
                                     BoardCoordinateMapper.ScreenPoint point,
                                     int message,
                                     int flags) throws IOException {
        if (!client.contains(point)) {
            throw new IOException("mapped point left the current client area");
        }
        long clientX = (long) point.x() - client.screenX();
        long clientY = (long) point.y() - client.screenY();
        if (clientX < 0 || clientX > Short.MAX_VALUE
                || clientY < 0 || clientY > Short.MAX_VALUE) {
            throw new IOException("client coordinates exceed Windows message limits");
        }
        return new MouseEvent(message, flags, (int) clientX, (int) clientY);
    }

    private void waitOutsidePermit(int millis) throws InterruptedException {
        if (millis > 0) {
            try {
                delay.waitFor(millis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
        }
    }

    private static SequenceHalted halt(AutomationSafetyKernel kernel, String reason) {
        kernel.validationFailed(reason);
        return new SequenceHalted();
    }

    private static WinDef.HWND requireHandle(WinDef.HWND handle) {
        if (handle == null || handle.getPointer() == null
                || Pointer.nativeValue(handle.getPointer()) == 0) {
            throw new IllegalArgumentException("selected window handle is required");
        }
        return handle;
    }

    private static String targetId(WinDef.HWND handle, int processId) {
        long nativeValue = Pointer.nativeValue(handle.getPointer());
        return "windows:pid=" + Integer.toUnsignedLong(processId)
                + ";hwnd=0x" + Long.toHexString(nativeValue);
    }

    private static long revision(int windowThreadId) {
        long revision = Integer.toUnsignedLong(windowThreadId);
        if (revision < 1) {
            throw new IllegalArgumentException("window thread id must be positive");
        }
        return revision;
    }

    private static void requireDelay(int delayMillis, String name) {
        if (delayMillis < 0 || delayMillis > MAX_DELAY_MILLIS) {
            throw new IllegalArgumentException(name + " must be between 0 and 2000");
        }
    }

    record WindowObservation(boolean valid,
                             int processId,
                             int windowThreadId,
                             BoardCoordinateMapper.ClientArea clientArea,
                             int dpi,
                             boolean focused,
                             boolean visible) {
        WindowObservation {
            if (valid) {
                if (processId == 0 || windowThreadId == 0) {
                    throw new IllegalArgumentException("valid window IDs must be non-zero");
                }
                Objects.requireNonNull(clientArea, "clientArea");
                if (dpi <= 0) {
                    throw new IllegalArgumentException("valid window DPI must be positive");
                }
            }
        }

        private static WindowObservation unavailable() {
            return new WindowObservation(false, 0, 0, null, 0, false, false);
        }

        WindowObservation withFocused(boolean newFocused) {
            return new WindowObservation(valid, processId, windowThreadId,
                    clientArea, dpi, newFocused, visible);
        }
    }

    record MouseEvent(int message, int flags, int clientX, int clientY) { }

    private record ValidatedMove(BoardCoordinateMapper.MovePoints points,
                                 BoardCoordinateMapper.TargetSnapshot target) { }

    interface NativeBridge {
        WindowObservation inspect(WinDef.HWND handle);

        BoardCoordinateMapper.ActivitySnapshot activity();

        boolean send(WinDef.HWND handle, MouseEvent event, int timeoutMillis);
    }

    @FunctionalInterface
    interface Delay {
        void waitFor(long millis) throws InterruptedException;
    }

    private static final class JnaNativeBridge implements NativeBridge {

        private static final int SMTO_ABORTIFHUNG = 0x0002;

        @Override
        public WindowObservation inspect(WinDef.HWND handle) {
            if (!User32.INSTANCE.IsWindow(handle)) {
                return WindowObservation.unavailable();
            }
            WinDef.RECT clientRect = new WinDef.RECT();
            WinDef.POINT clientOrigin = new WinDef.POINT();
            IntByReference processId = new IntByReference();
            int windowThreadId = User32.INSTANCE.GetWindowThreadProcessId(handle, processId);
            if (windowThreadId == 0
                    || !User32.INSTANCE.GetClientRect(handle, clientRect)
                    || !User32Extra.INSTANCE.ClientToScreen(handle, clientOrigin)) {
                return WindowObservation.unavailable();
            }
            int width = clientRect.right - clientRect.left;
            int height = clientRect.bottom - clientRect.top;
            int dpi = User32Extra.INSTANCE.GetDpiForWindow(handle);
            if (width <= 0 || height <= 0 || dpi <= 0) {
                return WindowObservation.unavailable();
            }
            BoardCoordinateMapper.ClientArea client = new BoardCoordinateMapper.ClientArea(
                    clientOrigin.x, clientOrigin.y, width, height);
            return new WindowObservation(
                    true,
                    processId.getValue(),
                    windowThreadId,
                    client,
                    dpi,
                    sameHandle(User32.INSTANCE.GetForegroundWindow(), handle),
                    User32.INSTANCE.IsWindowVisible(handle)
            );
        }

        @Override
        public BoardCoordinateMapper.ActivitySnapshot activity() {
            WinDef.POINT pointer = new WinDef.POINT();
            WinUser.LASTINPUTINFO lastInput = new WinUser.LASTINPUTINFO();
            lastInput.cbSize = lastInput.size();
            if (!User32.INSTANCE.GetCursorPos(pointer)
                    || !User32.INSTANCE.GetLastInputInfo(lastInput)) {
                throw new IllegalStateException("could not read Windows user activity");
            }
            return new BoardCoordinateMapper.ActivitySnapshot(
                    pointer.x, pointer.y, Integer.toUnsignedLong(lastInput.dwTime));
        }

        @Override
        public boolean send(WinDef.HWND handle, MouseEvent event, int timeoutMillis) {
            int packedPoint = (event.clientY() << 16) | (event.clientX() & 0xFFFF);
            WinDef.LRESULT sent = User32.INSTANCE.SendMessageTimeout(
                    handle,
                    event.message(),
                    new WinDef.WPARAM(event.flags()),
                    new WinDef.LPARAM(packedPoint),
                    SMTO_ABORTIFHUNG,
                    timeoutMillis,
                    new WinDef.DWORDByReference()
            );
            return sent != null && sent.longValue() != 0;
        }

        private boolean sameHandle(WinDef.HWND first, WinDef.HWND second) {
            return first != null && second != null
                    && first.getPointer() != null && second.getPointer() != null
                    && Pointer.nativeValue(first.getPointer())
                    == Pointer.nativeValue(second.getPointer());
        }
    }

    private static final class SequenceHalted extends Exception { }
}
