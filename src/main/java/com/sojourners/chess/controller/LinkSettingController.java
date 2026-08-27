package com.sojourners.chess.controller;

import com.sojourners.chess.App;
import com.sojourners.chess.automation.BoardCoordinateMapper;
import com.sojourners.chess.config.Properties;
import com.sojourners.chess.linker.profile.ConnectionProfile;
import com.sojourners.chess.linker.profile.ConnectionWizardState;
import com.sojourners.chess.linker.profile.RecognitionCompatibility;
import com.sojourners.chess.util.DialogUtils;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;

/** Controller for both legacy link timings and the fail-closed connection wizard. */
public class LinkSettingController {

    @FXML private Label stepLabel;
    @FXML private Label targetLabel;
    @FXML private Label verificationLabel;
    @FXML private TextField profileName;
    @FXML private TextField boardX;
    @FXML private TextField boardY;
    @FXML private TextField boardWidth;
    @FXML private TextField boardHeight;
    @FXML private ComboBox<String> orientation;
    @FXML private ComboBox<String> themeId;
    @FXML private ComboBox<String> modelId;
    @FXML private TextField linkScanTime;
    @FXML private TextField linkThreadNum;
    @FXML private TextField mouseClickDelay;
    @FXML private TextField mouseMoveDelay;
    @FXML private CheckBox localAuthorization;
    @FXML private Button dryRunButton;
    @FXML private Button completeButton;
    @FXML private Button exportRedactedButton;
    @FXML private Button exportLocalButton;

    private final Properties prop = Properties.getInstance();
    private ConnectionWizardState wizard;
    private ConnectionProfile approvedProfile;
    private boolean verifiedFieldsDirty;

    @FXML
    public void initialize() {
        orientation.getItems().setAll("红方在下", "黑方在下");
        orientation.setValue("红方在下");
        themeId.getItems().setAll(RecognitionCompatibility.CLASSIC_THEME);
        themeId.setValue(RecognitionCompatibility.CLASSIC_THEME);
        modelId.getItems().setAll(RecognitionCompatibility.XIANGQI_YOLO11_MODEL);
        modelId.setValue(RecognitionCompatibility.XIANGQI_YOLO11_MODEL);
        linkScanTime.setText(String.valueOf(prop.getLinkScanTime()));
        linkThreadNum.setText(String.valueOf(prop.getLinkThreadNum()));
        mouseClickDelay.setText(String.valueOf(prop.getMouseClickDelay()));
        mouseMoveDelay.setText(String.valueOf(prop.getMouseMoveDelay()));
        watchVerifiedField(profileName);
        watchVerifiedField(boardX);
        watchVerifiedField(boardY);
        watchVerifiedField(boardWidth);
        watchVerifiedField(boardHeight);
        watchVerifiedField(linkScanTime);
        watchVerifiedField(linkThreadNum);
        watchVerifiedField(mouseClickDelay);
        watchVerifiedField(mouseMoveDelay);
        orientation.valueProperty().addListener((observable, oldValue, newValue) -> markDirty());
        themeId.valueProperty().addListener((observable, oldValue, newValue) -> markDirty());
        modelId.valueProperty().addListener((observable, oldValue, newValue) -> markDirty());
        showNoTarget();
        localAuthorization.selectedProperty().addListener(
                (observable, oldValue, newValue) -> updateControls());
    }

    public void setWizardState(ConnectionWizardState selectedWizard) {
        wizard = selectedWizard;
        approvedProfile = null;
        populateFromWizard();
    }

    public ConnectionProfile getApprovedProfile() {
        return approvedProfile;
    }

    @FXML
    void cancelButtonClick(ActionEvent event) {
        approvedProfile = null;
        App.closeLinkSetting();
    }

