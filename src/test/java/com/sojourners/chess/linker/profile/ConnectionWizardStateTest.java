package com.sojourners.chess.linker.profile;

import com.sojourners.chess.automation.BoardCoordinateMapper;
import com.sojourners.chess.linker.LinkMode;
import com.sojourners.chess.testboard.LocalTestBoardState;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConnectionWizardStateTest {

    @Test
    void carriesAnExplicitConnectionCapabilityAndDefaultsToReadOnly() {
        assertEquals(LinkMode.READ_ONLY_ADVISOR,
                new ConnectionWizardState().connectionMode());
        assertEquals(LinkMode.AUTHORIZED_AUTOMATION,
                new ConnectionWizardState(LinkMode.AUTHORIZED_AUTOMATION)
                        .connectionMode());
    }

    @Test
    void cannotEnableAutomationUntilEveryStepAndDryRunPasses() {
        ConnectionWizardState wizard = new ConnectionWizardState();
        assertFalse(wizard.canEnableAutomation());
        assertThrows(IllegalStateException.class, wizard::profile);

        wizard.selectTarget(target(11, 120, 80, 960, 720, 144));
        wizard.setBoardBounds(new BoardCoordinateMapper.BoardBounds(80, 50, 800, 620));
        wizard.setDisplay(
                BoardCoordinateMapper.Orientation.RED_AT_BOTTOM, "CLASSIC");
        wizard.setModelAndTiming("yolo11-xiangqi", 100, 2, 2, 0);
        assertFalse(wizard.canEnableAutomation());

        BoardCoordinateMapper.MovePoints points = wizard.verifyDryRun(
                target(11, 120, 80, 960, 720, 144),
                new BoardCoordinateMapper.Move(0, 0, 0, 1));

        assertTrue(wizard.canEnableAutomation());
        assertEquals(ConnectionWizardState.Step.READY, wizard.step());
        assertTrue(points.from().x() >= 120 && points.to().y() >= 80);
        assertTrue(wizard.profile().dryRunVerified());
    }

    @Test
    void rejectsKnownPublicRankedPlatformTargetsBeforeCalibration() {
        ConnectionWizardState jjWizard = new ConnectionWizardState();
        ConnectionWizardState.TargetObservation jjTarget = new ConnectionWizardState.TargetObservation(
                "windows:pid=77;hwnd=700", 1,
                "JJGame.exe", "Chrome_WidgetWin_1", "JJ象棋 排位赛",
                "C:\\Games\\JJ\\JJGame.exe",
                new BoardCoordinateMapper.ClientArea(10, 20, 960, 720),
                96, true, true);

        IllegalArgumentException jjFailure = assertThrows(
                IllegalArgumentException.class,
                () -> jjWizard.selectTarget(jjTarget));

        assertTrue(jjFailure.getMessage().contains("公共平台"));
        assertEquals(ConnectionWizardState.Step.TARGET, jjWizard.step());
        assertFalse(jjWizard.canEnableAutomation());

        ConnectionWizardState tiantianWizard = new ConnectionWizardState();
        ConnectionWizardState.TargetObservation tiantianTarget = new ConnectionWizardState.TargetObservation(
                "windows:pid=78;hwnd=701", 1,
                "天天象棋.exe", "TXGuiFoundation", "中国象棋",
                "C:\\Games\\Tencent\\天天象棋.exe",
                new BoardCoordinateMapper.ClientArea(10, 20, 960, 720),
                96, true, true);

        IllegalArgumentException tiantianFailure = assertThrows(
                IllegalArgumentException.class,
                () -> tiantianWizard.selectTarget(tiantianTarget));

        assertTrue(tiantianFailure.getMessage().contains("公共平台"));
        assertEquals(ConnectionWizardState.Step.TARGET, tiantianWizard.step());
        assertFalse(tiantianWizard.canEnableAutomation());
    }

    @Test
    void rejectsRecognitionCombinationsThatHaveNotPassedEndToEndValidation() {
        ConnectionWizardState wizard = new ConnectionWizardState();
        wizard.selectTarget(target(11, 120, 80, 960, 720, 144));
        wizard.setBoardBounds(new BoardCoordinateMapper.BoardBounds(80, 50, 800, 620));
        wizard.setDisplay(
                BoardCoordinateMapper.Orientation.RED_AT_BOTTOM, "DARK");

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> wizard.setModelAndTiming(
                        "yolo11-xiangqi", 100, 2, 2, 0));

        assertTrue(failure.getMessage().contains("尚未通过端到端验证"));
        assertEquals(ConnectionWizardState.Step.MODEL, wizard.step());
        assertFalse(wizard.canEnableAutomation());
    }

    @Test
    void importedAutoThemeMustBeRevalidatedAsAConcreteSupportedTheme() throws IOException {
        String legacy = """
                PDCP 1
                name=5pys5Zyw5qOL55uY
                executable=TG9jYWxCb2FyZC5leGU
                windowClass=SmF2YUZY
                title=5pys5Zyw5rWL6K-V5qOL55uY
                clientWidth=960
                clientHeight=720
                boardX=80
                boardY=50
                boardWidth=800
                boardHeight=620
                dpi=144
                orientation=RED_AT_BOTTOM
                model=eW9sbzExLXhpYW5ncWk
                scanMillis=100
                threads=2
                clickMillis=2
                moveMillis=0
                """;
        ConnectionProfile imported = ConnectionProfile.read(
                new ByteArrayInputStream(legacy.getBytes(StandardCharsets.UTF_8)));
        ConnectionWizardState wizard = ConnectionWizardState.fromImported(imported);
        wizard.selectTarget(target(11, 120, 80, 960, 720, 144));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> wizard.setModelAndTiming(
                        "yolo11-xiangqi", 100, 2, 2, 0));

        assertTrue(failure.getMessage().contains("CLASSIC"));
        assertThrows(IllegalArgumentException.class, () -> wizard.verifyDryRun(
                target(11, 120, 80, 960, 720, 144),
                new BoardCoordinateMapper.Move(0, 0, 0, 1)));
        assertFalse(wizard.canEnableAutomation());
    }

    @Test
    void targetGeometryOrRevisionChangeRequiresRecalibration() {
        ConnectionWizardState wizard = readyWizard();

        ConnectionWizardState.TargetCheck check = wizard.observeTarget(
                target(12, 120, 80, 960, 720, 144));

        assertTrue(check.recalibrationRequired());
        assertTrue(check.message().contains("重新校准"));
        assertEquals(ConnectionWizardState.Step.RECALIBRATION_REQUIRED, wizard.step());
        assertFalse(wizard.canEnableAutomation());
    }

    @Test
    void redactedExportNeverContainsTheLocalExecutablePath() throws IOException {
        ConnectionProfile profile = readyWizard().profile();
        ByteArrayOutputStream redactedBytes = new ByteArrayOutputStream();
        ConnectionProfile.write(
                profile, redactedBytes, ConnectionProfile.ExportMode.REDACTED);
        String encoded = redactedBytes.toString(StandardCharsets.UTF_8);
        ConnectionProfile redacted = ConnectionProfile.read(
                new ByteArrayInputStream(redactedBytes.toByteArray()));

        assertFalse(encoded.contains("C:\\Private\\LocalBoard.exe"));
        assertTrue(redacted.target().localExecutablePath().isEmpty());
        assertFalse(redacted.dryRunVerified());

        ByteArrayOutputStream localBytes = new ByteArrayOutputStream();
        ConnectionProfile.write(profile, localBytes, ConnectionProfile.ExportMode.LOCAL_FULL);
        ConnectionProfile local = ConnectionProfile.read(
                new ByteArrayInputStream(localBytes.toByteArray()));
        assertEquals("C:\\Private\\LocalBoard.exe",
                local.target().localExecutablePath());
    }

    @Test
    void migratesV1ProfilesToSafeUnverifiedTemplates() throws IOException {
        String legacy = """
                PDCP 1
                name=5pys5Zyw5qOL55uY
                executable=TG9jYWxCb2FyZC5leGU
                windowClass=SmF2YUZY
                title=5pys5Zyw5rWL6K-V5qOL55uY
                clientWidth=960
                clientHeight=720
                boardX=80
                boardY=50
                boardWidth=800
                boardHeight=620
                dpi=144
                orientation=RED_AT_BOTTOM
                model=eW9sbzExLXhpYW5ncWk
                scanMillis=100
                threads=2
                clickMillis=2
                moveMillis=0
                """;

        ConnectionProfile migrated = ConnectionProfile.read(
                new ByteArrayInputStream(legacy.getBytes(StandardCharsets.UTF_8)));
        ConnectionWizardState wizard = ConnectionWizardState.fromImported(migrated);

        assertEquals(ConnectionProfile.CURRENT_VERSION, migrated.schemaVersion());
        assertEquals("AUTO", migrated.themeId());
        assertFalse(migrated.dryRunVerified());
        assertEquals(ConnectionWizardState.Step.TARGET, wizard.step());
        assertFalse(wizard.canEnableAutomation());
    }

    @Test
    void rejectsOversizedUnknownOrDuplicateProfileFields() {
        byte[] oversized = new byte[70_000];
        java.util.Arrays.fill(oversized, (byte) 'a');
        assertThrows(IOException.class,
                () -> ConnectionProfile.read(new ByteArrayInputStream(oversized)));
        assertThrows(IOException.class, () -> ConnectionProfile.read(
                new ByteArrayInputStream("PDCP 2\nunknown=x\n"
                        .getBytes(StandardCharsets.UTF_8))));
        assertThrows(IOException.class, () -> ConnectionProfile.read(
                new ByteArrayInputStream("PDCP 2\nname=YQ\nname=Yg\n"
                        .getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void localTestBoardWizardEndToEndKeepsDryRunSideEffectFree() {
        LocalTestBoardState localBoard = new LocalTestBoardState();
        String initialFen = localBoard.fen();
        ConnectionWizardState wizard = new ConnectionWizardState();
        ConnectionWizardState.TargetObservation selected = target(
                21, 200, 100, 960, 720, 144);

        wizard.setProfileName("离线测试棋盘");
        wizard.selectTarget(selected);
        wizard.setBoardBounds(new BoardCoordinateMapper.BoardBounds(80, 50, 800, 620));
        wizard.setDisplay(BoardCoordinateMapper.Orientation.RED_AT_BOTTOM, "CLASSIC");
        wizard.setModelAndTiming("yolo11-xiangqi", 100, 2, 2, 0);
        BoardCoordinateMapper.MovePoints points = wizard.verifyDryRun(
                selected, new BoardCoordinateMapper.Move(0, 3, 0, 4));

        assertTrue(wizard.canEnableAutomation());
        assertEquals(initialFen, localBoard.fen());
        assertTrue(localBoard.receivedMoves().isEmpty());
        assertTrue(points.from().x() >= 200 && points.to().y() >= 100);

        assertEquals(LocalTestBoardState.ClickKind.SELECTED,
                localBoard.clickDisplaySquare(0, 6).kind());
        LocalTestBoardState.ClickResult moved = localBoard.clickDisplaySquare(0, 5);
        assertEquals(LocalTestBoardState.ClickKind.MOVED, moved.kind());
        assertEquals("a3a4", moved.move().orElseThrow().ucci());

        assertTrue(wizard.observeTarget(target(
                21, 200, 100, 1_000, 720, 144)).recalibrationRequired());
        assertFalse(wizard.canEnableAutomation());
    }

    private static ConnectionWizardState readyWizard() {
        ConnectionWizardState wizard = new ConnectionWizardState();
        wizard.selectTarget(target(11, 120, 80, 960, 720, 144));
        wizard.setBoardBounds(new BoardCoordinateMapper.BoardBounds(80, 50, 800, 620));
        wizard.setDisplay(
                BoardCoordinateMapper.Orientation.RED_AT_BOTTOM, "CLASSIC");
        wizard.setModelAndTiming("yolo11-xiangqi", 100, 2, 2, 0);
        wizard.verifyDryRun(target(11, 120, 80, 960, 720, 144),
                new BoardCoordinateMapper.Move(0, 0, 0, 1));
        return wizard;
    }

    private static ConnectionWizardState.TargetObservation target(
            long revision, int x, int y, int width, int height, int dpi) {
        return new ConnectionWizardState.TargetObservation(
                "windows:pid=42;hwnd=100", revision,
                "LocalBoard.exe", "JavaFX", "本地测试棋盘",
                "C:\\Private\\LocalBoard.exe",
                new BoardCoordinateMapper.ClientArea(x, y, width, height),
                dpi, true, true);
    }
}
