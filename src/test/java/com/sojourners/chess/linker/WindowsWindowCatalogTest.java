package com.sojourners.chess.linker;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class WindowsWindowCatalogTest {

    @Test
    void keepsOnlyBoundedVisibleTopLevelWindowsFromOtherProcesses() {
        WindowsWindowCatalog catalog = new WindowsWindowCatalog(() -> List.of(
                window(1, 10, true, false, "Zulu", "z.exe", 900, 700),
                window(2, 11, true, false, "Alpha", "a.exe", 1000, 800),
                window(3, 77, true, false, "Own app", "self.exe", 900, 700),
                window(4, 12, false, false, "Hidden", "h.exe", 900, 700),
                window(5, 13, true, true, "Minimized", "m.exe", 900, 700),
                window(6, 14, true, false, "   ", "blank.exe", 900, 700),
                window(7, 15, true, false, "Tiny", "tiny.exe", 63, 700),
                window(8, 16, true, false, "Huge", "huge.exe", 40_000, 700),
                window(9, 17, true, false, "No process", "", 900, 700)));

        TargetWindowSelectionSession session = catalog.scan(77);

        assertEquals(3, session.choices().size());
        assertEquals("Alpha", session.choices().get(1).title());
        assertEquals(2L, session.resolve(session.choices().get(1)).nativeHandle());
        assertEquals("Zulu", session.choices().get(2).title());
    }

    @Test
    void capsEnumerationBeforeRenderingUntrustedMetadata() {
        List<WindowsWindowCatalog.ObservedWindow> windows =
                java.util.stream.LongStream.range(1, 500)
                        .mapToObj(handle -> window(
                                handle, handle + 1000, true, false,
                                "Window " + handle, "board.exe", 900, 700))
                        .toList();

        TargetWindowSelectionSession session =
                new WindowsWindowCatalog(() -> windows).scan(999);

        assertEquals(TargetWindowSelectionSession.MAX_WINDOW_CANDIDATES + 1,
                session.choices().size());
    }

    private WindowsWindowCatalog.ObservedWindow window(
            long handle, long processId, boolean visible, boolean minimized,
            String title, String executableName, int width, int height) {
        return new WindowsWindowCatalog.ObservedWindow(
                handle, processId, visible, minimized, title,
                executableName, "WindowClass", width, height);
    }
}
