package com.sojourners.chess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProductInfoTest {

    @Test
    void identifiesPikaDeskAndCreditsTheUpstreamProject() {
        assertAll(
                () -> assertEquals("PikaDesk", ProductInfo.NAME),
                () -> assertEquals("TCHESS", ProductInfo.UPSTREAM_NAME),
                () -> assertEquals("GPL-3.0-only", ProductInfo.LICENSE_ID),
                () -> assertTrue(ProductInfo.UPSTREAM_URL.startsWith("https://github.com/sojourners/public-Xiangqi")),
                () -> assertTrue(ProductInfo.aboutText().contains(ProductInfo.UPSTREAM_URL)),
                () -> assertTrue(ProductInfo.aboutText().contains(ProductInfo.LICENSE_ID))
        );
    }

    @Test
    void derivesUserFacingIdentityFromTheSameMetadata() {
        assertAll(
                () -> assertEquals("PikaDesk V" + ProductInfo.VERSION, ProductInfo.windowTitle()),
                () -> assertEquals("pikadesk_export_", ProductInfo.EXPORT_FILE_PREFIX),
                () -> assertEquals("来自 PikaDesk（基于 TCHESS）", ProductInfo.MANUAL_ATTRIBUTION)
        );
    }
}
