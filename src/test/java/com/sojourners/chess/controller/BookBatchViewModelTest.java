package com.sojourners.chess.controller;

import com.sojourners.chess.book.XqbBatchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BookBatchViewModelTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsOnlyXqbSourcesAndDeduplicatesNormalizedPaths() {
        BookBatchViewModel model = new BookBatchViewModel();
        Path first = temporaryDirectory.resolve("first.XQB");
        Path duplicate = temporaryDirectory.resolve("folder").resolve("..").resolve("first.XQB");
        Path unsupported = temporaryDirectory.resolve("book.obk");

        BookBatchViewModel.AddResult result = model.addSources(
                List.of(first, duplicate, unsupported));

        assertEquals(1, result.addedCount());
        assertEquals(List.of(unsupported.toAbsolutePath().normalize()), result.rejected());
        assertEquals(List.of(first.toAbsolutePath().normalize()), model.sources());
    }

    @Test
    void requiresSourcesAndANonOverlappingXqbDestination() {
        BookBatchViewModel model = new BookBatchViewModel();
        Path source = temporaryDirectory.resolve("source.xqb");

        assertEquals("请先添加至少一个 XQB v1 源文件。", model.readinessError().orElseThrow());
        model.addSources(List.of(source));
        assertEquals("请选择输出 XQB 文件。", model.readinessError().orElseThrow());
        model.setDestination(source);
        assertEquals("输出文件不能同时作为源文件。", model.readinessError().orElseThrow());
        model.setDestination(temporaryDirectory.resolve("output.xqb"));
        assertTrue(model.readinessError().isEmpty());
    }

    @Test
    void rejectsUnsupportedDestinationExtension() {
        BookBatchViewModel model = new BookBatchViewModel();

        assertThrows(IllegalArgumentException.class,
                () -> model.setDestination(temporaryDirectory.resolve("output.db")));
    }

    @Test
    void restoresTheExactSourceListFromARecoveryPlan() {
        BookBatchViewModel model = new BookBatchViewModel();
        Path stale = temporaryDirectory.resolve("stale.xqb");
        Path first = temporaryDirectory.resolve("first.xqb");
        Path second = temporaryDirectory.resolve("second.XQB");
        model.addSources(List.of(stale));

        model.replaceSources(List.of(first, second));

        assertEquals(List.of(first.toAbsolutePath().normalize(),
                second.toAbsolutePath().normalize()), model.sources());
        assertThrows(IllegalArgumentException.class,
                () -> model.replaceSources(List.of(temporaryDirectory.resolve("bad.obk"))));
    }

    @Test
    void formatsProgressAndBoundedReportForOrdinaryUsers() {
        Path source = temporaryDirectory.resolve("source.xqb").toAbsolutePath().normalize();
        XqbBatchService.Progress progress = new XqbBatchService.Progress(
                XqbBatchService.Phase.READING, 0, 2,
                12, 9, 2, 1, 0, source);
        XqbBatchService.Issue issue = new XqbBatchService.Issue(
                "INVALID_MOVE", "着法坐标无效", source, 7);
        XqbBatchService.BatchReport report = new XqbBatchService.BatchReport(
                2, 12, 9, 2, 1, List.of(issue), 3);

        assertEquals("正在读取 1/2：source.xqb｜扫描 12｜有效 9｜重复 2｜拒绝 1",
                BookBatchViewModel.progressText(progress));
        XqbBatchService.Progress checkpoint = new XqbBatchService.Progress(
                XqbBatchService.Phase.CHECKPOINTING, 1, 2,
                12, 9, 2, 1, 0, source);
        assertEquals("已保存断点 1/2：source.xqb｜扫描 12｜有效 9｜重复 2｜拒绝 1",
                BookBatchViewModel.progressText(checkpoint));
        String reportText = BookBatchViewModel.reportText(report);
        assertTrue(reportText.contains("扫描 12，写入 9，精确重复 2，拒绝 1"));
        assertTrue(reportText.contains("INVALID_MOVE"));
        assertTrue(reportText.contains("另有 3 条问题未展开"));
    }

    @Test
    void mapsFailuresWithoutExposingStackTraces() {
        assertEquals("任务已暂停，源文件和原输出文件均未改动；已完成断点可继续。",
                BookBatchViewModel.failureText("CANCELLED"));
        assertEquals("文件结构不是受支持的 XQB v1。",
                BookBatchViewModel.failureText("UNSUPPORTED_SCHEMA"));
        assertEquals("源文件或任务参数已变化，不能使用旧断点。",
                BookBatchViewModel.failureText("CHECKPOINT_MISMATCH"));
        assertFalse(BookBatchViewModel.failureText("SQLITE_FAILURE").contains("Exception"));
    }
}
