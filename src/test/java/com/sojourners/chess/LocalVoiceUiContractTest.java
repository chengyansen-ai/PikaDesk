package com.sojourners.chess;

import com.sojourners.chess.media.LocalVoiceService;
import javafx.scene.control.CheckMenuItem;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LocalVoiceUiContractTest {

    @Test
    void settingsMenuExposesIndependentLocalVoiceControls() throws Exception {
        String fxml = Files.readString(Path.of(
                "src", "main", "resources", "fxml", "app.fxml"));
        Class<?> controller = Class.forName(
                "com.sojourners.chess.controller.Controller");

        assertAll(
                () -> assertTrue(fxml.contains("text=\"中文语音\"")),
                () -> assertTrue(fxml.contains(
                        "<?import javafx.scene.control.SeparatorMenuItem?>")),
                () -> assertTrue(fxml.contains(
                        "fx:id=\"menuOfVoiceEnabled\"")),
                () -> assertTrue(fxml.contains(
                        "fx:id=\"menuOfVoiceMoves\"")),
                () -> assertTrue(fxml.contains(
                        "fx:id=\"menuOfVoiceWarnings\"")),
                () -> assertTrue(fxml.contains(
                        "fx:id=\"menuOfVoiceResults\"")),
                () -> assertTrue(fxml.contains(
                        "onAction=\"#voiceSettingChanged\"")),
                () -> assertEquals(CheckMenuItem.class,
                        controller.getDeclaredField("menuOfVoiceEnabled")
                                .getType()),
                () -> assertEquals(CheckMenuItem.class,
                        controller.getDeclaredField("menuOfVoiceMoves")
                                .getType()),
                () -> assertEquals(CheckMenuItem.class,
                        controller.getDeclaredField("menuOfVoiceWarnings")
                                .getType()),
                () -> assertEquals(CheckMenuItem.class,
                        controller.getDeclaredField("menuOfVoiceResults")
                                .getType()),
                () -> assertEquals(LocalVoiceService.class,
                        controller.getDeclaredField("voiceService").getType())
        );
    }

    @Test
    void controllerConnectsMoveWarningResultAndShutdownEvents()
            throws Exception {
        String source = Files.readString(Path.of("src", "main", "java",
                "com", "sojourners", "chess", "controller",
                "Controller.java"));

        assertAll(
                () -> assertTrue(source.contains(
                        "VoiceAnnouncement.move(")),
                () -> assertTrue(source.contains(
                        "VoiceAnnouncement.warning(")),
                () -> assertTrue(source.contains(
                        "VoiceAnnouncement.result(")),
                () -> assertTrue(source.contains("voiceService.close()"))
        );
    }
}
