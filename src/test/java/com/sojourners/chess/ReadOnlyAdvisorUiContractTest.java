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
}
