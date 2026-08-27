package com.sojourners.chess.testboard;

import com.sojourners.chess.analysis.MultiEngineAnalysisWorkspace;
import com.sojourners.chess.controller.component.MultiEnginePane;
import com.sojourners.chess.model.EngineConfig;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Offline acceptance window for exercising five real engine processes without
 * changing the user's persisted engine configuration.
 */
public final class MultiEngineTestApp extends Application {

    private static final String ENGINE_ENV = "PIKADESK_TEST_ENGINE";
    private MultiEnginePane pane;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        Path engine = configuredEngine();
        List<EngineConfig> engines = IntStream.rangeClosed(1, 5)
                .mapToObj(index -> new EngineConfig(
                        "Pikafish 验收 " + index,
                        engine.toString(),
                        "uci",
                        options()))
                .toList();
        pane = new MultiEnginePane(
                engines,
                10,
                320,
                () -> new MultiEngineAnalysisWorkspace.AnalysisRequest(
                        LocalTestBoardState.STANDARD_FEN,
                        List.of(),
                        new MultiEngineAnalysisWorkspace.SearchLimit(
                                MultiEngineAnalysisWorkspace.SearchMode.DEPTH,
                                10)),
                () -> { },
                () -> { });

        stage.setTitle("PikaDesk 五引擎离线验收");
        stage.setScene(new Scene(pane, 1500, 720));
        stage.setMinWidth(900);
        stage.setMinHeight(560);
        stage.setOnCloseRequest(event -> pane.close());
        stage.show();
    }

    @Override
    public void stop() {
        if (pane != null) {
            pane.close();
        }
    }

    private Path configuredEngine() {
        String value = System.getenv(ENGINE_ENV);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Set " + ENGINE_ENV + " to an explicitly selected local engine executable");
        }
        Path engine = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(engine)) {
            throw new IllegalStateException("Configured test engine is not a regular file: " + engine);
        }
        return engine;
    }

    private LinkedHashMap<String, String> options() {
        LinkedHashMap<String, String> options = new LinkedHashMap<>();
        options.put("MultiPV", "2");
        return options;
    }
}
