package com.sojourners.chess.linker;

import java.util.List;

/** Manual Windows acceptance probe restricted to the repository test board. */
public final class WindowsWindowCatalogProbe {

    private static final String ALLOWED_TITLE = "PikaDesk 本地自动化测试棋盘";

    private WindowsWindowCatalogProbe() { }

    public static void main(String[] args) {
        TargetWindowSelectionSession session = new WindowsWindowCatalog().scan(
                ProcessHandle.current().pid());
        List<TargetWindowChoice> matches = session.choices().stream()
                .filter(choice -> !choice.crosshairFallback())
                .filter(choice -> ALLOWED_TITLE.equals(choice.title()))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "expected exactly one repository test board, found " + matches.size());
        }
        TargetWindowChoice testBoard = matches.getFirst();
        TargetWindowSelectionSession.Resolution resolution = session.resolve(testBoard);
        if (resolution.crosshairFallback() || resolution.nativeHandle() == 0) {
            throw new IllegalStateException("test-board selection did not resolve");
        }
        System.out.println("WINDOW_CATALOG_TEST_TARGET=FOUND");
        System.out.println("WINDOW_CATALOG_TEST_SIZE="
                + testBoard.clientWidth() + "x" + testBoard.clientHeight());
        System.out.println("WINDOW_CATALOG_SELECTION=RESOLVED");
    }
}
