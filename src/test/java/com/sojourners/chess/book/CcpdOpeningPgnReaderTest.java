package com.sojourners.chess.book;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CcpdOpeningPgnReaderTest {

    private static final Charset BIG5 = Charset.forName("Big5");
    private static final String INITIAL_FEN =
            "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1";

    private final CcpdOpeningPgnReader reader = new CcpdOpeningPgnReader();

    @Test
    void readsARealisticStrictBig5Opening(@TempDir Path directory) throws IOException {
        Path source = write(directory, "opening.pgn", validPgn(
                "1. 炮二平五 馬８進７\r\n2. 馬二進三 車９平８  *"));

        CcpdOpeningPgnReader.OpeningLine line = reader.read(source);

        assertEquals("01 紅七路馬盤河黑上左象", line.event());
        assertEquals("C44", line.ecco());
        assertEquals("*", line.result());
        assertEquals(INITIAL_FEN, line.initialFen());
        assertEquals(java.util.List.of("h2e2", "h9g7", "h0g2", "i9h9"), line.moves());
        assertEquals(64, line.sourceSha256().length());
    }

    @Test
    void rejectsOversizedMalformedAndInjectedInput(@TempDir Path directory) throws IOException {
        Path oversized = directory.resolve("oversized.pgn");
        Files.write(oversized, new byte[65_537]);
        assertEquals("FILE_TOO_LARGE", failure(oversized).code());

        Path malformed = directory.resolve("malformed.pgn");
        Files.write(malformed, new byte[]{(byte) 0x81, 0x20});
        assertEquals("INVALID_BIG5", failure(malformed).code());

        Path injected = write(directory, "injected.pgn", validPgn(
                "1. 炮二平五.exe 馬８進７  *"));
        assertEquals("MALFORMED_MOVETEXT", failure(injected).code());
    }

    @Test
    void rejectsMissingHeadersAndMoveNumberGaps(@TempDir Path directory) throws IOException {
        Path missingFen = write(directory, "missing-fen.pgn",
                "[Game \"Chinese Chess\"]\r\n[Result \"*\"]\r\n\r\n1. 炮二平五 馬８進７  *");
        assertEquals("MISSING_REQUIRED_HEADER", failure(missingFen).code());

        Path gap = write(directory, "gap.pgn", validPgn(
                "1. 炮二平五 馬８進７\r\n3. 馬二進三 車９平８  *"));
        assertEquals("MALFORMED_MOVETEXT", failure(gap).code());
    }

    private CcpdOpeningPgnReader.ReadException failure(Path source) {
        return assertThrows(CcpdOpeningPgnReader.ReadException.class, () -> reader.read(source));
    }

    private Path write(Path directory, String name, String contents) throws IOException {
        Path file = directory.resolve(name);
        Files.write(file, contents.getBytes(BIG5));
        return file;
    }

    private String validPgn(String moves) {
        return "[Game \"Chinese Chess\"]\r\n"
                + "[Event \"01 紅七路馬盤河黑上左象\"]\r\n"
                + "[Result \"*\"]\r\n"
                + "[ECCO \"C44\"]\r\n"
                + "[FEN \"" + INITIAL_FEN + "\"]\r\n\r\n"
                + moves + "\r\n";
    }
}
