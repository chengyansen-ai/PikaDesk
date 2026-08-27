package com.sojourners.chess.controller;

import com.sojourners.chess.book.XqbBatchService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** UI state and user-facing copy for the XQB batch dialog. */
public final class BookBatchViewModel {

    private final LinkedHashSet<Path> sources = new LinkedHashSet<>();
    private Path destination;

    public AddResult addSources(List<Path> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        int before = sources.size();
        List<Path> rejected = new ArrayList<>();
        for (Path candidate : candidates) {
            Path normalized = normalize(candidate);
            if (isXqb(normalized)) sources.add(normalized);
            else rejected.add(normalized);
        }
        return new AddResult(sources.size() - before, rejected);
    }

    public void removeSources(List<Path> selected) {
        Objects.requireNonNull(selected, "selected").forEach(path ->
                sources.remove(normalize(path)));
    }

    public void replaceSources(List<Path> replacements) {
        Objects.requireNonNull(replacements, "replacements");
        LinkedHashSet<Path> normalized = new LinkedHashSet<>();
        for (Path replacement : replacements) {
            Path path = normalize(replacement);
            if (!isXqb(path)) {
                throw new IllegalArgumentException("recovery sources must use .xqb");
            }
            normalized.add(path);
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("recovery sources must not be empty");
        }
        sources.clear();
        sources.addAll(normalized);
    }

    public List<Path> sources() {
        return List.copyOf(sources);
    }

    public Path destination() {
        return destination;
    }

    public void setDestination(Path destination) {
        Path normalized = normalize(destination);
        if (!isXqb(normalized)) {
            throw new IllegalArgumentException("destination must use the .xqb extension");
        }
        this.destination = normalized;
    }

    public Optional<String> readinessError() {
        if (sources.isEmpty()) return Optional.of("请先添加至少一个 XQB v1 源文件。");
        if (destination == null) return Optional.of("请选择输出 XQB 文件。");
        if (sources.contains(destination)) return Optional.of("输出文件不能同时作为源文件。");
        return Optional.empty();
    }

    public static String progressText(XqbBatchService.Progress progress) {
        Objects.requireNonNull(progress, "progress");
        String action = switch (progress.phase()) {
            case VALIDATING -> "正在校验";
            case READING -> "正在读取";
            case CHECKPOINTING -> "已保存断点";
            case WRITING -> "正在写入";
            case COMPLETED -> "已完成";
        };
        int displayIndex = switch (progress.phase()) {
            case VALIDATING, READING ->
                    Math.min(progress.completedSources() + 1, progress.sourceCount());
            case CHECKPOINTING -> progress.completedSources();
            case WRITING, COMPLETED -> progress.sourceCount();
        };
        Path fileName = progress.currentFile().getFileName();
        String displayName = fileName == null
                ? progress.currentFile().toString() : fileName.toString();
        return action + " " + displayIndex + "/" + progress.sourceCount()
                + "：" + displayName
                + "｜扫描 " + progress.scannedRows()
                + "｜有效 " + progress.acceptedRows()
                + "｜重复 " + progress.duplicateRows()
                + "｜拒绝 " + progress.rejectedRows();
    }

    public static String reportText(XqbBatchService.BatchReport report) {
        Objects.requireNonNull(report, "report");
        StringBuilder text = new StringBuilder()
                .append("完成：扫描 ").append(report.scannedRows())
                .append("，写入 ").append(report.writtenRows())
                .append("，精确重复 ").append(report.duplicateRows())
                .append("，拒绝 ").append(report.rejectedRows()).append('。');
        for (XqbBatchService.Issue issue : report.issues()) {
            Path name = issue.source().getFileName();
            text.append(System.lineSeparator())
                    .append('[').append(issue.code()).append("] ")
                    .append(name == null ? issue.source() : name)
                    .append(" 第 ").append(issue.rowNumber()).append(" 行：")
                    .append(issue.message());
        }
        if (report.omittedIssueCount() > 0) {
            text.append(System.lineSeparator()).append("另有 ")
                    .append(report.omittedIssueCount()).append(" 条问题未展开。");
        }
        return text.toString();
    }

    public static String failureText(String code) {
        return switch (Objects.requireNonNullElse(code, "")) {
            case "CANCELLED" -> "任务已暂停，源文件和原输出文件均未改动；已完成断点可继续。";
            case "UNSUPPORTED_SCHEMA", "UNSUPPORTED_XQB_VERSION" ->
                    "文件结构不是受支持的 XQB v1。";
            case "INVALID_SQLITE_HEADER" -> "所选文件不是有效的 SQLite/XQB 文件。";
            case "SOURCE_TOO_LARGE" -> "源棋库超过安全大小上限。";
            case "ROW_LIMIT_EXCEEDED" -> "记录总数超过安全上限，任务已停止。";
            case "SOURCE_DESTINATION_OVERLAP" -> "输出文件不能与任何源文件相同。";
            case "SQLITE_FAILURE" -> "棋库读取或写入失败；原输出文件未改动。";
            case "CHECKPOINT_BUSY" -> "同一输出文件已有批处理任务正在运行。";
            case "CHECKPOINT_CORRUPT" -> "恢复数据已损坏；请清除断点后重新开始。";
            case "CHECKPOINT_MISMATCH" -> "源文件或任务参数已变化，不能使用旧断点。";
            default -> "批处理失败；原输出文件未改动。";
        };
    }

    private static Path normalize(Path path) {
        return Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }

    private static boolean isXqb(Path path) {
        Path name = path.getFileName();
        return name != null && name.toString().toLowerCase(Locale.ROOT).endsWith(".xqb");
    }

    public record AddResult(int addedCount, List<Path> rejected) {
        public AddResult {
            rejected = List.copyOf(Objects.requireNonNull(rejected, "rejected"));
            if (addedCount < 0) throw new IllegalArgumentException("addedCount must not be negative");
        }
    }
}
