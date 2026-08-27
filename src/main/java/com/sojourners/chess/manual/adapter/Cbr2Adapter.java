package com.sojourners.chess.manual.adapter;

import com.sojourners.chess.game.tree.GameTree;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Strict, bounded adapter for the community-documented CCBridge CBR version 2. */
public final class Cbr2Adapter {

    public static final int MAX_INPUT_BYTES = 16 * 1024 * 1024;
    public static final int MAX_OUTPUT_BYTES = 16 * 1024 * 1024;
    private static final int HEADER_SIZE = 2_214;
    private static final int MAX_MOVES = 50_000;
    private static final int MAX_VARIATION_DEPTH = 256;
    private static final int MAX_COMMENT_CHARS = 16_384;
    private static final int MAX_COMMENT_BYTES = MAX_COMMENT_CHARS * 2;
    private static final int MAX_NOTICES = 128;
    private static final byte[] MAGIC = "CCBridge Record\0"
            .getBytes(StandardCharsets.US_ASCII);
    private static final List<TextField> TEXT_FIELDS = List.of(
            new TextField("Title", 180, 128),
            new TextField("Event", 692, 64),
            new TextField("Date", 884, 64),
            new TextField("Site", 948, 64),
            new TextField("Red", 1_076, 64),
            new TextField("Black", 1_300, 64));
    private static final Map<Character, Integer> PIECE_CODES = Map.ofEntries(
            Map.entry('R', 0x11), Map.entry('N', 0x12), Map.entry('B', 0x13),
            Map.entry('A', 0x14), Map.entry('K', 0x15), Map.entry('C', 0x16),
            Map.entry('P', 0x17), Map.entry('r', 0x21), Map.entry('n', 0x22),
            Map.entry('b', 0x23), Map.entry('a', 0x24), Map.entry('k', 0x25),
            Map.entry('c', 0x26), Map.entry('p', 0x27));

    public ReadResult read(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        byte[] bytes = input.readNBytes(MAX_INPUT_BYTES + 1);
        if (bytes.length > MAX_INPUT_BYTES) {
            throw error("SIZE_LIMIT", MAX_INPUT_BYTES,
                    "CBR exceeds " + MAX_INPUT_BYTES + " bytes");
        }
        if (bytes.length < HEADER_SIZE + 4) {
            throw error("TRUNCATED_HEADER", bytes.length,
                    "CBR v2 requires its 2214-byte header and root marker");
        }

        validateHeader(bytes);
        List<Notice> notices = new ArrayList<>();
        Map<String, String> metadata = new LinkedHashMap<>();
        for (TextField field : TEXT_FIELDS) {
            String value = readFixedText(bytes, field);
            if (!value.isEmpty()) metadata.put(field.name(), value);
        }
        GameTree tree;
        try {
            tree = GameTree.create(boardFen(bytes));
        } catch (IllegalArgumentException invalid) {
            throw error("INVALID_POSITION", 2_120, safeMessage(invalid));
        }

        int offset = HEADER_SIZE;
        long rootMarker = Integer.toUnsignedLong(littleEndian(bytes, offset));
        offset += 4;
        if (rootMarker == 4) {
            TextValue root = readVariableText(bytes, offset, "root comment");
            offset = root.nextOffset();
            if (!root.value().isEmpty()) tree.updateComment(tree.root().id(), root.value());
        } else if (rootMarker != 0) {
            throw error("INVALID_ROOT_MARKER", HEADER_SIZE,
                    "CBR root marker must be zero or four");
        }
        readRecords(bytes, offset, tree, notices);

        int rawResult = bytes[2_076] & 0xff;
        if (rawResult == 4) {
            addNotice(notices, new Notice("DRAW_CODE_ALIAS",
                    "CBR draw code 4 was normalized to the standard draw result", 2_076));
        }
        ManualDocument document;
        try {
            document = new ManualDocument(tree, result(rawResult), metadata);
        } catch (IllegalArgumentException invalid) {
            throw error("INVALID_METADATA", 180, safeMessage(invalid));
        }
        return new ReadResult(document, notices);
    }

