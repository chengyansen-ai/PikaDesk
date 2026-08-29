package com.sojourners.chess;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReadOnlyAdvisorUiContractTest {

    @Test
    void disconnectingReadOnlyAdvisorRestoresTheImmediateMoveControl() throws Exception {
        String controller = Files.readString(Path.of(
                "src", "main", "java", "com", "sojourners", "chess",
                "controller", "Controller.java"));
        int start = controller.indexOf("private void stopGraphLink()");
        int end = controller.indexOf("private void engineGo()", start);
        String stopGraphLink = controller.substring(start, end);

        assertTrue(stopGraphLink.contains("immediateButton.setDisable(false);"));
    }

    @Test
    void windowsSelectionUsesFreshModelBoundsForReadOnlyPreflight() throws Exception {
        String linker = Files.readString(Path.of(
                "src", "main", "java", "com", "sojourners", "chess",
                "linker", "WindowsGraphLinker.java"));

        assertTrue(linker.contains("wizard.prepareReadOnlyAdvisor("));
    }

    @Test
    void wizardExplainsThatFreshReadOnlyPreflightAlreadyPassed() throws Exception {
        String controller = Files.readString(Path.of(
                "src", "main", "java", "com", "sojourners", "chess",
                "controller", "LinkSettingController.java"));

        assertTrue(controller.contains("已自动预校准"));
        assertTrue(controller.contains("重新执行识别校准（不点击）"));
    }

    @Test
    void connectionLifecycleHasItsOwnStatusLabel() throws Exception {
        String fxml = Files.readString(Path.of(
                "src", "main", "resources", "fxml", "app.fxml"));
        String controller = Files.readString(Path.of(
                "src", "main", "java", "com", "sojourners", "chess",
                "controller", "Controller.java"));

        assertTrue(fxml.contains("fx:id=\"connectionStatusLabel\""));
        assertTrue(controller.contains("void connectionStatus(ConnectionStatus status)"));
    }

    @Test
    void genericWindowChooserKeepsTheCrosshairFallback() throws Exception {
        String controller = Files.readString(Path.of(
                "src", "main", "java", "com", "sojourners", "chess",
                "controller", "Controller.java"));
        String windowsLinker = Files.readString(Path.of(
                "src", "main", "java", "com", "sojourners", "chess",
                "linker", "WindowsGraphLinker.java"));

        assertTrue(controller.contains("ChoiceDialog<TargetWindowChoice>"));
        assertTrue(windowsLinker.contains("TargetWindowSelectionSession"));
        assertTrue(windowsLinker.contains("crosshairFallback()"));
    }
}
