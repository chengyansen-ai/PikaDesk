package com.sojourners.chess.manual.adapter;

import com.sojourners.chess.game.tree.GameTree;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Strict, bounded adapter for the published XQStudio/XQF 1.0 (version 0x0a)
 * format. Later encrypted variants deliberately use a different compatibility
 * path because their record layout is not the published 1.0 contract.
 */
public final class Xqf10Adapter {

    public static final int MAX_INPUT_BYTES = 16 * 1024 * 1024;
    public static final int MAX_OUTPUT_BYTES = 16 * 1024 * 1024;
    private static final int HEADER_SIZE = 0x400;
    private static final int RECORD_SIZE = 8;
    private static final int MAX_MOVES = 50_000;
    private static final int MAX_COMMENT_BYTES = 16_384;
    private static final int MAX_NOTICES = 128;
    private static final Charset GBK = Charset.forName("GBK");
    private static final char[] PIECE_SYMBOLS = {
            'R', 'N', 'B', 'A', 'K', 'A', 'B', 'N', 'R', 'C', 'C',
            'P', 'P', 'P', 'P', 'P',
            'r', 'n', 'b', 'a', 'k', 'a', 'b', 'n', 'r', 'c', 'c',
            'p', 'p', 'p', 'p', 'p'
    };
    private static final List<TextField> TEXT_FIELDS = List.of(
            new TextField("Title", 0x50, 64),
            new TextField("Event", 0xd0, 64),
            new TextField("Date", 0x110, 16),
            new TextField("Site", 0x120, 16),
            new TextField("Red", 0x130, 16),
            new TextField("Black", 0x140, 16),
            new TextField("TimeControl", 0x150, 64),
            new TextField("RedTime", 0x190, 16),
            new TextField("BlackTime", 0x1a0, 16),
            new TextField("Annotator", 0x1d0, 16),
            new TextField("Author", 0x1e0, 16));

    public ReadResult read(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        byte[] bytes = input.readNBytes(MAX_INPUT_BYTES + 1);
        if (bytes.length > MAX_INPUT_BYTES) {
            throw error("SIZE_LIMIT", MAX_INPUT_BYTES,
                    "XQF exceeds " + MAX_INPUT_BYTES + " bytes");
        }
        if (bytes.length < HEADER_SIZE) {
            throw error("TRUNCATED_HEADER", bytes.length,
                    "XQF 1.0 requires a 1024-byte header");
        }
        validateHeader(bytes);

        Map<String, String> metadata = new LinkedHashMap<>();
        for (TextField field : TEXT_FIELDS) {
            String value = readTextBlock(bytes, field);
            if (!value.isEmpty()) metadata.put(field.name(), value);
        }
        metadata.put("XqfType", gameType(bytes[0x40] & 0xff, 0x40));

        GameTree tree;
        try {
            tree = GameTree.create(pieceFen(bytes));
        } catch (IllegalArgumentException invalid) {
            throw error("INVALID_POSITION", 0x10, safeMessage(invalid));
        }
        readRecords(bytes, tree);
        List<Notice> notices = List.of(new Notice("ASSUMED_RED_TO_MOVE",
                "XQF 1.0 has no side-to-move header field; decoded as red to move", 0x10));
        try {
            return new ReadResult(
                    new ManualDocument(tree, result(bytes[0x33] & 0xff), metadata), notices);
        } catch (IllegalArgumentException invalid) {
            throw error("INVALID_METADATA", 0x50, safeMessage(invalid));
        }
    }