    public WriteResult write(ManualDocument document, OutputStream output) throws IOException {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(output, "output");
        if (document.tree().size() - 1 > MAX_MOVES) {
            throw error("MOVE_LIMIT", 0, "CBR move limit exceeded");
        }

        List<Notice> notices = collectWriteNotices(document);
        byte[] header = new byte[HEADER_SIZE];
        System.arraycopy(MAGIC, 0, header, 0, MAGIC.length);
        header[19] = 2;
        for (TextField field : TEXT_FIELDS) {
            String value = document.metadata().get(field.name());
            if (value != null) writeFixedText(header, field, value);
        }
        header[2_076] = resultByte(document.result());
        header[2_112] = 1;
        putShort(header, 2_116, document.tree().initialFen().endsWith(" w") ? 1 : 2);
        byte[] board = encodeBoard(document.tree().initialFen());
        System.arraycopy(board, 0, header, 2_120, board.length);
        Arrays.fill(header, 2_210, 2_214, (byte) 0xff);

        BoundedBuffer encoded = new BoundedBuffer(MAX_OUTPUT_BYTES);
        encoded.write(header);
        String rootComment = document.tree().root().comment();
        if (rootComment.isEmpty()) {
            encoded.writeInt(0);
        } else {
            byte[] comment = encodeVariableText(rootComment, 0);
            encoded.writeInt(4);
            encoded.writeInt(comment.length);
            encoded.write(comment);
        }
        if (document.tree().root().children().isEmpty()) {
            encoded.write(new byte[4]);
        } else {
            writeRecords(document.tree(), encoded);
        }
        output.write(encoded.toByteArray());
        output.flush();
        return new WriteResult(notices);
    }

    private static void validateHeader(byte[] bytes) throws CbrException {
        if (!Arrays.equals(MAGIC, Arrays.copyOf(bytes, MAGIC.length))) {
            throw error("INVALID_MAGIC", 0, "CBR marker must be CCBridge Record plus NUL");
        }
        int version = bytes[19] & 0xff;
        if (version != 2) {
            throw error("UNSUPPORTED_VERSION", 19,
                    "audited adapter supports only CBR version 2; found " + version);
        }
        int result = bytes[2_076] & 0xff;
        if (result > 4) {
            throw error("INVALID_RESULT", 2_076, "invalid CBR result value");
        }
        int side = littleUnsignedShort(bytes, 2_116);
        if (side != 1 && side != 2) {
            throw error("INVALID_SIDE", 2_116, "CBR side must be 1 (red) or 2 (black)");
        }
        for (int offset = 2_210; offset < 2_214; offset++) {
            if ((bytes[offset] & 0xff) != 0xff) {
                throw error("INVALID_HEADER_SENTINEL", offset,
                        "CBR board terminator must contain four 0xff bytes");
            }
        }
    }

    private static String readFixedText(byte[] bytes, TextField field) throws CbrException {
        int end = field.offset() + field.size();
        int textEnd = end;
        for (int offset = field.offset(); offset < end; offset += 2) {
            if (bytes[offset] == 0 && bytes[offset + 1] == 0) {
                textEnd = offset;
                break;
            }
        }
        return decodeUtf16(bytes, field.offset(), textEnd - field.offset(), field.name());
    }

    private static String boardFen(byte[] bytes) throws CbrException {
        char[][] board = new char[10][9];
        for (char[] row : board) Arrays.fill(row, ' ');
        for (int index = 0; index < 90; index++) {
            int code = bytes[2_120 + index] & 0xff;
            if (code == 0) continue;
            char piece = decodePiece(code, 2_120 + index);
            board[index / 9][index % 9] = piece;
        }
        boolean redToMove = littleUnsignedShort(bytes, 2_116) == 1;
        return fen(board, redToMove);
    }

    private static char decodePiece(int code, int offset) throws CbrException {
        return switch (code) {
            case 0x11 -> 'R';
            case 0x12 -> 'N';
            case 0x13 -> 'B';
            case 0x14 -> 'A';
            case 0x15 -> 'K';
            case 0x16 -> 'C';
            case 0x17 -> 'P';
            case 0x21 -> 'r';
            case 0x22 -> 'n';
            case 0x23 -> 'b';
            case 0x24 -> 'a';
            case 0x25 -> 'k';
            case 0x26 -> 'c';
            case 0x27 -> 'p';
            default -> throw error("INVALID_PIECE", offset,
                    "unknown CBR board piece code " + code);
        };
    }

