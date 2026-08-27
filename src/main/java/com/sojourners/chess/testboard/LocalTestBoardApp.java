package com.sojourners.chess.testboard;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.util.Map;

/**
 * Offline-only deterministic window used as the first automation E2E target.
 */
public final class LocalTestBoardApp extends Application {

    private static final double BASE_CELL = 54;
    private static final double BASE_MARGIN = 44;
    private static final Map<Character, String> PIECE_NAMES = Map.ofEntries(
            Map.entry('R', "车"), Map.entry('N', "马"), Map.entry('B', "相"),
            Map.entry('A', "仕"), Map.entry('K', "帅"), Map.entry('C', "炮"),
            Map.entry('P', "兵"), Map.entry('r', "车"), Map.entry('n', "马"),
            Map.entry('b', "象"), Map.entry('a', "士"), Map.entry('k', "将"),
            Map.entry('c', "炮"), Map.entry('p', "卒")
    );

    private final LocalTestBoardState state = new LocalTestBoardState();
    private final Canvas boardCanvas = new Canvas();
    private final TextArea moveLog = new TextArea();
    private final Label status = new Label("离线测试棋盘已就绪；未连接网络或账号");
    private final TextField fenField = new TextField(LocalTestBoardState.STANDARD_FEN);

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(12));
        root.setTop(createFenBar());
        root.setCenter(createBoardPane());
        root.setRight(createControlPanel());
        root.setBottom(status);
        BorderPane.setMargin(status, new Insets(10, 0, 0, 0));

        boardCanvas.setId("localTestBoardCanvas");
        boardCanvas.setAccessibleRole(AccessibleRole.IMAGE_VIEW);
        boardCanvas.setAccessibleText("PikaDesk 本地测试棋盘画布");
        moveLog.setId("localTestBoardMoveLog");
        status.setId("localTestBoardStatus");
        fenField.setId("localTestBoardFen");
        boardCanvas.setOnMouseClicked(event -> receiveClick(event.getX(), event.getY()));

        primaryStage.setTitle("PikaDesk 本地自动化测试棋盘");
        primaryStage.setScene(new Scene(root, 1120, 840));
        primaryStage.setMinWidth(900);
        primaryStage.setMinHeight(700);
        redrawBoard();
        primaryStage.show();
    }

    private HBox createFenBar() {
        Label label = new Label("局面 FEN");
        HBox.setMargin(label, new Insets(5, 0, 0, 0));
        fenField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(fenField, javafx.scene.layout.Priority.ALWAYS);

        Button apply = new Button("应用局面");
        apply.setId("applyLocalTestBoardFen");
        apply.setOnAction(event -> applyFen());
        Button reset = new Button("标准开局");
        reset.setOnAction(event -> {
            fenField.setText(LocalTestBoardState.STANDARD_FEN);
            applyFen();
        });

        HBox bar = new HBox(8, label, fenField, apply, reset);
        bar.setAlignment(Pos.CENTER_LEFT);
        BorderPane.setMargin(bar, new Insets(0, 0, 10, 0));
        return bar;
    }

    private ScrollPane createBoardPane() {
        StackPane holder = new StackPane(boardCanvas);
        holder.setPadding(new Insets(16));
        ScrollPane scrollPane = new ScrollPane(holder);
        scrollPane.setId("localTestBoardScrollPane");
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setPannable(true);
        return scrollPane;
    }

    private VBox createControlPanel() {
        ComboBox<LocalTestBoardState.Orientation> orientation = new ComboBox<>();
        orientation.getItems().setAll(LocalTestBoardState.Orientation.values());
        orientation.setValue(state.orientation());
        orientation.setId("localTestBoardOrientation");
        orientation.setConverter(enumConverter("红方在下", "黑方在下"));
        orientation.setMaxWidth(Double.MAX_VALUE);
        orientation.setOnAction(event -> {
            if (orientation.getValue() == null) {
                return;
            }
            state.setOrientation(orientation.getValue());
            redrawBoard();
            status.setText("棋盘方向已更新；等待测试走法");
        });

        ComboBox<LocalTestBoardState.Theme> theme = new ComboBox<>();
        theme.getItems().setAll(LocalTestBoardState.Theme.values());
        theme.setValue(state.theme());
        theme.setId("localTestBoardTheme");
        theme.setConverter(enumConverter("经典木色", "高对比度"));
        theme.setMaxWidth(Double.MAX_VALUE);
        theme.setOnAction(event -> {
            if (theme.getValue() == null) {
                return;
            }
            state.setTheme(theme.getValue());
            redrawBoard();
            status.setText("测试主题已更新");
        });

        ComboBox<Double> scale = new ComboBox<>();
        scale.getItems().setAll(0.75, 1.0, 1.25, 1.5, 2.0);
        scale.setValue(state.scale());
        scale.setId("localTestBoardScale");
        scale.setConverter(new StringConverter<>() {
            @Override
            public String toString(Double value) {
                return value == null ? "" : Math.round(value * 100) + "%";
            }

            @Override
            public Double fromString(String value) {
                return Double.parseDouble(value.replace("%", "")) / 100.0;
            }
        });
        scale.setMaxWidth(Double.MAX_VALUE);
        scale.setOnAction(event -> {
            if (scale.getValue() == null) {
                return;
            }
            state.setScale(scale.getValue());
            redrawBoard();
            status.setText("棋盘渲染缩放已更新为 " + scale.getConverter().toString(scale.getValue()));
        });

        Label animationValue = new Label("0 ms");
        Slider animation = new Slider(0, 2_000, 0);
        animation.setId("localTestBoardAnimation");
        animation.setMajorTickUnit(500);
        animation.setMinorTickCount(4);
        animation.setShowTickLabels(true);
        animation.setShowTickMarks(true);
        animation.valueProperty().addListener((observable, oldValue, newValue) -> {
            int millis = (int) Math.round(newValue.doubleValue() / 50.0) * 50;
            state.setAnimationMillis(millis);
            animationValue.setText(millis + " ms");
        });

        moveLog.setEditable(false);
        moveLog.setWrapText(true);
        moveLog.setPrefRowCount(16);
        moveLog.setPromptText("实际收到的 UCCI 走法会记录在这里");
        Button clearLog = new Button("清空走法记录");
        clearLog.setMaxWidth(Double.MAX_VALUE);
        clearLog.setOnAction(event -> {
            state.clearReceivedMoves();
            moveLog.clear();
            status.setText("走法记录已清空");
        });

        HBox animationHeader = new HBox(8, new Label("动画速度"), animationValue);
        animationHeader.setAlignment(Pos.CENTER_LEFT);
        VBox panel = new VBox(
                8,
                new Label("棋盘方向"), orientation,
                new Label("识别主题"), theme,
                new Label("棋盘渲染缩放"), scale,
                animationHeader, animation,
                new Separator(),
                new Label("实际收到的走法"), moveLog, clearLog,
                new Separator(),
                new Label("此窗口完全离线、无需账号，只用于 PikaDesk 自动化测试。")
        );
        panel.setPadding(new Insets(0, 0, 0, 12));
        panel.setPrefWidth(310);
        return panel;
    }

    private <T extends Enum<T>> StringConverter<T> enumConverter(String first, String second) {
        return new StringConverter<>() {
            @Override
            public String toString(T value) {
                if (value == null) {
                    return "";
                }
                return value.ordinal() == 0 ? first : second;
            }

            @Override
            public T fromString(String value) {
                throw new UnsupportedOperationException("selection only");
            }
        };
    }

    private void applyFen() {
        try {
            state.setFen(fenField.getText());
            fenField.setText(state.fen());
            moveLog.clear();
            status.setText("测试局面已应用；等待测试走法");
            redrawBoard();
        } catch (IllegalArgumentException exception) {
            status.setText("局面未应用：" + exception.getMessage());
        }
    }

    private void receiveClick(double x, double y) {
        double cell = BASE_CELL * state.scale();
        double margin = BASE_MARGIN * state.scale();
        int displayFile = (int) Math.round((x - margin) / cell);
        int displayRow = (int) Math.round((y - margin) / cell);
        double centerX = margin + displayFile * cell;
        double centerY = margin + displayRow * cell;
        if (displayFile < 0 || displayFile > 8 || displayRow < 0 || displayRow > 9
                || Math.abs(x - centerX) > cell * 0.42
                || Math.abs(y - centerY) > cell * 0.42) {
            status.setText("忽略棋盘交叉点之外的点击");
            return;
        }

        LocalTestBoardState.ClickResult result = state.clickDisplaySquare(displayFile, displayRow);
        if (result.kind() == LocalTestBoardState.ClickKind.MOVED) {
            LocalTestBoardState.Move move = result.move().orElseThrow();
            String capture = move.capturedPiece() == ' ' ? "" : "，吃 " + move.capturedPiece();
            moveLog.appendText(state.receivedMoves().size() + ". " + move.ucci() + capture + System.lineSeparator());
            status.setText("收到走法 " + move.ucci() + "；等待动画后显示");
            redrawAfterAnimation(move.ucci());
        } else {
            status.setText(result.kind() == LocalTestBoardState.ClickKind.SELECTED
                    ? "已选择棋子；等待目标交叉点"
                    : "忽略空起点点击");
            redrawBoard();
        }
    }

    private void redrawAfterAnimation(String ucci) {
        if (state.animationMillis() == 0) {
            redrawBoard();
            status.setText("已显示走法 " + ucci);
            return;
        }
        boardCanvas.setDisable(true);
        PauseTransition pause = new PauseTransition(Duration.millis(state.animationMillis()));
        pause.setOnFinished(event -> {
            redrawBoard();
            boardCanvas.setDisable(false);
            status.setText("动画完成，已显示走法 " + ucci);
        });
        pause.play();
    }

    private void redrawBoard() {
        double scale = state.scale();
        double cell = BASE_CELL * scale;
        double margin = BASE_MARGIN * scale;
        boardCanvas.setWidth(margin * 2 + cell * 8);
        boardCanvas.setHeight(margin * 2 + cell * 9);

        Palette palette = state.theme() == LocalTestBoardState.Theme.CLASSIC
                ? Palette.CLASSIC : Palette.HIGH_CONTRAST;
        GraphicsContext graphics = boardCanvas.getGraphicsContext2D();
        graphics.setFill(palette.background);
        graphics.fillRect(0, 0, boardCanvas.getWidth(), boardCanvas.getHeight());
        graphics.setStroke(palette.lines);
        graphics.setLineWidth(Math.max(1.2, 1.7 * scale));

        double left = margin;
        double right = margin + 8 * cell;
        double top = margin;
        double bottom = margin + 9 * cell;
        for (int row = 0; row < 10; row++) {
            double y = margin + row * cell;
            graphics.strokeLine(left, y, right, y);
        }
        for (int file = 0; file < 9; file++) {
            double x = margin + file * cell;
            if (file == 0 || file == 8) {
                graphics.strokeLine(x, top, x, bottom);
            } else {
                graphics.strokeLine(x, top, x, margin + 4 * cell);
                graphics.strokeLine(x, margin + 5 * cell, x, bottom);
            }
        }
        drawPalaces(graphics, margin, cell);
        drawRiver(graphics, margin, cell, palette.lines, scale);

        OptionalSelection selection = selectedDisplaySquare();
        for (int displayRow = 0; displayRow < 10; displayRow++) {
            for (int displayFile = 0; displayFile < 9; displayFile++) {
                char piece = state.pieceAtDisplay(displayFile, displayRow);
                if (piece != ' ') {
                    boolean selected = selection.matches(displayFile, displayRow);
                    drawPiece(graphics, displayFile, displayRow, piece, selected, margin, cell, palette, scale);
                }
            }
        }
    }

    private OptionalSelection selectedDisplaySquare() {
        return state.selectedSquare()
                .map(square -> state.orientation() == LocalTestBoardState.Orientation.RED_AT_BOTTOM
                        ? new OptionalSelection(square.file(), square.row())
                        : new OptionalSelection(8 - square.file(), 9 - square.row()))
                .orElse(OptionalSelection.NONE);
    }

    private void drawPalaces(GraphicsContext graphics, double margin, double cell) {
        graphics.strokeLine(margin + 3 * cell, margin, margin + 5 * cell, margin + 2 * cell);
        graphics.strokeLine(margin + 5 * cell, margin, margin + 3 * cell, margin + 2 * cell);
        graphics.strokeLine(margin + 3 * cell, margin + 7 * cell, margin + 5 * cell, margin + 9 * cell);
        graphics.strokeLine(margin + 5 * cell, margin + 7 * cell, margin + 3 * cell, margin + 9 * cell);
    }

    private void drawRiver(GraphicsContext graphics, double margin, double cell, Color color, double scale) {
        graphics.setFill(color);
        graphics.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 19 * scale));
        graphics.setTextAlign(TextAlignment.CENTER);
        graphics.fillText("楚 河", margin + 2 * cell, margin + 4.65 * cell);
        graphics.fillText("汉 界", margin + 6 * cell, margin + 4.65 * cell);
    }

    private void drawPiece(GraphicsContext graphics, int displayFile, int displayRow, char piece,
                           boolean selected, double margin, double cell, Palette palette, double scale) {
        double centerX = margin + displayFile * cell;
        double centerY = margin + displayRow * cell;
        double radius = cell * 0.39;
        graphics.setFill(selected ? palette.selectedPiece : palette.piece);
        graphics.fillOval(centerX - radius, centerY - radius, radius * 2, radius * 2);
        graphics.setStroke(Character.isUpperCase(piece) ? palette.redPiece : palette.blackPiece);
        graphics.setLineWidth(Math.max(1.5, 2.2 * scale));
        graphics.strokeOval(centerX - radius, centerY - radius, radius * 2, radius * 2);
        graphics.setFill(Character.isUpperCase(piece) ? palette.redPiece : palette.blackPiece);
        graphics.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, cell * 0.43));
        graphics.setTextAlign(TextAlignment.CENTER);
        graphics.fillText(PIECE_NAMES.get(piece), centerX, centerY + cell * 0.16);
    }

    private record OptionalSelection(int file, int row) {
        private static final OptionalSelection NONE = new OptionalSelection(-1, -1);

        private boolean matches(int candidateFile, int candidateRow) {
            return file == candidateFile && row == candidateRow;
        }
    }

    private record Palette(Color background, Color lines, Color piece, Color selectedPiece,
                           Color redPiece, Color blackPiece) {
        private static final Palette CLASSIC = new Palette(
                Color.web("#e5b96f"), Color.web("#5a321d"), Color.web("#f6d99a"),
                Color.web("#fff3b0"), Color.web("#b42318"), Color.web("#171717")
        );
        private static final Palette HIGH_CONTRAST = new Palette(
                Color.web("#f7f7f7"), Color.web("#111111"), Color.WHITE,
                Color.web("#ffe066"), Color.web("#d00000"), Color.BLACK
        );
    }
}