    @FXML
    void dryRunButtonClick(ActionEvent event) {
        if (wizard == null || wizard.target() == null) {
            DialogUtils.showWarningDialog("尚未选择窗口", "请关闭此窗口，点击主界面的“连线”，再用准星选择本地棋盘窗口。");
            return;
        }
        try {
            wizard.setProfileName(profileName.getText());
            wizard.setBoardBounds(new BoardCoordinateMapper.BoardBounds(
                    number(boardX, "棋盘 X"), number(boardY, "棋盘 Y"),
                    number(boardWidth, "棋盘宽度"), number(boardHeight, "棋盘高度")));
            wizard.setDisplay(selectedOrientation(), themeId.getValue());
            wizard.setModelAndTiming(modelId.getValue(),
                    number(linkScanTime, "扫描间隔"),
                    number(linkThreadNum, "识别线程"),
                    number(mouseClickDelay, "点击间隔"),
                    number(mouseMoveDelay, "走子间隔"));
            BoardCoordinateMapper.MovePoints points = wizard.verifyDryRun(
                    wizard.target(), new BoardCoordinateMapper.Move(0, 0, 0, 1));
            verificationLabel.setText("干运行通过：a0 → a1 映射为 ("
                    + points.from().x() + ", " + points.from().y() + ") → ("
                    + points.to().x() + ", " + points.to().y()
                    + ")；未发送任何鼠标事件。请核对坐标后勾选授权。");
            stepLabel.setText("5/5 已验证 · 等待明确授权");
            verifiedFieldsDirty = false;
            updateControls();
        } catch (RuntimeException failure) {
            approvedProfile = null;
            verificationLabel.setText("干运行未通过：" + message(failure));
            updateControls();
            DialogUtils.showErrorDialog("连接配置无效", message(failure));
        }
    }

    @FXML
    void okButtonClick(ActionEvent event) {
        if (wizard == null || wizard.target() == null) {
            try {
                saveLegacyTimings();
                App.closeLinkSetting();
            } catch (RuntimeException failure) {
                DialogUtils.showErrorDialog("设置无效", message(failure));
            }
            return;
        }
        if (!wizard.canEnableAutomation() || verifiedFieldsDirty
                || !localAuthorization.isSelected()) {
            DialogUtils.showWarningDialog("尚未完成", "必须先通过干运行，并确认仅用于本地、离线或明确授权的棋盘。");
            return;
        }
        approvedProfile = wizard.profile();
        saveLegacyTimings();
        App.closeLinkSetting();
    }

    @FXML
    void importButtonClick(ActionEvent event) {
        FileChooser chooser = profileChooser("导入连接配置");
        File file = chooser.showOpenDialog(profileName.getScene().getWindow());
        if (file == null) return;
        try (InputStream input = Files.newInputStream(file.toPath())) {
            ConnectionProfile imported = ConnectionProfile.read(input);
            ConnectionWizardState importedWizard = ConnectionWizardState.fromImported(imported);
            if (wizard != null && wizard.target() != null) {
                importedWizard.selectTarget(wizard.target());
            }
            wizard = importedWizard;
            approvedProfile = null;
            localAuthorization.setSelected(false);
            populateFromWizard();
            verificationLabel.setText("配置已安全导入；授权和干运行状态已清除，必须重新验证当前窗口。");
        } catch (IOException | RuntimeException failure) {
            DialogUtils.showErrorDialog("导入失败", message(failure));
        }
    }

    @FXML
    void exportRedactedButtonClick(ActionEvent event) {
        exportProfile(ConnectionProfile.ExportMode.REDACTED);
    }

    @FXML
    void exportLocalButtonClick(ActionEvent event) {
        if (!DialogUtils.showConfirmDialog("导出本机配置",
                "本机配置可能包含程序路径，只应保存在自己的电脑。继续吗？")) return;
        exportProfile(ConnectionProfile.ExportMode.LOCAL_FULL);
    }

    private void exportProfile(ConnectionProfile.ExportMode mode) {
        if (wizard == null || !wizard.canEnableAutomation()) {
            DialogUtils.showWarningDialog("无法导出", "请先完成当前窗口的干运行验证。");
            return;
        }
        FileChooser chooser = profileChooser(mode == ConnectionProfile.ExportMode.REDACTED
                ? "导出脱敏配置" : "导出本机配置");
        chooser.setInitialFileName(safeFileName(profileName.getText()) + ".pdcp");
        File file = chooser.showSaveDialog(profileName.getScene().getWindow());
        if (file == null) return;
        try (OutputStream output = Files.newOutputStream(file.toPath())) {
            ConnectionProfile.write(wizard.profile(), output, mode);
            verificationLabel.setText(mode == ConnectionProfile.ExportMode.REDACTED
                    ? "脱敏配置已导出：本机路径与授权状态均已移除。"
                    : "本机配置已导出；导入后仍需重新选择窗口并干运行。");
        } catch (IOException | RuntimeException failure) {
            DialogUtils.showErrorDialog("导出失败", message(failure));
        }
    }

