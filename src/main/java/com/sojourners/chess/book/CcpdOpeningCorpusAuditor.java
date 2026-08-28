package com.sojourners.chess.book;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;

/** Audits a bounded flat directory of CCPD opening files. */
public final class CcpdOpeningCorpusAuditor {

    private static final int MAX_PGN_FILES = 10_000;
    private final CcpdOpeningPgnReader reader = new CcpdOpeningPgnReader();

    public AuditResult audit(Path directory) {
        Objects.requireNonNull(directory, "directory");
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new CorpusException("NOT_A_REGULAR_DIRECTORY");
        }

        List<Path> sources;
        try (Stream<Path> entries = Files.list(directory)) {
            sources = entries
                    .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                            .endsWith(".pgn"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .limit(MAX_PGN_FILES + 1L)
                    .toList();
        } catch (IOException exception) {
            throw new CorpusException("DIRECTORY_READ_ERROR", exception);
        }
        if (sources.size() > MAX_PGN_FILES) {
            throw new CorpusException("TOO_MANY_PGN_FILES");
        }

        Map<String, CcpdOpeningPgnReader.OpeningLine> unique = new LinkedHashMap<>();
        Map<String, Long> rejections = new TreeMap<>();
        int accepted = 0;
        int duplicates = 0;
        int maxPlies = 0;
        long uniquePlies = 0;
        for (Path source : sources) {
            try {
                CcpdOpeningPgnReader.OpeningLine line = reader.read(source);
                accepted++;
                String key = line.initialFen() + '\n' + String.join(" ", line.moves());
                if (unique.putIfAbsent(key, line) == null) {
                    uniquePlies += line.moves().size();
                    maxPlies = Math.max(maxPlies, line.moves().size());
                } else {
                    duplicates++;
                }
            } catch (CcpdOpeningPgnReader.ReadException exception) {
                rejections.merge(exception.code(), 1L, Long::sum);
            }
        }

        List<CcpdOpeningPgnReader.OpeningLine> lines = new ArrayList<>(unique.values());
        AuditReport report = new AuditReport(
                sources.size(),
                accepted,
                sources.size() - accepted,
                duplicates,
                lines.size(),
                uniquePlies,
                maxPlies,
                rejections);
        return new AuditResult(lines, report);
    }

    public record AuditResult(List<CcpdOpeningPgnReader.OpeningLine> lines,
                              AuditReport report) {
        public AuditResult {
            lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
            Objects.requireNonNull(report, "report");
        }
    }

    public record AuditReport(int pgnFiles,
                              int acceptedFiles,
                              int rejectedFiles,
                              int duplicateFiles,
                              int uniqueLines,
                              long uniquePlies,
                              int maxPlies,
                              Map<String, Long> rejections) {
        public AuditReport {
            rejections = Map.copyOf(Objects.requireNonNull(rejections, "rejections"));
        }
    }

    public static final class CorpusException extends IllegalArgumentException {

        private final String code;

        private CorpusException(String code) {
            super(code);
            this.code = code;
        }

        private CorpusException(String code, Throwable cause) {
            super(code, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