    public WriteResult write(ManualDocument document, OutputStream output) throws IOException {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(output, "output");
        GameTree tree = document.tree();
        if (!tree.initialFen().endsWith(" w")) {
            throw error("UNSUPPORTED_SIDE_TO_MOVE", 0,
                    "XQF 1.0 has no side-to-move field; only red-to-move positions are safe to write");
        }
        if (!tree.root().comment().isEmpty()) {
            throw error("UNSUPPORTED_ROOT_COMMENT", 0x400,
                    "XQF 1.0 requires a fixed empty step-zero record");
        }
        validateMainlineOnly(tree);
        if (tree.size() - 1 > MAX_MOVES) {
            throw error("MOVE_LIMIT", 0, "XQF move limit exceeded");
        }

        List<Notice> notices = collectWriteNotices(document);
        byte[] header = new byte[HEADER_SIZE];
        header[0] = 'X';
        header[1] = 'Q';
        header[2] = 0x0a;
        byte[] pieces = encodePieces(tree.initialFen());
        System.arraycopy(pieces, 0, header, 0x10, pieces.length);
        header[0x33] = resultByte(document.result());
        header[0x40] = (byte) gameTypeByte(document.metadata().getOrDefault("XqfType", "FULL"));
        for (TextField field : TEXT_FIELDS) {
            String value = document.metadata().get(field.name());
            if (value != null) writeTextBlock(header, field, value);
        }

        BoundedBuffer encoded = new BoundedBuffer(MAX_OUTPUT_BYTES);
        encoded.write(header);
        List<GameTree.Node> mainline = mainline(tree);
        encoded.write(new byte[]{0x18, 0x20,
                mainline.isEmpty() ? 0 : (byte) 0xf0, (byte) 0xff, 0, 0, 0, 0});
        for (int index = 0; index < mainline.size(); index++) {
            GameTree.Node node = mainline.get(index);
            byte[] comment = encodeText(node.comment(),
                    MAX_COMMENT_BYTES, "COMMENT_LIMIT", 0);
            byte[] record = new byte[RECORD_SIZE];
            record[0] = (byte) (coordinate(node.move(), 0) + 24);
            record[1] = (byte) (coordinate(node.move(), 2) + 32);
            record[2] = index + 1 < mainline.size() ? (byte) 0xf0 : 0;
            putLittleEndian(record, 4, comment.length);
            encoded.write(record);
            encoded.write(comment);
        }
        output.write(encoded.toByteArray());
        output.flush();
        return new WriteResult(notices);
    }

    private static void validateHeader(byte[] bytes) throws XqfException {
        if (bytes[0] != 'X' || bytes[1] != 'Q') {
            throw error("INVALID_MAGIC", 0, "XQF marker must be XQ");
        }
        int version = bytes[2] & 0xff;
        if (version != 0x0a) {
            throw error("UNSUPPORTED_VERSION", 2,
                    "audited adapter supports only published XQF 1.0 (0x0a); found " + version);
        }
        requireZero(bytes, 0x03, 0x10, "reserved header bytes");
        requireZero(bytes, 0x30, 0x33, "reserved header bytes");
        int result = bytes[0x33] & 0xff;
        if (result > 3) throw error("INVALID_RESULT", 0x33, "invalid XQF result value");
        requireZero(bytes, 0x34, 0x40, "reserved header bytes");
        int type = bytes[0x40] & 0xff;
        if (type > 3) throw error("INVALID_TYPE", 0x40, "invalid XQF game type");
        requireZero(bytes, 0x41, 0x50, "reserved header bytes");
        requireZero(bytes, 0x90, 0xd0, "reserved header bytes");
        requireZero(bytes, 0x1b0, 0x1d0, "reserved header bytes");
        requireZero(bytes, 0x1f0, 0x400, "reserved header bytes");
    }

    private static String readTextBlock(byte[] bytes, TextField field) throws XqfException {
        int length = bytes[field.offset()] & 0xff;
        if (length >= field.size()) {
            throw error("TEXT_LIMIT", field.offset(),
                    field.name() + " exceeds its XQF header block");
        }
        int textStart = field.offset() + 1;
        int textEnd = textStart + length;
        requireZero(bytes, textEnd, field.offset() + field.size(),
                field.name() + " padding");
        return decodeText(bytes, textStart, length, field.name());
    }

    private static String pieceFen(byte[] bytes) throws XqfException {
        char[][] board = new char[10][9];
        for (char[] rank : board) Arrays.fill(rank, ' ');
        for (int index = 0; index < PIECE_SYMBOLS.length; index++) {
            int position = bytes[0x10 + index] & 0xff;
            if (position == 0xff) continue;
            int x = position / 10;
            int y = position % 10;
            if (x > 8 || y > 9) {
                throw error("INVALID_COORDINATE", 0x10 + index,
                        "piece coordinate is outside the xiangqi board");
            }
            int row = 9 - y;
            if (board[row][x] != ' ') {
                throw error("DUPLICATE_PIECE_POSITION", 0x10 + index,
                        "two header pieces occupy the same coordinate");
            }
            board[row][x] = PIECE_SYMBOLS[index];
        }
        return fen(board, true);
    }

