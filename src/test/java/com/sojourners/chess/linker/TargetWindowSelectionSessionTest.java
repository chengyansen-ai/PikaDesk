package com.sojourners.chess.linker;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TargetWindowSelectionSessionTest {

    @Test
    void normalizesUntrustedWindowMetadataWithoutExposingTheNativeHandle() {
        TargetWindowSelectionSession session = TargetWindowSelectionSession.create(List.of(
                new TargetWindowSelectionSession.NativeWindow(
                        0x1234L, "  本地\n测试棋盘  ", "Board.exe",
                        " JavaFX\tWindow ", 1120, 840)));

        TargetWindowChoice choice = session.choices().get(1);

        assertEquals("本地 测试棋盘", choice.title());
        assertEquals("JavaFX Window", choice.windowClassName());
        assertEquals("本地 测试棋盘 — Board.exe · 1120×840", choice.toString());
        assertFalse(choice.toString().contains("1234"));
        assertEquals(0x1234L, session.resolve(choice).nativeHandle());
    }

    @Test
    void addsCrosshairFallbackAndCapsVisibleCandidates() {
        List<TargetWindowSelectionSession.NativeWindow> windows = new ArrayList<>();
        for (int index = 0; index < 250; index++) {
            windows.add(new TargetWindowSelectionSession.NativeWindow(
                    1000L + index, "窗口 " + index, "Board.exe",
                    "JavaFX", 900, 700));
        }

        TargetWindowSelectionSession session = TargetWindowSelectionSession.create(windows);

        assertEquals(201, session.choices().size());
        assertTrue(session.choices().getFirst().crosshairFallback());
        assertTrue(session.resolve(session.choices().getFirst()).crosshairFallback());
    }

    @Test
    void rejectsForgedAndExpiredSelections() {
        TargetWindowSelectionSession first = TargetWindowSelectionSession.create(List.of(
                window(41L, "第一个窗口")));
        TargetWindowSelectionSession second = TargetWindowSelectionSession.create(List.of(
                window(42L, "第二个窗口")));
        TargetWindowChoice real = first.choices().get(1);
        TargetWindowChoice forged = new TargetWindowChoice(
                real.selectionToken(), "被篡改", real.executableName(),
                real.windowClassName(), real.clientWidth(), real.clientHeight(), false);

        assertThrows(IllegalArgumentException.class, () -> first.resolve(forged));
        assertThrows(IllegalArgumentException.class, () -> second.resolve(real));
    }

    @Test
    void rejectsInvalidWindowBoundsAndOversizedMetadata() {
        assertThrows(IllegalArgumentException.class,
                () -> new TargetWindowSelectionSession.NativeWindow(
                        0, "窗口", "Board.exe", "JavaFX", 900, 700));
        assertThrows(IllegalArgumentException.class,
                () -> new TargetWindowSelectionSession.NativeWindow(
                        1, "x".repeat(257), "Board.exe", "JavaFX", 900, 700));
        assertThrows(IllegalArgumentException.class,
                () -> new TargetWindowSelectionSession.NativeWindow(
                        1, "窗口", "Board.exe", "JavaFX", 63, 700));
    }

    private TargetWindowSelectionSession.NativeWindow window(long handle, String title) {
        return new TargetWindowSelectionSession.NativeWindow(
                handle, title, "Board.exe", "JavaFX", 900, 700);
    }
}
