package com.sojourners.chess.util;

import java.awt.*;
import java.net.URI;

public class SystemUtils {

    public static void openBrowser(String url) {
        Thread.startVirtualThread(() -> {
            Desktop desktop = Desktop.getDesktop();
            if (Desktop.isDesktopSupported() && desktop.isSupported(Desktop.Action.BROWSE)) {
                try {
                    URI uri = new URI(url);
                    desktop.browse(uri);
                } catch (Exception e1) {
                    e1.printStackTrace();
                }
            }
        });
    }
}