    private static void readRecords(byte[] bytes, GameTree tree) throws XqfException {
        if (bytes.length < HEADER_SIZE + RECORD_SIZE) {
            throw error("TRUNCATED_RECORD", bytes.length, "missing XQF step-zero record");
        }
        if ((bytes[0x400] & 0xff) != 0x18 || (bytes[0x401] & 0xff) != 0x20
                || (bytes[0x403] & 0xff) != 0xff || littleEndian(bytes, 0x404) != 0) {
            throw error("INVALID_ROOT_RECORD", 0x400,
                    "XQF 1.0 step-zero record is not canonical");
        }
        int rootFlag = bytes[0x402] & 0xff;
        if (rootFlag != 0 && rootFlag != 0xf0) {
            throw error("INVALID_CONTINUATION", 0x402, "invalid step-zero continuation flag");
        }
        int offset = HEADER_SIZE + RECORD_SIZE;
        if (rootFlag == 0) {
            if (offset != bytes.length) {
                throw error("TRAILING_DATA", offset, "data follows an empty XQF record");
            }
            return;
        }

        UUID current = tree.root().id();
        int moves = 0;
        while (true) {
            if (offset + RECORD_SIZE > bytes.length) {
                throw error("TRUNCATED_RECORD", offset, "truncated XQF move record");
            }
            if (++moves > MAX_MOVES) {
                throw error("MOVE_LIMIT", offset, "XQF move limit exceeded");
            }
            int from = decodeCoordinate(bytes[offset] & 0xff, 24, offset);
            int to = decodeCoordinate(bytes[offset + 1] & 0xff, 32, offset + 1);
            int continuation = bytes[offset + 2] & 0xff;
            if (continuation != 0 && continuation != 0xf0) {
                throw error("INVALID_CONTINUATION", offset + 2,
                        "move continuation must be 0x00 or 0xf0");
            }
            if (bytes[offset + 3] != 0) {
                throw error("INVALID_RESERVED_BYTE", offset + 3,
                        "XQF 1.0 move reserved byte must be zero");
            }
            long commentLength = Integer.toUnsignedLong(littleEndian(bytes, offset + 4));
            if (commentLength > MAX_COMMENT_BYTES) {
                throw error("COMMENT_LIMIT", offset + 4, "XQF comment exceeds its limit");
            }
            int commentStart = offset + RECORD_SIZE;
            long nextLong = (long) commentStart + commentLength;
            if (nextLong > bytes.length) {
                throw error("TRUNCATED_COMMENT", commentStart, "truncated XQF comment");
            }
            int next = (int) nextLong;
            String comment = decodeText(bytes, commentStart, (int) commentLength, "comment");
            String move = coordinate(from) + coordinate(to);
            try {
                current = tree.insert(current, move).endNodeId();
                if (!comment.isEmpty()) tree.updateComment(current, comment);
            } catch (IllegalArgumentException | IllegalStateException invalid) {
                throw error("INVALID_MOVE", offset, safeMessage(invalid));
            }
            offset = next;
            if (continuation == 0) {
                if (offset != bytes.length) {
                    throw error("TRAILING_DATA", offset, "data follows the last XQF move");
                }
                return;
            }
        }
    }

    private static byte[] encodePieces(String fen) throws XqfException {
        String position = fen.substring(0, fen.indexOf(' '));
        String[] ranks = position.split("/", -1);
        Map<Character, List<Integer>> locations = new HashMap<>();
        for (int row = 0; row < ranks.length; row++) {
            int x = 0;
            for (char symbol : ranks[row].toCharArray()) {
                if (Character.isDigit(symbol)) {
                    x += symbol - '0';
                } else {
                    int y = 9 - row;
                    locations.computeIfAbsent(symbol, ignored -> new ArrayList<>()).add(x * 10 + y);
                    x++;
                }
            }
        }
        locations.forEach((piece, list) -> list.sort(Character.isUpperCase(piece)
                ? Comparator.reverseOrder() : Comparator.naturalOrder()));
        Map<Character, Integer> consumed = new HashMap<>();
        byte[] encoded = new byte[PIECE_SYMBOLS.length];
        Arrays.fill(encoded, (byte) 0xff);
        for (int index = 0; index < PIECE_SYMBOLS.length; index++) {
            char piece = PIECE_SYMBOLS[index];
            int ordinal = consumed.getOrDefault(piece, 0);
            List<Integer> positions = locations.getOrDefault(piece, List.of());
            if (ordinal < positions.size()) encoded[index] = positions.get(ordinal).byteValue();
            consumed.put(piece, ordinal + 1);
        }
        for (Map.Entry<Character, List<Integer>> entry : locations.entrySet()) {
            if (entry.getValue().size() > consumed.getOrDefault(entry.getKey(), 0)) {
                throw error("PIECE_LIMIT", 0x10, "position has too many " + entry.getKey() + " pieces");
            }
        }
        return encoded;
    }

