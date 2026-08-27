package com.sojourners.chess.manual;

import com.sojourners.chess.manual.adapter.ManualDocument;
import com.sojourners.chess.model.ManualRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ManualFileGatewayTest {

    @TempDir
    Path temporaryDirectory;

    @ParameterizedTest
    @EnumSource(ManualFileGateway.Format.class)
    void savesAndOpensEveryUiFormatThroughTheBoundedAdapters(
            ManualFileGateway.Format format) throws Exception {
        ChessManual source = format == ManualFileGateway.Format.XQF
                ? linearManual() : branchingManual();
        Path file = temporaryDirectory.resolve("round-trip."
                + format.extension().toUpperCase());
        ManualFileGateway gateway = new ManualFileGateway();

        ManualFileGateway.SaveResult saved = gateway.save(source,
                ManualDocument.Result.RED_WIN, file);
        ManualFileGateway.OpenResult opened = gateway.open(file);

        assertEquals(format, saved.format());
        assertEquals(format, opened.format());
        assertEquals(ManualDocument.Result.RED_WIN, opened.result());
        assertEquals("界面接线", opened.manual().getName());
        ManualRecord openedHead = opened.manual().getHead();
        assertFalse(openedHead.getList().get(0).getCnMove().isBlank());
        if (format == ManualFileGateway.Format.XQF) {
            assertEquals("b0c2", openedHead.getList().get(0).getMove());
        } else {
            assertEquals(2, opened.manual().getHead().getList().size());
            assertEquals(Set.of("b0c2", "h0g2"), openedHead.getList().stream()
                    .map(ManualRecord::getMove).collect(java.util.stream.Collectors.toSet()));
            assertEquals("h0g2", openedHead.getList().get(openedHead.getNext()).getMove());
            assertEquals("根注释", openedHead.getRemark());
        }
    }

    @Test
    void pgnMapsTheUiTitleToEventAndBack() throws Exception {
        Path file = temporaryDirectory.resolve("event.pgn");
        ManualFileGateway gateway = new ManualFileGateway();

        gateway.save(linearManual(), ManualDocument.Result.DRAW, file);
        String text = Files.readString(file, StandardCharsets.UTF_8);
        ManualFileGateway.OpenResult opened = gateway.open(file);

        assertTrue(text.contains("[Event \"界面接线\"]"));
        assertFalse(text.contains("[Title \"界面接线\"]"));
        assertEquals("界面接线", opened.manual().getName());
        assertEquals(ManualDocument.Result.DRAW, opened.result());
    }

    @Test
    void retainedMetadataSurvivesAnOpenAndSaveSession() throws Exception {
        Path source = temporaryDirectory.resolve("metadata.pgn");
        Path target = temporaryDirectory.resolve("metadata.txq");
        Files.writeString(source, """
                [Game \"Chinese Chess\"]
                [Event \"界面接线\"]
                [Annotator \"测试者\"]
                [TimeControl \"60+1\"]
                [Result \"*\"]
                [Format \"ICCS\"]

                1. B0-C2 *
                """, StandardCharsets.UTF_8);
        ManualFileGateway gateway = new ManualFileGateway();

        ManualFileGateway.OpenResult opened = gateway.open(source);
        gateway.save(opened.manual(), opened.result(), opened.metadata(), target);
        ManualFileGateway.OpenResult reopened = gateway.open(target);

        assertEquals("测试者", reopened.metadata().get("Annotator"));
        assertEquals("60+1", reopened.metadata().get("TimeControl"));
        assertEquals(Map.of("Annotator", "测试者", "Event", "界面接线",
                "TimeControl", "60+1"), opened.metadata());
    }

    @Test
    void cbrCandidateWriterWarningReachesTheUiBoundary() throws Exception {
        Path file = temporaryDirectory.resolve("warning.cbr");

        ManualFileGateway.SaveResult saved = new ManualFileGateway().save(
                branchingManual(), ManualDocument.Result.ONGOING, file);

        assertEquals(List.of("UNVERIFIED_EXTERNAL_WRITER"), saved.notices().stream()
                .map(ManualFileGateway.Notice::code).toList());
    }

    @Test
    void unsupportedExtensionsFailBeforeReadingOrWriting() throws Exception {
        Path file = temporaryDirectory.resolve("manual.txt");
        Files.writeString(file, "ORIGINAL", StandardCharsets.US_ASCII);
        ManualFileGateway gateway = new ManualFileGateway();

        ManualFileGateway.ManualFileException openFailure = assertThrows(
                ManualFileGateway.ManualFileException.class, () -> gateway.open(file));
        ManualFileGateway.ManualFileException saveFailure = assertThrows(
                ManualFileGateway.ManualFileException.class, () -> gateway.save(
                        linearManual(), ManualDocument.Result.ONGOING, file));

        assertEquals("UNSUPPORTED_EXTENSION", openFailure.code());
        assertEquals("UNSUPPORTED_EXTENSION", saveFailure.code());
        assertEquals("ORIGINAL", Files.readString(file, StandardCharsets.US_ASCII));
    }

    @Test
    void rejectedExportCannotTruncateAnExistingFileOrLeaveATemporaryFile()
            throws Exception {
        Path file = temporaryDirectory.resolve("preserved.xqf");
        byte[] original = "ORIGINAL".getBytes(StandardCharsets.US_ASCII);
        Files.write(file, original);

        assertThrows(IOException.class, () -> new ManualFileGateway().save(
                branchingManual(), ManualDocument.Result.ONGOING, file));

        assertArrayEquals(original, Files.readAllBytes(file));
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(List.of("preserved.xqf"), files
                    .map(path -> path.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void desktopManualHandleRoutesFileOpenAndSaveThroughTheSafeGateway()
            throws Exception {
        Class<?> handle = Class.forName(
                "com.sojourners.chess.controller.handle.ChessManualHandle");
        String source = Files.readString(Path.of("src", "main", "java", "com",
                "sojourners", "chess", "controller", "handle", "ChessManualHandle.java"));

        assertEquals(ManualFileGateway.class,
                handle.getDeclaredField("manualFileGateway").getType());
        assertTrue(source.contains("manualFileGateway.open(file.toPath())"));
        assertTrue(source.contains("manualFileGateway.save("));
        assertFalse(source.contains("manualServices.get(ext).openChessManual"));
        assertFalse(source.contains("manualServices.get(ext).saveChessManual"));
    }

    private static ChessManual linearManual() {
        ChessManual manual = baseManual();
        ManualRecord first = new ManualRecord(1, "b0c2", "");
        first.setRemark("第一步");
        ManualRecord second = new ManualRecord(2, "b9c7", "");
        first.getList().add(second);
        manual.getHead().getList().add(first);
        return manual;
    }

    private static ChessManual branchingManual() {
        ChessManual manual = baseManual();
        manual.getHead().setRemark("根注释");
        manual.getHead().getList().add(new ManualRecord(1, "b0c2", ""));
        manual.getHead().getList().add(new ManualRecord(1, "h0g2", ""));
        manual.getHead().setNext(1);
        return manual;
    }

    private static ChessManual baseManual() {
        ChessManual manual = new ChessManual();
        manual.setName("界面接线");
        manual.setDate("2026-08-27");
        manual.setCity("上海");
        manual.setRed("红方");
        manual.setBlack("黑方");
        manual.setFenCode(ManualDocument.STANDARD_FEN);
        manual.setHead(new ManualRecord(0, "开始局面", (Integer) null));
        return manual;
    }
}