    private static void readRecords(byte[] bytes,
                                    int start,
                                    GameTree tree,
                                    List<Notice> notices) throws CbrException {
        int offset = start;
        if (offset == bytes.length) return;
        UUID parent = tree.root().id();
        ArrayDeque<UUID> variationParents = new ArrayDeque<>();
        int moves = 0;
        boolean stepAttributeReported = false;
        while (offset < bytes.length) {
            if (offset + 4 > bytes.length) {
                throw error("TRUNCATED_RECORD", offset, "truncated CBR move record");
            }
            if (allZero(bytes, offset, 4)) {
                if (moves == 0 && variationParents.isEmpty() && offset + 4 == bytes.length) return;
                throw error("UNEXPECTED_TERMINATOR", offset,
                        "zero move terminator is valid only for an empty record");
            }
            if (++moves > MAX_MOVES) {
                throw error("MOVE_LIMIT", offset, "CBR move limit exceeded");
            }
            int flag = bytes[offset] & 0xff;
            if ((flag & ~0x07) != 0) {
                throw error("INVALID_STEP_FLAG", offset, "CBR step uses unknown flag bits");
            }
            int stepAttribute = bytes[offset + 1] & 0xff;
            if (stepAttribute > 1) {
                throw error("INVALID_RESERVED_BYTE", offset + 1,
                        "CBR step attribute must be zero or one");
            }
            if (stepAttribute == 1 && !stepAttributeReported) {
                addNotice(notices, new Notice("UNSUPPORTED_STEP_ATTRIBUTE",
                        "CBR step attribute 1 was accepted but its presentation semantics are not preserved",
                        offset + 1));
                stepAttributeReported = true;
            }
            int from = bytes[offset + 2] & 0xff;
            int to = bytes[offset + 3] & 0xff;
            if (from >= 90 || to >= 90 || from == to) {
                throw error("INVALID_COORDINATE", offset + 2,
                        "CBR move coordinates must be two different board indices");
            }
            offset += 4;
            String comment = "";
            if ((flag & 0x04) != 0) {
                TextValue value = readVariableText(bytes, offset, "move comment");
                offset = value.nextOffset();
                comment = value.value();
            }

            GameTree.MergeResult inserted;
            try {
                inserted = tree.insert(parent, coordinate(from) + coordinate(to));
                if (inserted.createdNodes() == 0) {
                    throw error("DUPLICATE_VARIATION", offset,
                            "CBR contains the same sibling move more than once");
                }
                if (!comment.isEmpty()) tree.updateComment(inserted.endNodeId(), comment);
            } catch (CbrException duplicate) {
                throw duplicate;
            } catch (IllegalArgumentException | IllegalStateException invalid) {
                throw error("INVALID_MOVE", offset, safeMessage(invalid));
            }

            boolean hasNext = (flag & 0x01) == 0;
            boolean hasVariation = (flag & 0x02) != 0;
            if (hasNext) {
                if (hasVariation) {
                    if (variationParents.size() >= MAX_VARIATION_DEPTH) {
                        throw error("VARIATION_LIMIT", offset,
                                "CBR variation nesting limit exceeded");
                    }
                    variationParents.push(parent);
                }
                parent = inserted.endNodeId();
            } else if (!hasVariation) {
                if (!variationParents.isEmpty()) {
                    parent = variationParents.pop();
                } else {
                    if (offset != bytes.length) {
                        throw error("TRAILING_DATA", offset,
                                "data follows the terminal CBR move");
                    }
                    return;
                }
            }
        }
        throw error("TRUNCATED_RECORD", offset,
                "CBR ended before its continuation or variation record");
    }

    private static TextValue readVariableText(byte[] bytes, int lengthOffset, String label)
            throws CbrException {
        if (lengthOffset + 4 > bytes.length) {
            throw error("TRUNCATED_TEXT", lengthOffset, "missing " + label + " length");
        }
        long length = Integer.toUnsignedLong(littleEndian(bytes, lengthOffset));
        if (length > MAX_COMMENT_BYTES) {
            throw error("COMMENT_LIMIT", lengthOffset, label + " exceeds its byte limit");
        }
        if ((length & 1) != 0) {
            throw error("INVALID_TEXT_LENGTH", lengthOffset,
                    label + " UTF-16LE byte length must be even");
        }
        int textOffset = lengthOffset + 4;
        long next = (long) textOffset + length;
        if (next > bytes.length) {
            throw error("TRUNCATED_TEXT", textOffset, "truncated " + label);
        }
        String value = decodeUtf16(bytes, textOffset, (int) length, label);
        if (value.length() > MAX_COMMENT_CHARS) {
            throw error("COMMENT_LIMIT", textOffset, label + " exceeds its character limit");
        }
        return new TextValue(value, (int) next);
    }