    private void populateFromWizard() {
        if (wizard == null || wizard.target() == null) {
            showNoTarget();
            return;
        }
        ConnectionWizardState.TargetObservation target = wizard.target();
        profileName.setText(wizard.profileName());
        targetLabel.setText(target.titleHint() + " · " + target.executableName()
                + " · " + target.clientArea().width() + "×"
                + target.clientArea().height() + " · " + target.dpi() + " DPI");
        if (wizard.boardBounds() != null) {
            boardX.setText(String.valueOf(wizard.boardBounds().x()));
            boardY.setText(String.valueOf(wizard.boardBounds().y()));
            boardWidth.setText(String.valueOf(wizard.boardBounds().width()));
            boardHeight.setText(String.valueOf(wizard.boardBounds().height()));
        }
        if (wizard.orientation() != null) {
            orientation.setValue(wizard.orientation()
                    == BoardCoordinateMapper.Orientation.RED_AT_BOTTOM
                    ? "红方在下" : "黑方在下");
        }
        if (wizard.themeId() != null) themeId.setValue(wizard.themeId());
        if (wizard.modelId() != null) modelId.setValue(wizard.modelId());
        if (wizard.scanIntervalMillis() > 0) {
            linkScanTime.setText(String.valueOf(wizard.scanIntervalMillis()));
            linkThreadNum.setText(String.valueOf(wizard.recognitionThreads()));
            mouseClickDelay.setText(String.valueOf(wizard.clickDelayMillis()));
            mouseMoveDelay.setText(String.valueOf(wizard.moveDelayMillis()));
        }
        stepLabel.setText(wizard.boardBounds() == null
                ? "2/5 需要填写棋盘边界" : "4/5 请核对并执行干运行");
        verificationLabel.setText("自动点击仍为关闭状态。干运行只计算两个落点，不会点击窗口。");
        verifiedFieldsDirty = false;
        updateControls();
    }

    private void showNoTarget() {
        targetLabel.setText("未选择目标窗口");
        stepLabel.setText("1/5 请从主界面点击“连线”后选择窗口");
        verificationLabel.setText("此处可调整旧版扫描参数；没有当前目标时不能授权自动点击。");
        verifiedFieldsDirty = false;
        updateControls();
    }

    private void updateControls() {
        boolean hasTarget = wizard != null && wizard.target() != null;
        dryRunButton.setDisable(!hasTarget);
        boolean ready = hasTarget && wizard.canEnableAutomation() && !verifiedFieldsDirty;
        completeButton.setText(hasTarget ? "完成并允许本次连接" : "保存参数");
        completeButton.setDisable(hasTarget && (!ready || !localAuthorization.isSelected()));
        exportRedactedButton.setDisable(!ready);
        exportLocalButton.setDisable(!ready);
    }

    private void watchVerifiedField(TextInputControl field) {
        field.textProperty().addListener((observable, oldValue, newValue) -> markDirty());
    }

    private void markDirty() {
        if (wizard != null && wizard.canEnableAutomation()) {
            verifiedFieldsDirty = true;
            approvedProfile = null;
            verificationLabel.setText("配置已修改；必须重新执行干运行。");
            stepLabel.setText("4/5 配置已变化 · 需要重新验证");
            updateControls();
        }
    }

    private void saveLegacyTimings() {
        int scan = number(linkScanTime, "扫描间隔");
        int threads = number(linkThreadNum, "识别线程");
        int click = number(mouseClickDelay, "点击间隔");
        int move = number(mouseMoveDelay, "走子间隔");
        if (scan < 20 || scan > 10_000 || threads < 1 || threads > 64
                || click < 0 || click > 2_000 || move < 0 || move > 2_000) {
            throw new IllegalArgumentException("参数超出安全范围");
        }
        prop.setLinkScanTime(scan);
        prop.setLinkThreadNum(threads);
        prop.setMouseClickDelay(click);
        prop.setMouseMoveDelay(move);
    }

    private BoardCoordinateMapper.Orientation selectedOrientation() {
        return "黑方在下".equals(orientation.getValue())
                ? BoardCoordinateMapper.Orientation.BLACK_AT_BOTTOM
                : BoardCoordinateMapper.Orientation.RED_AT_BOTTOM;
    }

    private int number(TextField field, String label) {
        try {
            return Integer.parseInt(field.getText().trim());
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(label + "必须是整数", failure);
        }
    }

    private FileChooser profileChooser(String title) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("PikaDesk 连接配置 (*.pdcp)", "*.pdcp"));
        return chooser;
    }

    private String safeFileName(String source) {
        String safe = source == null ? "connection" : source.trim()
                .replaceAll("[\\\\/:*?\"<>|]", "_");
        return safe.isBlank() ? "connection" : safe;
    }

    private String message(Throwable failure) {
        String detail = failure.getMessage();
        return detail == null || detail.isBlank()
                ? failure.getClass().getSimpleName() : detail;
    }
}
