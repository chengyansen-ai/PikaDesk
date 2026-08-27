package com.sojourners.chess.controller.component;

import com.sojourners.chess.analysis.MultiEngineAnalysisWorkspace;
import com.sojourners.chess.enginee.EngineRegistry;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

final class EngineAnalysisCard extends TitledPane {

    private final CheckBox enabled = new CheckBox("参与");
    private final CheckBox visible = new CheckBox("显示");
    private final Button moveUp = textButton("上移", "将此引擎向前移动一栏");
    private final Button moveDown = textButton("下移", "将此引擎向后移动一栏");
    private final Label status = new Label();
    private final VBox variations = new VBox(6);
    private boolean updating;

    EngineAnalysisCard(MultiEngineAnalysisWorkspace.EngineView view,
                       Consumer<Boolean> enabledAction,
                       Consumer<Boolean> visibleAction,
                       Runnable moveUpAction,
                       Runnable moveDownAction) {
        setCollapsible(false);
        setMinWidth(220);
        setPrefWidth(240);
        setMaxWidth(320);

        HBox controls = new HBox(8, enabled, visible, moveUp, moveDown);
        controls.setAlignment(Pos.CENTER_LEFT);
        status.setWrapText(true);
        variations.setFillWidth(true);
        VBox content = new VBox(8, controls, status, variations);
        content.setPadding(new Insets(8));
        VBox.setVgrow(variations, Priority.ALWAYS);
        setContent(content);

        enabled.setOnAction(event -> {
            if (!updating) enabledAction.accept(enabled.isSelected());
        });
        visible.setOnAction(event -> {
            if (!updating) visibleAction.accept(visible.isSelected());
        });
        moveUp.setOnAction(event -> moveUpAction.run());
        moveDown.setOnAction(event -> moveDownAction.run());
        update(view, false, false);
    }

    void update(MultiEngineAnalysisWorkspace.EngineView view,
                boolean first,
                boolean last) {
        updating = true;
        setText(view.displayName());
        setAccessibleText("引擎 " + view.displayName());
        enabled.setSelected(view.enabled());
        enabled.setDisable(view.registryStatus() != EngineRegistry.Status.RUNNING);
        visible.setSelected(view.visible());
        moveUp.setDisable(first);
        moveDown.setDisable(last);
        status.setText(statusText(view));
        variations.getChildren().setAll(variationNodes(view));
        updating = false;
    }

    private List<Label> variationNodes(MultiEngineAnalysisWorkspace.EngineView view) {
        if (!view.visible()) {
            return List.of(wrappedLabel("结果已隐藏；如仍勾选“参与”，引擎会继续分析。"));
        }
        if (view.principalVariations().isEmpty()) {
            return List.of(wrappedLabel(switch (view.activity()) {
                case WAITING_READY -> "正在清理上一局面的输出…";
                case ANALYZING -> "正在等待主变…";
                case PAUSED -> "此引擎已暂停。";
                case ERROR -> "引擎命令失败。";
                default -> "尚无分析结果。";
            }));
        }
        return view.principalVariations().stream()
                .map(line -> wrappedLabel(format(line)))
                .toList();
    }

    private String statusText(MultiEngineAnalysisWorkspace.EngineView view) {
        String process = switch (view.registryStatus()) {
            case REGISTERED -> "未启动";
            case STARTING -> "启动中";
            case RUNNING -> "运行中";
            case FAILED -> "启动或运行失败";
            case STOPPED -> "已停止";
        };
        return view.lastError().map(error -> process + "：" + error).orElse(process);
    }

    private String format(MultiEngineAnalysisWorkspace.PvLine line) {
        String score = line.mate() != null
                ? "杀 " + line.mate()
                : line.scoreCp() == null ? "—"
                : String.format(Locale.ROOT, "%+.2f", line.scoreCp() / 100.0);
        String moves = String.join(" ", line.moves().stream().limit(8).toList());
        return "PV" + line.multiPv() + "  深度 " + line.depth()
                + "  分数 " + score + "\n" + moves;
    }

    private Label wrappedLabel(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private static Button textButton(String text, String accessibleText) {
        Button button = new Button(text);
        button.setAccessibleText(accessibleText);
        button.setTooltip(new Tooltip(accessibleText));
        button.setMinWidth(Button.USE_PREF_SIZE);
        return button;
    }
}
