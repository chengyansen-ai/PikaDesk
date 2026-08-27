package com.sojourners.chess.testboard;

import com.sojourners.chess.App;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

/** Standalone local acceptance window for the XQB batch workflow. */
public final class BookBatchTestApp extends Application {

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(
                BookBatchTestApp.class.getResource("/fxml/bookBatch.fxml"));
        Parent root = loader.load();
        Scene scene = new Scene(root);
        App.applyTheme(scene);
        stage.setTitle("PikaDesk XQB 批处理离线验收");
        stage.setScene(scene);
        stage.setMinWidth(640);
        stage.setMinHeight(620);
        stage.show();
    }
}
