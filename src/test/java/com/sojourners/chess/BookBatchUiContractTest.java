package com.sojourners.chess;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BookBatchUiContractTest {

    @Test
    void exposesTheXqbBatchWorkflowWithVisibleKeyboardLabels() throws Exception {
        String app = Files.readString(Path.of(
                "src", "main", "resources", "fxml", "app.fxml"));
        String dialog = Files.readString(Path.of(
                "src", "main", "resources", "fxml", "bookBatch.fxml"));
        String pom = Files.readString(Path.of("pom.xml"));
        Class<?> testApp = Class.forName(
                "com.sojourners.chess.testboard.BookBatchTestApp");

        assertAll(
                () -> assertTrue(app.contains("onAction=\"#bookBatchButtonClick\"")),
                () -> assertTrue(app.contains("text=\"批量制作 XQB\"")),
                () -> assertTrue(dialog.contains("fx:id=\"sourceList\"")),
                () -> assertTrue(dialog.contains("fx:id=\"destinationField\"")),
                () -> assertTrue(dialog.contains("fx:id=\"progressBar\"")),
                () -> assertTrue(dialog.contains("fx:id=\"statusLabel\"")),
                () -> assertTrue(dialog.contains("fx:id=\"issuesArea\"")),
                () -> assertTrue(dialog.contains("fx:id=\"discardCheckpointButton\"")),
                () -> assertTrue(dialog.contains("onAction=\"#discardCheckpointButtonClick\"")),
                () -> assertTrue(dialog.contains("text=\"_开始制作\"")),
                () -> assertTrue(dialog.contains("text=\"_取消\"")),
                () -> assertTrue(dialog.contains("text=\"_关闭\"")),
                () -> assertTrue(javafx.application.Application.class
                        .isAssignableFrom(testApp)),
                () -> assertTrue(pom.contains("<id>book-batch-test</id>")),
                () -> assertTrue(pom.contains(
                        "Xiangqi/com.sojourners.chess.testboard.BookBatchTestApp"))
        );
    }
}
