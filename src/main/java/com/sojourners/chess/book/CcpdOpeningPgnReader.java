package com.sojourners.chess.book;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads one bounded, strict Big5 CCPD opening file as untrusted input. */
public final class CcpdOpeningPgnReader {

    static final int MAX_FILE_BYTES = 65_536;
    private static final int MAX_MOVES = 512;
    private static final Charset BIG5 = Charset.forName("Big5");
    private static final Pattern HEADER = Pattern.compile(
            "^\\[([A-Za-z][A-Za-z0-9]*) \\\"([^\\\"\\r\\n]{0,512})\\\"\\]$");
    private static final Pattern MOVE_LINE = Pattern.compile(
            "^\\s*(\\d{1,3})\\.\\s+(\\S{4})(?:\\s+(\\S{4}))?"
                    + "(?:\\s+(1-0|0-1|1/2-1/2|\\*))?\\s*$");
    private static final Set<String> RESULTS = Set.of("1-0", "0-1", "1/2-1/2", "*");

    private final StrictChineseMoveDecoder moveDecoder = new StrictChineseMoveDecoder();

    public OpeningLine read(Path source) {
        Objects.requireNonNull(source, "source");
        byte[] bytes = readBounded(source);
        String text = decodeBig5(bytes);
        rejectControlCharacters(text);
        return parse(text, sha256(bytes));
    }

    private byte[] readBounded(Path source) {
        try {
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new ReadException("NOT_A_REGULAR_FILE");
            }
            if (Files.size(source) > MAX_FILE_BYTES) {
                throw new ReadException("FILE_TOO_LARGE");
            }
            try (InputStream input = Files.newInputStream(source)) {
                byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
                if (bytes.length > MAX_FILE_BYTES) {
                    throw new ReadException("FILE_TOO_LARGE");
                }
                return bytes;
            }
        } catch (ReadException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ReadException("IO_ERROR", exception);
        }
    }

    private String decodeBig5(byte[] bytes) {
        try {
            CharBuffer decoded = BIG5.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException exception) {
            throw new ReadException("INVALID_BIG5", exception);
        }
    }

    private void rejectControlCharacters(String text) {
        for (int index = 0; index < text.length(); index++) {
            char symbol = text.charAt(index);
            if (symbol == '\r' || symbol == '\n' || symbol == '\t') continue;
            if (Character.isISOControl(symbol)) {
                throw new ReadException("MALFORMED_PGN");
            }
        }
    }

    private OpeningLine parse(String text, String sourceSha256) {
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        Map<String, String> headers = new LinkedHashMap<>();
        int index = 0;
        while (index < lines.length && !lines[index].isBlank()) {
            Matcher matcher = HEADER.matcher(lines[index]);
            if (!matcher.matches() || headers.putIfAbsent(matcher.group(1), matcher.group(2)) != null) {
                throw new ReadException("MALFORMED_HEADER");
            }
            index++;
        }
        while (index < lines.length && lines[index].isBlank()) index++;

        String fen = headers.get("FEN");
        String headerResult = headers.get("Result");
        if (fen == null || headerResult == null || !RESULTS.contains(headerResult)) {
            throw new ReadException("MISSING_REQUIRED_HEADER");
        }

        List<String> moves = new ArrayList<>();
        String currentFen = fen;
        String movetextResult = null;
        int expectedNumber = 1;
        for (; index < lines.length; index++) {
            if (lines[index].isBlank()) continue;
            if (movetextResult != null) {
                throw new ReadException("MALFORMED_MOVETEXT");
            }
            Matcher matcher = MOVE_LINE.matcher(lines[index]);
            if (!matcher.matches() || Integer.parseInt(matcher.group(1)) != expectedNumber++) {
                throw new ReadException("MALFORMED_MOVETEXT");
            }
            currentFen = appendMove(moves, currentFen, matcher.group(2));
            if (matcher.group(3) != null) {
                currentFen = appendMove(moves, currentFen, matcher.group(3));
            }
            movetextResult = matcher.group(4);
        }
        if (moves.isEmpty() || moves.size() > MAX_MOVES || !headerResult.equals(movetextResult)) {
            throw new ReadException("MALFORMED_MOVETEXT");
        }

        return new OpeningLine(
                headers.getOrDefault("Event", ""),
                headers.getOrDefault("ECCO", ""),
                headerResult,
                fen,
                moves,
                sourceSha256);
    }

    private String appendMove(List<String> moves, String fen, String notation) {
        if (moves.size() >= MAX_MOVES) {
            throw new ReadException("TOO_MANY_MOVES");
        }
        try {
            StrictChineseMoveDecoder.DecodedMove decoded = moveDecoder.decode(fen, notation);
            moves.add(decoded.ucci());
            return decoded.nextFen();
        } catch (StrictChineseMoveDecoder.DecodeException exception) {
            throw new ReadException("INVALID_MOVE", exception);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().withUpperCase().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record OpeningLine(String event,
                              String ecco,
                              String result,
                              String initialFen,
                              List<String> moves,
                              String sourceSha256) {
        public OpeningLine {
            Objects.requireNonNull(event, "event");
            Objects.requireNonNull(ecco, "ecco");
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(initialFen, "initialFen");
            moves = List.copyOf(Objects.requireNonNull(moves, "moves"));
            Objects.requireNonNull(sourceSha256, "sourceSha256");
        }
    }

    public static final class ReadException extends IllegalArgumentException {

        private final String code;

        private ReadException(String code) {
            super(code);
            this.code = code;
        }

        private ReadException(String code, Throwable cause) {
            super(code, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
