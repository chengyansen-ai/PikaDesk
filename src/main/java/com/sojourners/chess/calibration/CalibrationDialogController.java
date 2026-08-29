package com.sojourners.chess.calibration;

import com.sojourners.chess.automation.BoardCoordinateMapper;
import com.sojourners.chess.util.DialogUtils;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;

import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * Visual board calibration dialog. Shows a full-screen snapshot, asks the user
 * to click the board's top-left and bottom-right intersections, and converts
 * the two points into client-relative board bounds.
 */
public class CalibrationDialogController {

    private static final double PREVIEW_WIDTH = 720.0;

    @FXML private Label hintLabel;
    @FXML private Label detailLabel;
    @FXML private ImageView snapshotView;
    @FXML private Pane overlayPane;
    @FXML private Button undoButton;
    @FXML private Button applyButton;

    private BufferedImage snapshot;
    private BoardCoordinateMapper.ClientArea client;
    private double scaling = 1.0;
    private Consumer<BoardCoordinateMapper.BoardBounds> onApplied;

    private BoardCalibrator.ScreenPoint firstPoint;
    private BoardCalibrator.ScreenPoint secondPoint;
    private double viewWidth;
    private double viewHeight;

    public void setSnapshot(BufferedImage snapshot,
                            BoardCoordinateMapper.ClientArea client,
                            double screenScalingFactor,
                            Consumer<BoardCoordinateMapper.BoardBounds> onApplied) {
        this.snapshot = snapshot;
        this.client = client;
        this.scaling = screenScalingFactor;
        this.onApplied = onApplied;
    }

    @FXML
    public void initialize() {
        // layout state is driven after setSnapshot
    }

    public void renderSnapshot() {
        if (snapshot == null || snapshot.getWidth() <= 0 || snapshot.getHeight() <= 0) {
            throw new IllegalStateException("标定截图不可用");
        }
        Image image = toFxImage(snapshot);
        snapshotView.setImage(image);
        double imageWidth = snapshot.getWidth();
        double imageHeight = snapshot.getHeight();
        double scale = PREVIEW_WIDTH / imageWidth;
        viewWidth = PREVIEW_WIDTH;
        viewHeight = Math.max(1.0, imageHeight * scale);
        overlayPane.setPrefSize(viewWidth, viewHeight);
        overlayPane.setMaxSize(viewWidth, viewHeight);
        resetPoints();
        updateState();
    }

    @FXML
    void snapshotClicked(MouseEvent event) {
        double logicalX = event.getX() / viewWidth * snapshot.getWidth();
        double logicalY = event.getY() / viewHeight * snapshot.getHeight();
        BoardCalibrator.ScreenPoint point =
                new BoardCalibrator.ScreenPoint(logicalX, logicalY);
        if (firstPoint == null) {
            firstPoint = point;
            addMarker(point, Color.RED, "左上");
        } else if (secondPoint == null) {
            secondPoint = point;
            addMarker(point, Color.BLUE, "右下");
            addPreviewBox();
        }
        updateState();
    }

    @FXML
    void undoClick(javafx.event.ActionEvent event) {
        if (secondPoint != null) {
            secondPoint = null;
        } else {
            firstPoint = null;
        }
        overlayPane.getChildren().clear();
        if (firstPoint != null) {
            addMarker(firstPoint, Color.RED, "左上");
        }
        updateState();
    }

    @FXML
    void cancelClick(javafx.event.ActionEvent event) {
        close();
    }

    @FXML
    void applyClick(javafx.event.ActionEvent event) {
        if (firstPoint == null || secondPoint == null) {
            return;
        }
        try {
            BoardCoordinateMapper.BoardBounds bounds = BoardCalibrator.fromTwoCorners(
                    firstPoint, secondPoint, client, scaling);
            if (onApplied != null) {
                onApplied.accept(bounds);
            }
            close();
        } catch (IllegalArgumentException failure) {
            String detail = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            DialogUtils.showErrorDialog("标定无效", detail);
        }
    }

    private void addMarker(BoardCalibrator.ScreenPoint point, Color color, String tag) {
        double viewX = point.x() / snapshot.getWidth() * viewWidth;
        double viewY = point.y() / snapshot.getHeight() * viewHeight;
        Circle marker = new Circle(viewX, viewY, 7.0, Color.TRANSPARENT);
        marker.setStroke(color);
        marker.setStrokeWidth(2.5);
        overlayPane.getChildren().add(marker);
    }

    private void addPreviewBox() {
        double x1 = firstPoint.x() / snapshot.getWidth() * viewWidth;
        double y1 = firstPoint.y() / snapshot.getHeight() * viewHeight;
        double x2 = secondPoint.x() / snapshot.getWidth() * viewWidth;
        double y2 = secondPoint.y() / snapshot.getHeight() * viewHeight;
        Rectangle box = new Rectangle(
                Math.min(x1, x2), Math.min(y1, y2),
                Math.abs(x2 - x1), Math.abs(y2 - y1));
        box.setFill(Color.TRANSPARENT);
        box.setStroke(Color.LIMEGREEN);
        box.setStrokeWidth(2.0);
        box.getStrokeDashArray().addAll(6.0, 4.0);
        overlayPane.getChildren().add(box);
    }

    private void updateState() {
        if (firstPoint == null) {
            detailLabel.setText("第 1 步：点击棋盘最左上角的交叉点（左上角第一个十字点）");
        } else if (secondPoint == null) {
            detailLabel.setText("第 2 步：点击棋盘最右下角的交叉点（右下角最后一个十字点）");
        } else {
            detailLabel.setText("已选两个交点：绿色框内即识别为棋盘范围。可撤销重选，或填入向导。");
        }
        undoButton.setDisable(firstPoint == null);
        applyButton.setDisable(firstPoint == null || secondPoint == null);
    }

    private void resetPoints() {
        firstPoint = null;
        secondPoint = null;
        overlayPane.getChildren().clear();
    }

    private void close() {
        Stage stage = (Stage) snapshotView.getScene().getWindow();
        stage.close();
    }

    private static Image toFxImage(BufferedImage source) {
        WritableImage target = new WritableImage(source.getWidth(), source.getHeight());
        PixelWriter writer = target.getPixelWriter();
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int argb = source.getRGB(x, y);
                writer.setArgb(x, y, argb);
            }
        }
        return target;
    }
}