    private static void writeRecords(GameTree tree, BoundedBuffer output) throws CbrException {
        ArrayDeque<EmitNode> pending = new ArrayDeque<>();
        pushChildren(tree, tree.root(), 0, pending);
        int emitted = 0;
        while (!pending.isEmpty()) {
            EmitNode task = pending.pop();
            if (++emitted > MAX_MOVES) {
                throw error("MOVE_LIMIT", 0, "CBR move limit exceeded");
            }
            if (task.variationDepth() > MAX_VARIATION_DEPTH) {
                throw error("VARIATION_LIMIT", 0, "CBR variation nesting limit exceeded");
            }
            GameTree.Node node = task.node();
            byte[] comment = encodeVariableText(node.comment(), 0);
            int flag = node.children().isEmpty() ? 0x01 : 0;
            if (task.hasSibling()) flag |= 0x02;
            if (comment.length > 0) flag |= 0x04;
            String move = node.move();
            output.write(new byte[]{(byte) flag, 0,
                    (byte) boardIndex(move, 0), (byte) boardIndex(move, 2)});
            if (comment.length > 0) {
                output.writeInt(comment.length);
                output.write(comment);
            }
            int childVariationDepth = task.variationDepth() + (task.hasSibling() ? 1 : 0);
            pushChildren(tree, node, childVariationDepth, pending);
        }
    }

    private static void pushChildren(GameTree tree,
                                     GameTree.Node parent,
                                     int depth,
                                     ArrayDeque<EmitNode> pending) {
        List<UUID> children = parent.children();
        for (int index = children.size() - 1; index >= 0; index--) {
            pending.push(new EmitNode(tree.node(children.get(index)),
                    index + 1 < children.size(), depth));
        }
    }

    private static byte[] encodeBoard(String fen) throws CbrException {
        byte[] board = new byte[90];
        String[] ranks = fen.substring(0, fen.indexOf(' ')).split("/", -1);
        for (int row = 0; row < ranks.length; row++) {
            int file = 0;
            for (char symbol : ranks[row].toCharArray()) {
                if (Character.isDigit(symbol)) {
                    file += symbol - '0';
                } else {
                    Integer code = PIECE_CODES.get(symbol);
                    if (code == null) {
                        throw error("INVALID_POSITION", 2_120,
                                "position contains an unsupported CBR piece");
                    }
                    board[row * 9 + file++] = code.byteValue();
                }
            }
        }
        return board;
    }

