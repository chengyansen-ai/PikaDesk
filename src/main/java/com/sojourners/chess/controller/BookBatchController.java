package com.sojourners.chess.controller;

import com.sojourners.chess.book.XqbBatchService;
import com.sojourners.chess.book.XqbResumableBatchService;
import com.sojourners.chess.util.PathUtils;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** JavaFX boundary for the local XQB batch builder. */
public final class BookBatchController {

    @FXML
    private ListView<Path> sourceList;
    @FXML
    private TextField destinationField;
    @FXML
    private ProgressBar progressBar;
    @FXML
    private Label statusLabel;
    @FXML
    private TextArea issuesArea;
    @FXML
    private Button addSourceButton;
    @FXML
    private Button removeSourceButton;
    @FXML
    private Button destinationButton;
    @FXML
    private Button discardCheckpointButton;
    @FXML
    private Button startButton;
    @FXML
    private Button cancelButton;

    private final BookBatchViewModel model = new BookBatchViewModel();
    private final XqbResumableBatchService resumableService =
            new XqbResumableBatchService();
    private Task<XqbBatchService.BatchReport> currentTask;
    private AtomicBoolean cancellationRequested;
    private boolean checkpointAvailable;

    public void initialize() {
        sourceList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        sourceList.setCellFactory(ignored -> new ListCell<>() {
            @Override
            protected void updateItem(Path path, boolean empty) {
                super.updateItem(path, empty);
                setText(empty || path == null ? null : path.toString());
            }
        });
        destinationField.setEditable(false);
        progressBar.setProgress(0);
        issuesArea.setText("尚未开始。完成后会在这里显示校验摘要和有限的问题样例。");
        updateReadyState();
        Platform.runLater(() -> {
            addSourceButton.requestFocus();
            Window window = sourceList.getScene().getWindow();
            window.setOnCloseRequest(event -> {
                if (currentTask != null) {
                    requestCancellation();
                    event.consume();
                }
            });
        });
    }

