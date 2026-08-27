package com.sojourners.chess;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WindowsPackagingContractTest {

    @Test
    void developmentImageUsesVerifiedTwoStageModularPackaging() throws IOException {
        String script = Files.readString(
                Path.of("scripts", "package-windows.ps1"), StandardCharsets.UTF_8);

        assertTrue(script.contains("mvnw.cmd"));
        assertTrue(script.contains("clean"));
        assertTrue(script.contains("verify"));
        assertTrue(script.contains("dependency:copy-dependencies"));
        assertTrue(script.contains("jlink.exe"));
        assertTrue(script.contains("jdk.unsupported.desktop"));
        assertTrue(script.contains("java.sql.rowset"));
        assertTrue(script.contains("jpackage.exe"));
        assertTrue(script.contains("--runtime-image"));
        assertTrue(script.contains("Xiangqi/com.sojourners.chess.Main"));
        assertTrue(script.contains("classes\\model"));
        assertTrue(script.contains("classes\\sound"));
        assertTrue(script.contains("classes\\ui"));
        assertTrue(script.contains("DEVELOPMENT-ONLY.txt"));
        assertFalse(script.contains("SkipTests"));
        assertFalse(script.contains("javafx:jlink"));
    }
}
