package com.sojourners.chess.controller.component;

import com.sojourners.chess.game.tree.GameTree;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GameTreeViewModelTest {

    private static final String STANDARD_FEN =
            "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w";

    @Test
    void exposesMainlineVariationsAndExactNavigationContext() {
        GameTreeViewModel model = new GameTreeViewModel(STANDARD_FEN);
        UUID root = model.root().id();
        UUID first = model.recordMove("b0c2").id();
        UUID reply = model.recordMove("b9c7").id();
        model.jumpTo(root);
        UUID variation = model.recordMove("h0g2").id();

        List<GameTreeViewModel.Row> children = model.children(root);
        assertEquals(List.of(first, variation),
                children.stream().map(GameTreeViewModel.Row::id).toList());
        assertTrue(children.getFirst().mainline());
        assertFalse(children.getLast().mainline());

        GameTreeViewModel.Navigation navigation = model.jumpTo(reply);
        assertEquals(reply, navigation.nodeId());
        assertEquals(List.of("b0c2", "b9c7"), navigation.moves());
        assertEquals("w", navigation.positionFen().split(" ")[1]);
        assertEquals(List.of(), navigation.childMoves());
    }

    @Test
    void updatesCommentsEvaluationCurveAndCriticalMistakes() {
        GameTreeViewModel model = new GameTreeViewModel(STANDARD_FEN);
        UUID first = model.recordMove("b0c2").id();
        UUID reply = model.recordMove("b9c7").id();
        model.updateComment(first, "稳健出子");
        model.updateEvaluation(first,
                new GameTree.Evaluation(25, null, 12, "Pikafish"));
        model.updateEvaluation(reply,
                new GameTree.Evaluation(190, null, 14, "Pikafish"));

        assertEquals("稳健出子", model.row(first).comment());
        assertEquals(List.of(first, reply), model.evaluationSeries().stream()
                .map(GameTreeViewModel.EvaluationPoint::nodeId).toList());
        assertEquals(List.of(reply), model.criticalMistakes(150).stream()
                .map(GameTreeViewModel.CriticalMistake::nodeId).toList());
        assertTrue(model.row(reply).evaluationText().contains("+1.90"));
        assertTrue(model.criticalMistakes(150).getFirst().label().contains("黑方失误"));
    }

    @Test
    void doesNotLabelAnUnmeasuredGapAsOneMoveMistake() {
        GameTreeViewModel model = new GameTreeViewModel(STANDARD_FEN);
        UUID first = model.recordMove("b0c2").id();
        model.recordMove("b9c7");
        UUID third = model.recordMove("c2b0").id();
        model.updateEvaluation(first,
                new GameTree.Evaluation(20, null, 10, "Pikafish"));
        model.updateEvaluation(third,
                new GameTree.Evaluation(-300, null, 12, "Pikafish"));

        assertTrue(model.criticalMistakes(150).isEmpty());
    }

    @Test
    void canDetectAFirstMoveMistakeFromAnEvaluatedRoot() {
        GameTreeViewModel model = new GameTreeViewModel(STANDARD_FEN);
        UUID root = model.root().id();
        UUID first = model.recordMove("b0c2").id();
        model.updateEvaluation(root,
                new GameTree.Evaluation(80, null, 10, "Pikafish"));
        model.updateEvaluation(first,
                new GameTree.Evaluation(-90, null, 12, "Pikafish"));

        assertEquals(List.of(first), model.criticalMistakes(150).stream()
                .map(GameTreeViewModel.CriticalMistake::nodeId).toList());
        assertTrue(model.criticalMistakes(150).getFirst().label().contains("红方失误"));
    }

    @Test
    void validatesAnImportedPathBeforeChangingTheExistingTree() {
        GameTreeViewModel model = new GameTreeViewModel(STANDARD_FEN);

        assertThrows(IllegalArgumentException.class, () -> model.followLine(
                STANDARD_FEN, List.of("b0c2", "b9c7", "b0b2")));
        assertEquals(1, model.size());
        assertEquals(model.root().id(), model.current().id());
    }

    @Test
    void handlesTenThousandPlyWithoutFlatteningTheTreeForChildQueries() {
        assertTimeout(Duration.ofSeconds(5), () -> {
            GameTreeViewModel model = new GameTreeViewModel(STANDARD_FEN);
            String[] cycle = {"b0c2", "b9c7", "c2b0", "c7b9"};
            for (int ply = 0; ply < 10_000; ply++) {
                model.recordMove(cycle[ply % cycle.length]);
            }

            assertEquals(10_001, model.size());
            assertEquals(1, model.children(model.root().id()).size());
            assertEquals(10_000, model.jumpTo(model.current().id()).moves().size());
        });
    }

    @Test
    void mainWindowDeclaresTheBranchTreeHost() throws Exception {
        String fxml = Files.readString(Path.of(
                "src", "main", "resources", "fxml", "app.fxml"));
        Class<?> controllerType = Class.forName(
                "com.sojourners.chess.controller.Controller");

        assertAll(
                () -> assertTrue(fxml.contains("text=\"分支树\"")),
                () -> assertTrue(fxml.contains("fx:id=\"gameTreePaneHost\"")),
                () -> assertEquals("javafx.scene.layout.BorderPane",
                        controllerType.getDeclaredField("gameTreePaneHost")
                                .getType().getName()),
                () -> assertTrue(javafx.scene.layout.BorderPane.class
                        .isAssignableFrom(GameTreePane.class))
        );
    }
}
