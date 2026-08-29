package com.sojourners.chess.linker;

import com.sojourners.chess.jna.User32Extra;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Enumerates generic visible Windows targets without application-specific rules. */
final class WindowsWindowCatalog {

    private static final int NATIVE_ENUMERATION_LIMIT = 400;

    private final WindowSource source;

    WindowsWindowCatalog() {
        this(WindowsWindowCatalog::enumerateNativeWindows);
    }

    WindowsWindowCatalog(WindowSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    TargetWindowSelectionSession scan(long excludedProcessId) {
        List<TargetWindowSelectionSession.NativeWindow> candidates = source.enumerate().stream()
                .filter(window -> eligible(window, excludedProcessId))
                .sorted(Comparator
                        .comparing((ObservedWindow window) -> normalized(window.title()),
                                String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(window -> normalized(window.executableName()),
                                String.CASE_INSENSITIVE_ORDER))
                .limit(TargetWindowSelectionSession.MAX_WINDOW_CANDIDATES)
                .map(window -> new TargetWindowSelectionSession.NativeWindow(
                        window.nativeHandle(), window.title(), window.executableName(),
                        window.windowClassName(), window.clientWidth(), window.clientHeight()))
                .toList();
        return TargetWindowSelectionSession.create(candidates);
    }

    private static boolean eligible(ObservedWindow window, long excludedProcessId) {
        return window != null
                && window.nativeHandle() != 0
                && window.processId() > 0
                && window.processId() != excludedProcessId
                && window.visible()
                && !window.minimized()
                && bounded(window.title(), 256, false)
                && bounded(window.executableName(), 260, false)
                && bounded(window.windowClassName(), 256, false)
                && window.clientWidth() >= 64 && window.clientWidth() <= 32_768
                && window.clientHeight() >= 64 && window.clientHeight() <= 32_768;
    }

    private static boolean bounded(String value, int maximum, boolean allowEmpty) {
        if (value == null) return false;
        String normalized = normalized(value);
        return (allowEmpty || !normalized.isEmpty()) && normalized.length() <= maximum;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static List<ObservedWindow> enumerateNativeWindows() {
        List<ObservedWindow> windows = new ArrayList<>();
        User32.INSTANCE.EnumWindows((handle, ignored) -> {
            if (windows.size() >= NATIVE_ENUMERATION_LIMIT) return false;
            boolean visible = User32.INSTANCE.IsWindowVisible(handle);
            boolean minimized = User32Extra.INSTANCE.IsIconic(handle);
            if (!visible || minimized) return true;
            WinDef.RECT client = new WinDef.RECT();
            User32.INSTANCE.GetClientRect(handle, client);
            int width = client.right - client.left;
            int height = client.bottom - client.top;
            if (width < 64 || width > 32_768 || height < 64 || height > 32_768) {
                return true;
            }
            String title = windowTitle(handle);
            String windowClass = windowClassName(handle);
            if (!bounded(title, 256, false)
                    || !bounded(windowClass, 256, false)) {
                return true;
            }
            IntByReference processId = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(handle, processId);
            long unsignedProcessId = Integer.toUnsignedLong(processId.getValue());
            windows.add(new ObservedWindow(
                    Pointer.nativeValue(handle.getPointer()),
                    unsignedProcessId,
                    visible,
                    minimized,
                    title,
                    executableName(unsignedProcessId),
                    windowClass,
                    width,
                    height));
            return true;
        }, null);
        return windows;
    }

    private static String windowTitle(WinDef.HWND handle) {
        int capacity = Math.min(
                Math.max(User32.INSTANCE.GetWindowTextLength(handle) + 1, 2), 257);
        char[] value = new char[capacity];
        int length = User32.INSTANCE.GetWindowText(handle, value, value.length);
        return length > 0 ? new String(value, 0, length) : "";
    }

    private static String windowClassName(WinDef.HWND handle) {
        char[] value = new char[257];
        int length = User32.INSTANCE.GetClassName(handle, value, value.length);
        return length > 0 ? Native.toString(value) : "";
    }

    private static String executableName(long processId) {
        WinNT.HANDLE process = Kernel32.INSTANCE.OpenProcess(
                WinNT.PROCESS_QUERY_LIMITED_INFORMATION, false, (int) processId);
        if (process == null) return fallbackExecutable(processId);
        try {
            char[] path = new char[4_096];
            IntByReference length = new IntByReference(path.length);
            if (!Kernel32.INSTANCE.QueryFullProcessImageName(
                    process, 0, path, length)) {
                return fallbackExecutable(processId);
            }
            try {
                Path name = Path.of(new String(path, 0, length.getValue())).getFileName();
                if (name != null && !name.toString().isBlank()) {
                    return name.toString();
                }
            } catch (InvalidPathException ignored) { }
            return fallbackExecutable(processId);
        } finally {
            Kernel32.INSTANCE.CloseHandle(process);
        }
    }

    private static String fallbackExecutable(long processId) {
        return "process-" + Long.toUnsignedString(processId) + ".exe";
    }

    @FunctionalInterface
    interface WindowSource {
        List<ObservedWindow> enumerate();
    }

    record ObservedWindow(long nativeHandle,
                          long processId,
                          boolean visible,
                          boolean minimized,
                          String title,
                          String executableName,
                          String windowClassName,
                          int clientWidth,
                          int clientHeight) { }
}
