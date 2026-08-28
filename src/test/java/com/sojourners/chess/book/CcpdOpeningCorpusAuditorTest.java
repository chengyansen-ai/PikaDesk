package com.sojourners.chess.book;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class CcpdOpeningCorpusAuditorTest {

    private static final Charset BIG5 = Charset.forName("Big5");
    private static final String INITIAL_FEN =
            "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1";

    @Test
    void auditsEveryPgnAndDeduplicatesIdenticalLines(@TempDir Path directory) throws IOException {
        write(directory.resolve("00000001.pgn"), validPgn("甲"));
        write(directory.resolve("00000002.pgn"), validPgn("乙"));
        Files.write(directory.resolve("00000003.pgn"), new byte[]{(byte) 0x81, 0x20});
        Files.writeString(directory.resolve("README.txt"), "not a game");

        CcpdOpeningCorpusAuditor.AuditResult result =
                new CcpdOpeningCorpusAuditor().audit(directory);

        assertEquals(1, result.lines().size());
        assertEquals(3, result.report().pgnFiles());
        assertEquals(2, result.report().acceptedFiles());
        assertEquals(1, result.report().rejectedFiles());
        assertEquals(1, result.report().duplicateFiles());
        assertEquals(1, result.report().uniqueLines());
        assertEquals(4, result.report().uniquePlies());
        assertEquals(4, result.report().maxPlies());
        assertEquals(Map.of("INVALID_BIG5", 1L), result.report().rejections());
    }

    private void write(Path file, String contents) throws IOException {
        Files.write(file, contents.getBytes(BIG5));
    }

    private String validPgn(String eventSuffix) {
        return "[Game \"Chinese Chess\"]\r\n"
                + "[Event \"01 測試" + eventSuffix + "\"]\r\n"
                + "[Result \"*\"]\r\n"
                + "[ECCO \"C44\"]\r\n"
                + "[FEN \"" + INITIAL_FEN + "\"]\r\n\r\n"
                + "1. 炮二平五 馬８進７\r\n"
                + "2. 馬二進三 車９平８  *\r\n";
    }
}
