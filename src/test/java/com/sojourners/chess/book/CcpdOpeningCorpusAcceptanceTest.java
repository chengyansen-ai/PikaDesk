package com.sojourners.chess.book;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class CcpdOpeningCorpusAcceptanceTest {

    @Test
    void auditsTheExplicitlySelectedLocalCorpus() {
        String configured = System.getProperty("ccpdOpeningDir", "");
        assumeTrue(!configured.isBlank(), "set -DccpdOpeningDir to run the licensed-corpus audit");

        CcpdOpeningCorpusAuditor.AuditResult result =
                new CcpdOpeningCorpusAuditor().audit(Path.of(configured));
        CcpdOpeningCorpusAuditor.AuditReport report = result.report();

        System.out.printf(
                "CCPD_AUDIT pgn=%d accepted=%d rejected=%d duplicates=%d unique=%d plies=%d max=%d rejections=%s%n",
                report.pgnFiles(), report.acceptedFiles(), report.rejectedFiles(),
                report.duplicateFiles(), report.uniqueLines(), report.uniquePlies(),
                report.maxPlies(), report.rejections());
        printRejectionSamples(Path.of(configured));
        assertEquals(report.pgnFiles(), report.acceptedFiles() + report.rejectedFiles());
        assertFalse(result.lines().isEmpty());
    }

    private void printRejectionSamples(Path directory) {
        CcpdOpeningPgnReader reader = new CcpdOpeningPgnReader();
        Map<String, AtomicInteger> printed = new ConcurrentHashMap<>();
        try (var sources = Files.list(directory)) {
            sources.filter(path -> path.getFileName().toString().endsWith(".pgn"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(path -> {
                        try {
                            reader.read(path);
                        } catch (CcpdOpeningPgnReader.ReadException exception) {
                            if (printed.computeIfAbsent(exception.code(), ignored -> new AtomicInteger())
                                    .incrementAndGet() > 10) return;
                            Throwable detail = exception.getCause();
                            String detailCode = detail instanceof StrictChineseMoveDecoder.DecodeException decode
                                    ? decode.code() : "";
                            System.out.printf("CCPD_REJECT file=%s code=%s detail=%s context=%s%n",
                                    path.getFileName(), exception.code(), detailCode, exception.getMessage());
                        }
                    });
        } catch (IOException exception) {
            throw new AssertionError("cannot enumerate the explicit corpus", exception);
        }
    }
}