    private static List<Notice> collectWriteNotices(ManualDocument document) throws XqfException {
        List<Notice> notices = new ArrayList<>();
        List<String> supported = new ArrayList<>(TEXT_FIELDS.stream().map(TextField::name).toList());
        supported.add("XqfType");
        for (String name : document.metadata().keySet()) {
            if (!supported.contains(name)) {
                addNotice(notices, new Notice("UNSUPPORTED_METADATA",
                        "metadata field omitted: " + name, 0));
            }
        }
        ArrayDeque<GameTree.Node> pending = new ArrayDeque<>();
        pending.add(document.tree().root());
        boolean evaluationReported = false;
        while (!pending.isEmpty()) {
            GameTree.Node node = pending.removeFirst();
            if (!evaluationReported && node.evaluation().isPresent()) {
                addNotice(notices, new Notice("UNSUPPORTED_EVALUATION",
                        "XQF 1.0 has no engine-evaluation field", 0));
                evaluationReported = true;
            }
            pending.addAll(document.tree().children(node.id()));
        }
        return List.copyOf(notices);
    }

    private static void validateMainlineOnly(GameTree tree) throws XqfException {
        GameTree.Node current = tree.root();
        int visited = 1;
        while (true) {
            if (current.children().size() > 1) {
                throw error("UNSUPPORTED_VARIATIONS", 0,
                        "published XQF 1.0 cannot represent variations");
            }
            if (current.children().isEmpty()) break;
            UUID next = current.children().getFirst();
            if (!current.mainlineChildId().equals(java.util.Optional.of(next))) {
                throw error("INVALID_TREE", 0, "single child is not the mainline child");
            }
            current = tree.node(next);
            visited++;
        }
        if (visited != tree.size()) {
            throw error("UNSUPPORTED_VARIATIONS", 0,
                    "published XQF 1.0 cannot represent the complete game tree");
        }
    }

    private static List<GameTree.Node> mainline(GameTree tree) {
        List<GameTree.Node> result = new ArrayList<>(Math.max(0, tree.size() - 1));
        GameTree.Node current = tree.root();
        while (current.mainlineChildId().isPresent()) {
            current = tree.node(current.mainlineChildId().orElseThrow());
            result.add(current);
        }
        return result;
    }

    private static void writeTextBlock(byte[] header, TextField field, String value)
            throws XqfException {
        byte[] encoded = encodeText(value, field.size() - 1,
                "TEXT_LIMIT", field.offset());
        header[field.offset()] = (byte) encoded.length;
        System.arraycopy(encoded, 0, header, field.offset() + 1, encoded.length);
    }

