package com.sojourners.chess;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProjectBaselineTest {

    @Test
    void runsTestsOnJava21OrNewer() {
        assertTrue(
                Runtime.version().feature() >= 21,
                () -> "PikaDesk requires Java 21+, but tests use " + Runtime.version()
        );
    }

    @Test
    void mainWindowExposesTheAccessibleMultiEngineWorkspaceHost() throws Exception {
        String fxml = Files.readString(Path.of(
                "src", "main", "resources", "fxml", "app.fxml"));
        Class<?> paneType = Class.forName(
                "com.sojourners.chess.controller.component.MultiEnginePane");
        Class<?> controllerType = Class.forName(
                "com.sojourners.chess.controller.Controller");

        assertAll(
                () -> assertTrue(fxml.contains("text=\"多引擎\"")),
                () -> assertTrue(fxml.contains("fx:id=\"multiEnginePaneHost\"")),
                () -> assertEquals("javafx.scene.layout.BorderPane",
                        controllerType.getDeclaredField("multiEnginePaneHost")
                                .getType().getName()),
                () -> assertTrue(javafx.scene.layout.BorderPane.class.isAssignableFrom(paneType))
        );
    }

    @Test
    void providesAnOfflineFiveEngineAcceptanceWindow() throws Exception {
        Class<?> testApp = Class.forName(
                "com.sojourners.chess.testboard.MultiEngineTestApp");
        String pom = Files.readString(Path.of("pom.xml"));

        assertAll(
                () -> assertTrue(javafx.application.Application.class
                        .isAssignableFrom(testApp)),
                () -> assertTrue(pom.contains("<id>multi-engine-test</id>")),
                () -> assertTrue(pom.contains(
                        "Xiangqi/com.sojourners.chess.testboard.MultiEngineTestApp"))
        );
    }
}