    private static List<Notice> collectWriteNotices(ManualDocument document)
            throws CbrException {
        List<Notice> notices = new ArrayList<>();
        addNotice(notices, new Notice("UNVERIFIED_EXTERNAL_WRITER",
                "CBR v2 export follows a community-reversed layout; validate it in CCBridge before relying on interchange",
                0));
        List<String> supported = TEXT_FIELDS.stream().map(TextField::name).toList();
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
                        "CBR v2 has no verified engine-evaluation field", 0));
                evaluationReported = true;
            }
            pending.addAll(document.tree().children(node.id()));
        }
        return List.copyOf(notices);
    }

    private static void writeFixedText(byte[] header, TextField field, String value)
            throws CbrException {
        byte[] encoded = encodeUtf16(value, field.size() - 2, field.offset(), field.name());
        System.arraycopy(encoded, 0, header, field.offset(), encoded.length);
    }

    private static byte[] encodeVariableText(String value, int offset) throws CbrException {
        return encodeUtf16(value, MAX_COMMENT_BYTES, offset, "comment");
    }

    private static byte[] encodeUtf16(String value, int limit, int offset, String label)
            throws CbrException {
        if (value.indexOf('\0') >= 0) {
            throw error("UNREPRESENTABLE_TEXT", offset,
                    label + " contains an embedded NUL character");
        }
        try {
            ByteBuffer buffer = StandardCharsets.UTF_16LE.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] encoded = new byte[buffer.remaining()];
            buffer.get(encoded);
            if (encoded.length > limit) {
                throw error(label.equals("comment") ? "COMMENT_LIMIT" : "TEXT_LIMIT",
                        offset, label + " exceeds " + limit + " UTF-16LE bytes");
            }
            return encoded;
        } catch (CharacterCodingException invalid) {
            throw error("UNREPRESENTABLE_TEXT", offset,
                    label + " contains malformed UTF-16 text");
        }
    }

    private static String decodeUtf16(byte[] bytes, int offset, int length, String label)
            throws CbrException {
        if ((length & 1) != 0) {
            throw error("INVALID_TEXT_LENGTH", offset,
                    label + " UTF-16LE byte length must be even");
        }
        try {
            return StandardCharsets.UTF_16LE.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, length)).toString();
        } catch (CharacterCodingException invalid) {
            throw error("INVALID_ENCODING", offset, "invalid UTF-16LE " + label);
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

    private static String coordinate(int index) {
        return new String(new char[]{(char) ('a' + index % 9),
                (char) ('9' - index / 9)});
    }

    private static int boardIndex(String move, int offset) {
        int file = move.charAt(offset) - 'a';
        int rank = move.charAt(offset + 1) - '0';
        return (9 - rank) * 9 + file;
    }

    private static boolean allZero(byte[] bytes, int offset, int length) {
        for (int index = 0; index < length; index++) {
            if (bytes[offset + index] != 0) return false;
        }
        return true;
    }

    private static int littleEndian(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | (bytes[offset + 1] & 0xff) << 8
                | (bytes[offset + 2] & 0xff) << 16
                | (bytes[offset + 3] & 0xff) << 24;
    }

    private static int littleUnsignedShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | (bytes[offset + 1] & 0xff) << 8;
    }

    private static void putShort(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }

    private static ManualDocument.Result result(int value) {
        return switch (value) {
            case 0 -> ManualDocument.Result.ONGOING;
            case 1 -> ManualDocument.Result.RED_WIN;
            case 2 -> ManualDocument.Result.BLACK_WIN;
            case 3, 4 -> ManualDocument.Result.DRAW;
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

    private static void addNotice(List<Notice> notices, Notice notice) throws CbrException {
        if (notices.size() >= MAX_NOTICES) {
            throw error("NOTICE_LIMIT", notice.offset(), "CBR notice limit exceeded");
        }
        notices.add(notice);
    }

    private static String safeMessage(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message;
    }

    private static CbrException error(String code, int offset, String message) {
        return new CbrException(code, Math.max(0, offset), message);
    }

    public record Notice(String code, String message, int offset) {
        public Notice {
            code = Objects.requireNonNull(code, "code");
            message = Objects.requireNonNull(message, "message");
            if (code.isBlank() || message.isBlank() || offset < 0) {
                throw new IllegalArgumentException("invalid CBR notice");
            }
        }
    }

    public record ReadResult(ManualDocument document, List<Notice> notices) {
        public ReadResult {
            Objects.requireNonNull(document, "document");
            notices = List.copyOf(Objects.requireNonNull(notices, "notices"));
            if (notices.size() > MAX_NOTICES) {
                throw new IllegalArgumentException("CBR notice limit exceeded");
            }
        }
    }

    public record WriteResult(List<Notice> notices) {
        public WriteResult {
            notices = List.copyOf(Objects.requireNonNull(notices, "notices"));
            if (notices.size() > MAX_NOTICES) {
                throw new IllegalArgumentException("CBR notice limit exceeded");
            }
        }
    }

    public static final class CbrException extends IOException {
        private final String code;
        private final int offset;

        private CbrException(String code, int offset, String message) {
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
    private record TextValue(String value, int nextOffset) { }
    private record EmitNode(GameTree.Node node, boolean hasSibling, int variationDepth) { }

    private static final class BoundedBuffer {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(HEADER_SIZE + 4_096);

        private BoundedBuffer(int limit) {
            this.limit = limit;
        }

        private void write(byte[] value) throws CbrException {
            if ((long) bytes.size() + value.length > limit) {
                throw error("SIZE_LIMIT", bytes.size(), "serialized CBR exceeds output limit");
            }
            bytes.writeBytes(value);
        }

        private void writeInt(int value) throws CbrException {
            write(new byte[]{(byte) value, (byte) (value >>> 8),
                    (byte) (value >>> 16), (byte) (value >>> 24)});
        }

        private byte[] toByteArray() {
            return bytes.toByteArray();
        }
    }
}