    private static byte[] encodeText(String value, int limit, String limitCode, int offset)
            throws XqfException {
        try {
            ByteBuffer buffer = GBK.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] encoded = new byte[buffer.remaining()];
            buffer.get(encoded);
            if (encoded.length > limit) {
                throw error(limitCode, offset, "GBK text exceeds " + limit + " bytes");
            }
            return encoded;
        } catch (CharacterCodingException invalid) {
            throw error("UNREPRESENTABLE_TEXT", offset,
                    "text cannot be represented by XQF 1.0 GBK encoding");
        }
    }

    private static String decodeText(byte[] bytes, int offset, int length, String label)
            throws XqfException {
        try {
            return GBK.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, length)).toString();
        } catch (CharacterCodingException invalid) {
            throw error("INVALID_ENCODING", offset, "invalid GBK " + label);
        }
    }

    private static String fen(char[][] board, boolean redToMove) {
        StringBuilder result = new StringBuilder(96);
        for (int row = 0; row < board.length; row++) {
            int empty = 0;
            for (char piece : board[row]) {
                if (piece == ' ') {
                    empty++;
                } else {
                    if (empty > 0) result.append(empty);
                    empty = 0;
                    result.append(piece);
                }
            }
            if (empty > 0) result.append(empty);
            if (row + 1 < board.length) result.append('/');
        }
        return result.append(redToMove ? " w" : " b").toString();
    }

    private static String coordinate(int position) {
        return new String(new char[]{(char) ('a' + position / 10),
                (char) ('0' + position % 10)});
    }

    private static int coordinate(String move, int offset) {
        return (move.charAt(offset) - 'a') * 10 + move.charAt(offset + 1) - '0';
    }

    private static int decodeCoordinate(int encoded, int adjustment, int offset)
            throws XqfException {
        int position = encoded - adjustment;
        if (position < 0 || position / 10 > 8 || position % 10 > 9) {
            throw error("INVALID_COORDINATE", offset, "move coordinate is outside the board");
        }
        return position;
    }

    private static int littleEndian(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | (bytes[offset + 1] & 0xff) << 8
                | (bytes[offset + 2] & 0xff) << 16
                | (bytes[offset + 3] & 0xff) << 24;
    }

    private static void putLittleEndian(byte[] bytes, int offset, int value) {
        for (int index = 0; index < 4; index++) {
            bytes[offset + index] = (byte) (value >>> (index * 8));
        }
    }

    private static void requireZero(byte[] bytes, int start, int end, String label)
            throws XqfException {
        for (int offset = start; offset < end; offset++) {
            if (bytes[offset] != 0) {
                throw error("NONZERO_RESERVED", offset, label + " must be zero");
            }
        }
    }

    private static ManualDocument.Result result(int value) {
        return switch (value) {
            case 0 -> ManualDocument.Result.ONGOING;
            case 1 -> ManualDocument.Result.RED_WIN;
            case 2 -> ManualDocument.Result.BLACK_WIN;
            case 3 -> ManualDocument.Result.DRAW;
            default -> throw new IllegalStateException("validated result expected");
        };
    }

    private static byte resultByte(ManualDocument.Result result) {
        return switch (result) {
            case ONGOING -> 0;
            case RED_WIN -> 1;
            case BLACK_WIN -> 2;
            case DRAW -> 3;
        };
    }

    private static String gameType(int value, int offset) throws XqfException {
        return switch (value) {
            case 0 -> "FULL";
            case 1 -> "OPENING";
            case 2 -> "MIDDLEGAME";
            case 3 -> "ENDGAME";
            default -> throw error("INVALID_TYPE", offset, "invalid XQF game type");
        };
    }

    private static int gameTypeByte(String value) throws XqfException {
        return switch (value) {
            case "FULL" -> 0;
            case "OPENING" -> 1;
            case "MIDDLEGAME" -> 2;
            case "ENDGAME" -> 3;
            default -> throw error("INVALID_TYPE", 0x40,
                    "XqfType must be FULL, OPENING, MIDDLEGAME, or ENDGAME");
        };
    }

    private static void addNotice(List<Notice> notices, Notice notice) throws XqfException {
        if (notices.size() >= MAX_NOTICES) {
            throw error("NOTICE_LIMIT", notice.offset(), "XQF notice limit exceeded");
        }
        notices.add(notice);
    }

    private static String safeMessage(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message;
    }

    private static XqfException error(String code, int offset, String message) {
        return new XqfException(code, Math.max(0, offset), message);
    }

    public record Notice(String code, String message, int offset) {
        public Notice {
            code = Objects.requireNonNull(code, "code");
            message = Objects.requireNonNull(message, "message");
            if (code.isBlank() || message.isBlank() || offset < 0) {
                throw new IllegalArgumentException("invalid XQF notice");
            }
        }
    }

    public record ReadResult(ManualDocument document, List<Notice> notices) {
        public ReadResult {
            Objects.requireNonNull(document, "document");
            notices = List.copyOf(Objects.requireNonNull(notices, "notices"));
            if (notices.size() > MAX_NOTICES) {
                throw new IllegalArgumentException("XQF notice limit exceeded");
            }
        }
    }

    public record WriteResult(List<Notice> notices) {
        public WriteResult {
            notices = List.copyOf(Objects.requireNonNull(notices, "notices"));
            if (notices.size() > MAX_NOTICES) {
                throw new IllegalArgumentException("XQF notice limit exceeded");
            }
        }
    }

    public static final class XqfException extends IOException {
        private final String code;
        private final int offset;

        private XqfException(String code, int offset, String message) {
            super(code + " at " + offset + ": " + message);
            this.code = Objects.requireNonNull(code, "code");
            this.offset = offset;
        }

        public String code() {
            return code;
        }

        public int offset() {
            return offset;
        }
    }

    private record TextField(String name, int offset, int size) { }

    private static final class BoundedBuffer {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(HEADER_SIZE + 4096);

        private BoundedBuffer(int limit) {
            this.limit = limit;
        }

        private void write(byte[] value) throws XqfException {
            if ((long) bytes.size() + value.length > limit) {
                throw error("SIZE_LIMIT", bytes.size(), "serialized XQF exceeds output limit");
            }
            bytes.writeBytes(value);
        }

        private byte[] toByteArray() {
            return bytes.toByteArray();
        }
    }
}
