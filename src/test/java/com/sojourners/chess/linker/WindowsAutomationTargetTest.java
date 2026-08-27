package com.sojourners.chess.linker;

import com.sojourners.chess.automation.AutomationSafetyKernel;
import com.sojourners.chess.automation.AutomationState;
import com.sojourners.chess.automation.BoardCoordinateMapper;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WindowsAutomationTargetTest {

    private static final WinDef.HWND HANDLE =
            new WinDef.HWND(Pointer.createConstant(0x1234));
    private static final BoardCoordinateMapper.ClientArea CLIENT =
            new BoardCoordinateMapper.ClientArea(100, 200, 1_000, 800);
    private static final BoardCoordinateMapper.BoardBounds BOARD =
            new BoardCoordinateMapper.BoardBounds(50, 40, 800, 630);
    private static final BoardCoordinateMapper.ActivitySnapshot QUIET =
            new BoardCoordinateMapper.ActivitySnapshot(20, 30, 100);

    @Test
    void sendsExactlyOnePermittedMoveUsingClientCoordinates() {
        FakeBridge bridge = new FakeBridge();
        WindowsAutomationTarget target = new WindowsAutomationTarget(HANDLE, bridge, millis -> { });
        AutomationSafetyKernel kernel = readyKernel(target.authorization());

        boolean executed = target.executeOne(
                kernel,
                new BoardCoordinateMapper(),
                calibration(target),
                QUIET,
                new BoardCoordinateMapper.Move(0, 0, 0, 1),
                0,
                0
        );

        assertAll(
                () -> assertTrue(executed),
                () -> assertEquals(AutomationState.CONFIRMING, kernel.state()),
                () -> assertEquals(List.of(
                        event(0x0200, 0, 50, 670),
                        event(0x0201, 1, 50, 670),
                        event(0x0202, 0, 50, 670),
                        event(0x0200, 0, 50, 600),
                        event(0x0201, 1, 50, 600),
                        event(0x0202, 0, 50, 600)
                ), bridge.events),
                () -> assertFalse(target.executeOne(
                        kernel, new BoardCoordinateMapper(), calibration(target), QUIET,
                        new BoardCoordinateMapper.Move(0, 1, 0, 2), 0, 0)),
                () -> assertEquals(6, bridge.events.size())
        );
    }

    @Test
    void stopsBeforeTheNextMessageWhenFocusIsLost() {
        FakeBridge bridge = new FakeBridge();
        bridge.afterSend = () -> bridge.window = bridge.window.withFocused(false);
        WindowsAutomationTarget target = new WindowsAutomationTarget(HANDLE, bridge, millis -> { });
        AutomationSafetyKernel kernel = readyKernel(target.authorization());

        boolean executed = executeDefault(target, bridge, kernel);

        assertAll(
                () -> assertFalse(executed),
                () -> assertEquals(1, bridge.events.size()),
                () -> assertEquals(AutomationState.PAUSED, kernel.state()),
                () -> assertTrue(kernel.lastReason().orElseThrow().contains("TARGET_NOT_FOCUSED"))
        );
    }

    @Test
    void stopsBeforeTheNextMessageWhenUserInputChanges() {
        FakeBridge bridge = new FakeBridge();
        bridge.afterSend = () -> bridge.activity =
                new BoardCoordinateMapper.ActivitySnapshot(20, 30, 101);
        WindowsAutomationTarget target = new WindowsAutomationTarget(HANDLE, bridge, millis -> { });
        AutomationSafetyKernel kernel = readyKernel(target.authorization());

        boolean executed = executeDefault(target, bridge, kernel);

        assertAll(
                () -> assertFalse(executed),
                () -> assertEquals(1, bridge.events.size()),
                () -> assertEquals(AutomationState.PAUSED, kernel.state()),
                () -> assertTrue(kernel.lastReason().orElseThrow().contains("USER_INPUT_DETECTED"))
        );
    }

    @Test
    void rejectsAReusedWindowHandleBeforeSendingAnything() {
        FakeBridge bridge = new FakeBridge();
        WindowsAutomationTarget target = new WindowsAutomationTarget(HANDLE, bridge, millis -> { });
        AutomationSafetyKernel kernel = readyKernel(target.authorization());
        bridge.window = new WindowsAutomationTarget.WindowObservation(
                true, 99, 88, CLIENT, 144, true, true);

        boolean executed = executeDefault(target, bridge, kernel);

        assertAll(
                () -> assertFalse(executed),
                () -> assertTrue(bridge.events.isEmpty()),
                () -> assertEquals(AutomationState.PAUSED, kernel.state()),
                () -> assertTrue(kernel.lastReason().orElseThrow().contains("TARGET_ID_CHANGED"))
        );
    }

    @Test
    void pausesWhenTheBoundedNativeSendFails() {
        FakeBridge bridge = new FakeBridge();
        bridge.sendSucceeds = false;
        WindowsAutomationTarget target = new WindowsAutomationTarget(HANDLE, bridge, millis -> { });
        AutomationSafetyKernel kernel = readyKernel(target.authorization());

        boolean executed = executeDefault(target, bridge, kernel);

        assertAll(
                () -> assertFalse(executed),
                () -> assertEquals(1, bridge.events.size()),
                () -> assertEquals(AutomationState.PAUSED, kernel.state()),
                () -> assertTrue(kernel.lastReason().orElseThrow().contains("timed out or failed"))
        );
    }

    private boolean executeDefault(WindowsAutomationTarget target,
                                   FakeBridge bridge,
                                   AutomationSafetyKernel kernel) {
        return target.executeOne(
                kernel, new BoardCoordinateMapper(), calibration(target), QUIET,
                new BoardCoordinateMapper.Move(0, 0, 0, 1), 0, 0);
    }

    private BoardCoordinateMapper.Calibration calibration(WindowsAutomationTarget target) {
        return new BoardCoordinateMapper.Calibration(
                target.authorization().targetId(),
                target.authorization().targetRevision(),
                CLIENT,
                BOARD,
                144,
                BoardCoordinateMapper.Orientation.RED_AT_BOTTOM
        );
    }

    private AutomationSafetyKernel readyKernel(
            AutomationSafetyKernel.Authorization authorization) {
        AutomationSafetyKernel kernel = new AutomationSafetyKernel();
        assertTrue(kernel.arm(authorization));
        assertTrue(kernel.beginObservation());
        assertTrue(kernel.recognitionAccepted());
        assertTrue(kernel.beginThinking());
        assertTrue(kernel.readyToExecute());
        return kernel;
    }

    private WindowsAutomationTarget.MouseEvent event(int message, int flags,
                                                      int clientX, int clientY) {
        return new WindowsAutomationTarget.MouseEvent(message, flags, clientX, clientY);
    }

    private static final class FakeBridge implements WindowsAutomationTarget.NativeBridge {
        private WindowsAutomationTarget.WindowObservation window =
                new WindowsAutomationTarget.WindowObservation(
                        true, 42, 77, CLIENT, 144, true, true);
        private BoardCoordinateMapper.ActivitySnapshot activity = QUIET;
        private final List<WindowsAutomationTarget.MouseEvent> events = new ArrayList<>();
        private Runnable afterSend = () -> { };
        private boolean sendSucceeds = true;

        @Override
        public WindowsAutomationTarget.WindowObservation inspect(WinDef.HWND handle) {
            return window;
        }

        @Override
        public BoardCoordinateMapper.ActivitySnapshot activity() {
            return activity;
        }

        @Override
        public boolean send(WinDef.HWND handle, WindowsAutomationTarget.MouseEvent event,
                            int timeoutMillis) {
            events.add(event);
            afterSend.run();
            return sendSucceeds;
        }
    }
}
