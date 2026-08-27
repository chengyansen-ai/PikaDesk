package com.sojourners.chess.controller.component;

import com.sojourners.chess.game.tree.GameTree;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.AccessibleRole;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** Branch navigation, comments, evaluation curve, and mistake navigation UI. */
public final class GameTreePane extends BorderPane {

    private static final int MAX_CHART_POINTS = 2_000;
    private static final int CRITICAL_SWING_CP = 150;

    private final GameTreeViewModel model;
    private final Consumer<GameTreeViewModel.Navigation> navigationConsumer;
    private final TreeView<GameTreeViewModel.Row> treeView = new TreeView<>();
    private final TextArea comment = new TextArea();
    private final Label evaluation = new Label("未评估");
    private final Label status = new Label();
    private final Button promote = button("设为主线", "将所选变例提升为主线");
    private final Button delete = button("删除变例", "删除所选节点及其全部后续着法");
    private final ListView<GameTreeViewModel.CriticalMistake> mistakes = new ListView<>();
    private final LineChart<Number, Number> chart;
    private final Map<UUID, LazyTreeItem> loadedItems = new HashMap<>();
    private boolean internalSelection;
    private UUID editedNodeId;

    public GameTreePane(String initialFen,
                        Consumer<GameTreeViewModel.Navigation> navigationConsumer) {
        this.model = new GameTreeViewModel(initialFen);
        this.navigationConsumer = Objects.requireNonNull(navigationConsumer, "navigationConsumer");
        setAccessibleRole(AccessibleRole.PARENT);
        setAccessibleText("分支棋谱与局面评估工作区");

        NumberAxis xAxis = new NumberAxis();
        NumberAxis yAxis = new NumberAxis();
        xAxis.setLabel("回合");
        yAxis.setLabel("红方分值");
        yAxis.setForceZeroInRange(false);
        chart = new LineChart<>(xAxis, yAxis);
        chart.setLegendVisible(false);
        chart.setAnimated(false);
        chart.setCreateSymbols(false);
        chart.setTitle("主线评估曲线");
        chart.setMinHeight(150);
        chart.setAccessibleText("主线评估曲线，正值有利红方，负值有利黑方");

        configureTree();
        configureEditor();
        SplitPane split = new SplitPane(treeView, buildDetails());
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.52);
        setCenter(split);
        BorderPane.setMargin(status, new Insets(4, 8, 6, 8));
        status.setWrapText(true);
        setBottom(status);
        rebuildAndReveal(model.root().id());
    }

    public void reset(String initialFen) {
        commitComment();
        model.reset(initialFen);
        rebuildAndReveal(model.root().id());
        status.setText("新分支棋谱已建立。节点数：1");
    }

    public void followLine(String initialFen, List<String> moves) {
        commitComment();
        GameTreeViewModel.Navigation navigation = model.followLine(initialFen, moves);
        rebuildAndReveal(navigation.nodeId());
        status.setText("已同步传统棋谱路径。节点数：" + model.size());
    }

    public GameTreeViewModel.Navigation recordMove(String move) {
        commitComment();
        UUID parentId = model.current().id();
        GameTreeViewModel.Row added = model.recordMove(move);
        LazyTreeItem parent = loadedItems.get(parentId);
        if (parent != null) {
            parent.reload();
            parent.setExpanded(true);
            Optional<TreeItem<GameTreeViewModel.Row>> child = parent.getChildren().stream()
                    .filter(item -> item.getValue().id().equals(added.id())).findFirst();
            child.ifPresent(this::selectInternally);
        }
        refreshDetails(added.id());
        status.setText("已记录 " + added.label() + "。节点数：" + model.size());
        return model.jumpTo(added.id());
    }

    public void updateCurrentEvaluation(Integer centipawns,
                                        Integer mateIn,
                                        int depth,
                                        String engine) {
        if ((centipawns == null) == (mateIn == null) || depth < 1) return;
        String engineName = engine == null || engine.isBlank()
                ? "本地引擎" : engine.trim();
        if (engineName.length() > 128) engineName = engineName.substring(0, 128);
        GameTree.Evaluation next;
        try {
            next = new GameTree.Evaluation(
                    centipawns, mateIn, Math.min(depth, 256), engineName);
        } catch (IllegalArgumentException invalid) {
            status.setText("已忽略超出安全范围的引擎评估。");
            return;
        }
        UUID nodeId = model.current().id();
        model.updateEvaluation(nodeId, next);
        LazyTreeItem item = loadedItems.get(nodeId);
        if (item != null) item.setValue(model.row(nodeId));
        treeView.refresh();
        refreshAnalytics();
        evaluation.setText(model.row(nodeId).evaluationText());
    }

    public GameTreeViewModel.Navigation currentNavigation() {
        return model.jumpTo(model.current().id());
    }

    private void configureTree() {
        treeView.setShowRoot(true);
        treeView.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        treeView.setAccessibleText("分支棋谱树，实心圆为主线，空心菱形为变例");
        treeView.setCellFactory(ignored -> new TreeCell<>() {
            @Override
            protected void updateItem(GameTreeViewModel.Row row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                String branch = row.parentId() == null ? "◆" : row.mainline() ? "●" : "◇";
                String note = row.comment().isBlank() ? "" : "  ✎";
                String score = "未评估".equals(row.evaluationText())
                        ? "" : "  [" + row.evaluationText().split(" / ")[0] + "]";
                setText(branch + " " + row.label() + score + note);
                setStyle(row.mainline()
                        ? "-fx-font-weight: bold;" : "-fx-font-style: italic;");
                setAccessibleText((row.mainline() ? "主线 " : "变例 ") + row.label()
                        + "，" + row.evaluationText());
            }
        });
        treeView.getSelectionModel().selectedItemProperty().addListener(
                (ignored, oldItem, newItem) -> {
                    if (internalSelection || newItem == null) return;
                    commitComment();
                    GameTreeViewModel.Navigation navigation =
                            model.jumpTo(newItem.getValue().id());
                    refreshDetails(navigation.nodeId());
                    treeView.refresh();
                    try {
                        navigationConsumer.accept(navigation);
                        status.setText("已恢复 " + newItem.getValue().label()
                                + "，引擎上下文已切换到该局面。");
                    } catch (RuntimeException failure) {
                        status.setText("局面恢复失败：" + message(failure));
                    }
                });
    }

    private void configureEditor() {
        comment.setWrapText(true);
        comment.setPromptText("为当前节点添加注释；Ctrl+Enter 保存");
        comment.setAccessibleText("当前分支节点注释，最多 16384 个字符");
        comment.setTextFormatter(new TextFormatter<String>(change ->
                change.getControlNewText().length() <= 16_384 ? change : null));
        comment.focusedProperty().addListener((ignored, wasFocused, focused) -> {
            if (wasFocused && !focused) commitComment();
        });
        comment.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isControlDown() && event.getCode() == KeyCode.ENTER) {
                commitComment();
                event.consume();
            }
        });
        promote.setOnAction(event -> promoteSelected());
        delete.setOnAction(event -> deleteSelected());
        mistakes.setAccessibleText("主线关键失误列表，选择后跳转到对应节点");
        mistakes.setCellFactory(ignored -> new ListCell<>() {
            @Override
            protected void updateItem(GameTreeViewModel.CriticalMistake item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.label());
            }
        });
        mistakes.getSelectionModel().selectedItemProperty().addListener(
                (ignored, oldItem, selected) -> {
                    if (selected != null) revealAndNavigate(selected.nodeId());
                });
    }

    private VBox buildDetails() {
        Label legend = new Label("● 主线   ◇ 变例   ✎ 有注释");
        legend.setAccessibleText("分支图例：实心圆主线，空心菱形变例，铅笔表示有注释");
        evaluation.setWrapText(true);
        HBox actions = new HBox(8, promote, delete);
        VBox result = new VBox(6,
                legend, new Label("当前评估"), evaluation,
                new Label("节点注释"), comment, actions,
                chart, new Label("关键失误（波动至少 1.50）"), mistakes);
        result.setPadding(new Insets(8));
        VBox.setVgrow(comment, Priority.SOMETIMES);
        VBox.setVgrow(chart, Priority.ALWAYS);
        VBox.setVgrow(mistakes, Priority.SOMETIMES);
        return result;
    }

    private void promoteSelected() {
        TreeItem<GameTreeViewModel.Row> selected = treeView.getSelectionModel().getSelectedItem();
        if (selected == null || selected.getValue().parentId() == null) return;
        UUID nodeId = selected.getValue().id();
        commitComment();
        model.promoteToMainline(nodeId);
        rebuildAndReveal(nodeId);
        status.setText("已将所选变例提升为主线。");
    }

    private void deleteSelected() {
        TreeItem<GameTreeViewModel.Row> selected = treeView.getSelectionModel().getSelectedItem();
        if (selected == null || selected.getValue().parentId() == null) return;
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "删除该节点及其全部后续着法？此操作会删除当前未保存的分支。",
                ButtonType.CANCEL, ButtonType.OK);
        alert.setTitle("删除分支");
        alert.setHeaderText(selected.getValue().label());
        if (alert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        UUID nodeId = selected.getValue().id();
        int removed = model.deleteSubtree(nodeId);
        UUID current = model.current().id();
        rebuildAndReveal(current);
        GameTreeViewModel.Navigation navigation = model.jumpTo(current);
        navigationConsumer.accept(navigation);
        status.setText("已删除 " + removed + " 个节点，并恢复到父局面。");
    }

    private void commitComment() {
        if (editedNodeId == null) return;
        try {
            model.updateComment(editedNodeId, comment.getText());
            LazyTreeItem item = loadedItems.get(editedNodeId);
            if (item != null) item.setValue(model.row(editedNodeId));
            treeView.refresh();
        } catch (RuntimeException failure) {
            status.setText("注释保存失败：" + message(failure));
        }
    }

    private void refreshDetails(UUID nodeId) {
        GameTreeViewModel.Row row = model.row(nodeId);
        editedNodeId = nodeId;
        comment.setText(row.comment());
        evaluation.setText(row.evaluationText());
        promote.setDisable(row.parentId() == null || row.mainline());
        delete.setDisable(row.parentId() == null);
        refreshAnalytics();
    }

    private void refreshAnalytics() {
        List<GameTreeViewModel.EvaluationPoint> points = model.evaluationSeries();
        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        if (!points.isEmpty()) {
            int stride = Math.max(1, (int) Math.ceil(points.size() / (double) MAX_CHART_POINTS));
            for (int index = 0; index < points.size(); index += stride) {
                GameTreeViewModel.EvaluationPoint point = points.get(index);
                series.getData().add(new XYChart.Data<>(point.ply(), point.score() / 100.0));
            }
            GameTreeViewModel.EvaluationPoint last = points.getLast();
            if (series.getData().isEmpty()
                    || !series.getData().getLast().getXValue().equals(last.ply())) {
                series.getData().add(new XYChart.Data<>(last.ply(), last.score() / 100.0));
            }
        }
        chart.getData().setAll(series);
        mistakes.getItems().setAll(model.criticalMistakes(CRITICAL_SWING_CP));
    }

    private void rebuildAndReveal(UUID nodeId) {
        loadedItems.clear();
        LazyTreeItem root = new LazyTreeItem(model.root());
        treeView.setRoot(root);
        root.setExpanded(true);
        reveal(nodeId).ifPresent(this::selectInternally);
        refreshDetails(nodeId);
    }

    private void revealAndNavigate(UUID nodeId) {
        reveal(nodeId).ifPresent(item -> {
            treeView.getSelectionModel().select(item);
            treeView.scrollTo(treeView.getRow(item));
        });
    }

    private Optional<TreeItem<GameTreeViewModel.Row>> reveal(UUID nodeId) {
        List<UUID> path = model.pathTo(nodeId);
        if (path.isEmpty()) return Optional.empty();
        TreeItem<GameTreeViewModel.Row> current = treeView.getRoot();
        for (int index = 1; index < path.size(); index++) {
            UUID nextId = path.get(index);
            current.setExpanded(true);
            current = current.getChildren().stream()
                    .filter(item -> item.getValue().id().equals(nextId))
                    .findFirst().orElse(null);
            if (current == null) return Optional.empty();
        }
        return Optional.of(current);
    }

    private void selectInternally(TreeItem<GameTreeViewModel.Row> item) {
        internalSelection = true;
        try {
            treeView.getSelectionModel().select(item);
            treeView.scrollTo(treeView.getRow(item));
        } finally {
            internalSelection = false;
        }
    }

    private static Button button(String text, String accessibleText) {
        Button button = new Button(text);
        button.setAccessibleText(accessibleText);
        button.setTooltip(new Tooltip(accessibleText));
        return button;
    }

    private static String message(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message.trim();
    }

    private final class LazyTreeItem extends TreeItem<GameTreeViewModel.Row> {

        private boolean loaded;

        private LazyTreeItem(GameTreeViewModel.Row row) {
            super(row);
            loadedItems.put(row.id(), this);
        }

        @Override
        public ObservableList<TreeItem<GameTreeViewModel.Row>> getChildren() {
            if (!loaded) {
                loaded = true;
                super.getChildren().setAll(model.children(getValue().id()).stream()
                        .map(LazyTreeItem::new).toList());
            }
            return super.getChildren();
        }

        @Override
        public boolean isLeaf() {
            return !getValue().hasChildren();
        }

        private void reload() {
            super.getChildren().stream()
                    .filter(LazyTreeItem.class::isInstance)
                    .map(LazyTreeItem.class::cast)
                    .forEach(LazyTreeItem::forgetLoadedSubtree);
            super.getChildren().clear();
            setValue(model.row(getValue().id()));
            loaded = false;
        }

        private void forgetLoadedSubtree() {
            java.util.ArrayDeque<LazyTreeItem> pending = new java.util.ArrayDeque<>();
            pending.push(this);
            while (!pending.isEmpty()) {
                LazyTreeItem item = pending.pop();
                loadedItems.remove(item.getValue().id());
                if (item.loaded) {
                    item.directChildren().forEach(pending::push);
                }
            }
        }

        private List<LazyTreeItem> directChildren() {
            return super.getChildren().stream()
                    .filter(LazyTreeItem.class::isInstance)
                    .map(LazyTreeItem.class::cast).toList();
        }
    }
}
