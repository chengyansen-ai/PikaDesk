package com.sojourners.chess;

/**
 * User-facing product identity and upstream attribution.
 */
public final class ProductInfo {

    public static final String NAME = "PikaDesk";
    public static final String VERSION = "0.1.0-dev";
    public static final String BUILD_DATE = "2026-08-26";
    public static final String UPSTREAM_NAME = "TCHESS";
    public static final String UPSTREAM_VERSION = "1.9";
    public static final String UPSTREAM_COMMIT = "2d41525095639548059ebd930b0af4d29efc1364";
    public static final String UPSTREAM_URL = "https://github.com/sojourners/public-Xiangqi";
    public static final String UPSTREAM_RELEASES_URL = UPSTREAM_URL + "/releases";
    public static final String UPSTREAM_MANUAL_URL = UPSTREAM_URL + "/blob/master/MANUAL.md";
    public static final String LICENSE_ID = "GPL-3.0-only";
    public static final String EXPORT_FILE_PREFIX = "pikadesk_export_";
    public static final String MANUAL_ATTRIBUTION = "来自 PikaDesk（基于 TCHESS）";

    private ProductInfo() {
    }

    public static String windowTitle() {
        return NAME + " V" + VERSION;
    }

    public static String aboutText() {
        String newline = System.lineSeparator();
        return NAME
                + newline + "版本：" + VERSION
                + newline + "构建日期：" + BUILD_DATE
                + newline + "基于 " + UPSTREAM_NAME + " " + UPSTREAM_VERSION
                + newline + "上游：" + UPSTREAM_URL
                + newline + "许可证：" + LICENSE_ID;
    }
}