    @FXML
    void addSourceButtonClick() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择 XQB v1 源棋库");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("XQB v1 开局库 (*.xqb)", "*.xqb", "*.XQB"));
        setInitialDirectory(chooser);
        List<File> selected = chooser.showOpenMultipleDialog(owner());
        if (selected == null || selected.isEmpty()) return;
        BookBatchViewModel.AddResult added = model.addSources(selected.stream()
                .map(File::toPath).toList());
        sourceList.getItems().setAll(model.sources());
        if (added.rejected().isEmpty()) {
            statusLabel.setText("已添加 " + added.addedCount() + " 个源文件。");
        } else {
            statusLabel.setText("已添加 " + added.addedCount()
                    + " 个；忽略 " + added.rejected().size() + " 个非 XQB 文件。");
        }
        updateReadyState();
    }

    @FXML
    void removeSourceButtonClick() {
        List<Path> selected = List.copyOf(
                sourceList.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            statusLabel.setText("请先选择要移除的源文件。");
            return;
        }
        model.removeSources(selected);
        sourceList.getItems().setAll(model.sources());
        statusLabel.setText("已从任务中移除 " + selected.size() + " 个源文件。");
        updateReadyState();
    }

    @FXML
    void destinationButtonClick() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("保存合并后的 XQB v1 棋库");
        chooser.setInitialFileName("pikadesk-merged.xqb");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("XQB v1 开局库 (*.xqb)", "*.xqb"));
        setInitialDirectory(chooser);
        File selected = chooser.showSaveDialog(owner());
        if (selected == null) return;
        Path destination = selected.toPath();
        if (!destination.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                .endsWith(".xqb")) {
            destination = Path.of(destination + ".xqb");
        }
        model.setDestination(destination);
        destinationField.setText(model.destination().toString());
        loadRecoveryStatus();
        updateReadyState();
    }

    @FXML
    void discardCheckpointButtonClick() {
        if (model.destination() == null || !checkpointAvailable) return;
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                "只删除该输出文件对应的 PikaDesk 断点数据。源棋库和已有输出文件不会被删除。",
                ButtonType.CANCEL, ButtonType.OK);
        confirmation.initOwner(owner());
        confirmation.setTitle("清除 XQB 批处理断点");
        confirmation.setHeaderText("确定从头重新制作吗？");
        if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        try {
            resumableService.discard(model.destination());
            checkpointAvailable = false;
            discardCheckpointButton.setDisable(true);
            statusLabel.setText("断点已清除；源文件和已有输出文件均未改动。");
            updateReadyState();
        } catch (IOException failure) {
            statusLabel.setText("无法清除断点；请确认没有另一个批处理任务正在运行。");
        }
    }

    @FXML
    void startButtonClick() {
        var readinessError = model.readinessError();
        if (readinessError.isPresent()) {
            statusLabel.setText(readinessError.get());
            return;
        }
        setRunning(true);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        statusLabel.setText("正在准备安全的只读导入……");
        issuesArea.setText("任务运行中。源文件不会被修改。");

        Task<XqbBatchService.BatchReport> task = new Task<>() {
            @Override
            protected XqbBatchService.BatchReport call() throws Exception {
                return resumableService.buildOrResume(model.sources(), model.destination(),
                        cancellationRequested::get,
                        progress -> Platform.runLater(() -> applyProgress(this, progress)));
            }
        };
        cancellationRequested = new AtomicBoolean();
        currentTask = task;
        task.setOnSucceeded(ignored -> finishSuccess(task, task.getValue()));
        task.setOnFailed(ignored -> finishFailure(task, task.getException()));
        Thread worker = new Thread(task, "pikadesk-xqb-batch");
        worker.setDaemon(true);
        worker.start();
    }

    @FXML
    void cancelButtonClick() {
        requestCancellation();
    }

    @FXML
    void closeButtonClick() {
        if (currentTask != null) {
            requestCancellation();
            return;
        }
        ((Stage) sourceList.getScene().getWindow()).close();
    }

    private void applyProgress(Task<XqbBatchService.BatchReport> task,
                               XqbBatchService.Progress progress) {
        if (currentTask != task) return;
        statusLabel.setText(BookBatchViewModel.progressText(progress));
    }

    private void finishSuccess(Task<XqbBatchService.BatchReport> task,
                               XqbBatchService.BatchReport report) {
        if (currentTask != task) return;
        currentTask = null;
        cancellationRequested = null;
        checkpointAvailable = false;
        setRunning(false);
        progressBar.setProgress(1);
        statusLabel.setText("制作完成：" + model.destination().getFileName());
        issuesArea.setText(BookBatchViewModel.reportText(report));
        discardCheckpointButton.setDisable(true);
    }

    private void finishFailure(Task<XqbBatchService.BatchReport> task, Throwable failure) {
        if (currentTask != task) return;
        currentTask = null;
        cancellationRequested = null;
        setRunning(false);
        progressBar.setProgress(0);
        String code = failure instanceof XqbBatchService.BookBatchException batchFailure
                ? batchFailure.code() : "UNKNOWN";
        statusLabel.setText(BookBatchViewModel.failureText(code));
        issuesArea.setText("CANCELLED".equals(code)
                ? "任务已安全暂停。重新选择同一输出文件会载入源文件和已完成断点。"
                : "错误代码：" + code + System.lineSeparator()
                + "可调整输入后重试；已有输出文件保持不变。");
        refreshCheckpointAvailability();
    }

    private void requestCancellation() {
        if (currentTask == null || cancellationRequested == null) return;
        cancellationRequested.set(true);
        statusLabel.setText("正在取消，请等待当前记录安全结束……");
        cancelButton.setDisable(true);
    }

    private void setRunning(boolean running) {
        addSourceButton.setDisable(running || checkpointAvailable);
        removeSourceButton.setDisable(running || checkpointAvailable
                || sourceList.getItems().isEmpty());
        destinationButton.setDisable(running);
        discardCheckpointButton.setDisable(running || !checkpointAvailable);
        startButton.setDisable(running || model.readinessError().isPresent());
        cancelButton.setDisable(!running);
        sourceList.setDisable(running);
    }

    private void updateReadyState() {
        startButton.setDisable(currentTask != null || model.readinessError().isPresent());
        addSourceButton.setDisable(currentTask != null || checkpointAvailable);
        removeSourceButton.setDisable(currentTask != null || checkpointAvailable
                || sourceList.getItems().isEmpty());
    }

    private Window owner() {
        return sourceList.getScene().getWindow();
    }

    private void loadRecoveryStatus() {
        try {
            XqbResumableBatchService.RecoveryStatus recovery =
                    resumableService.inspect(model.destination());
            checkpointAvailable = recovery.available();
            discardCheckpointButton.setDisable(!checkpointAvailable);
            if (recovery.available()) {
                model.replaceSources(recovery.sources());
                sourceList.getItems().setAll(model.sources());
                statusLabel.setText("已载入断点：完成 " + recovery.completedSources()
                        + "/" + recovery.sourceCount() + " 个源文件，已扫描 "
                        + recovery.scannedRows() + " 行。点击开始将继续。");
            } else {
                statusLabel.setText("已选择输出文件。开始前不会写入。");
            }
        } catch (XqbBatchService.BookBatchException failure) {
            checkpointAvailable = true;
            discardCheckpointButton.setDisable(false);
            statusLabel.setText(BookBatchViewModel.failureText(failure.code()));
        } catch (IOException failure) {
            checkpointAvailable = false;
            discardCheckpointButton.setDisable(true);
            statusLabel.setText("无法读取该输出文件的恢复状态。");
        }
    }

    private void refreshCheckpointAvailability() {
        try {
            checkpointAvailable = model.destination() != null
                    && resumableService.inspect(model.destination()).available();
        } catch (IOException failure) {
            checkpointAvailable = true;
        }
        discardCheckpointButton.setDisable(!checkpointAvailable);
        updateReadyState();
    }

    private void setInitialDirectory(FileChooser chooser) {
        Path directory = model.sources().isEmpty()
                ? Path.of(PathUtils.getJarPath()) : model.sources().getFirst().getParent();
        if (directory != null && Files.isDirectory(directory)) {
            chooser.setInitialDirectory(directory.toFile());
        }
    }
}
