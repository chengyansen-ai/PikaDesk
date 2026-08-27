package com.sojourners.chess.manual.adapter;

import com.sojourners.chess.game.tree.GameTree;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded Xiangqi PGN adapter for the unambiguous ICCS notation. */
public final class PgnAdapter {

    public static final int MAX_INPUT_BYTES = 4 * 1024 * 1024;
    public static final int MAX_OUTPUT_BYTES = 4 * 1024 * 1024;
    public static final int MAX_NOTICES = 1_024;
    private static final int MAX_TAGS = 64;
    private static final int MAX_TAG_LINE_CHARS = 4_096;
    private static final int MAX_COMMENT_CHARS = 16_384;
    private static final int MAX_VARIATION_DEPTH = 256;
    private static final int MAX_MOVES = 50_000;
    private static final Pattern TAG = Pattern.compile(
            "\\[([A-Za-z][A-Za-z0-9_]{0,31})[ \\t]+\"((?:\\\\[\\\\\"]|[^\"\\\\])*)\"\\]");
    private static final Pattern MOVE_NUMBER = Pattern.compile("[0-9]+\\.{1,3}");
    private static final Pattern MOVE = Pattern.compile("(?i)[a-i][0-9]-?[a-i][0-9]");
    private static final Pattern NAG = Pattern.compile("\\$[0-9]+");

    public ReadResult read(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        byte[] bytes = input.readNBytes(MAX_INPUT_BYTES + 1);
        if (bytes.length > MAX_INPUT_BYTES) {
            throw error("SIZE_LIMIT", MAX_INPUT_BYTES,
                    "PGN exceeds " + MAX_INPUT_BYTES + " bytes");
        }

        List<Notice> notices = new ArrayList<>();
        String text = decode(bytes, notices);
        TagSection tagSection = parseTags(text);
        Map<String, String> tags = tagSection.tags();
        if (!"Chinese Chess".equals(tags.get("Game"))) {
            throw error("INVALID_GAME", 0, "Game tag must be Chinese Chess");
        }
        String format = tags.getOrDefault("Format", "Chinese");
        if (!format.equals("ICCS")) {
            throw error("UNSUPPORTED_FORMAT", 0,
                    "only ICCS PGN is currently supported; found " + format);
        }

        String initialFen = tags.getOrDefault("FEN", ManualDocument.STANDARD_FEN);
        GameTree tree;
        try {
            tree = GameTree.create(initialFen);
        } catch (IllegalArgumentException invalid) {
            throw error("INVALID_FEN", 0, safeMessage(invalid));
        }

        ManualDocument.Result tagResult = null;
        if (tags.containsKey("Result")) {
            try {
                tagResult = ManualDocument.Result.fromToken(tags.get("Result"));
            } catch (IllegalArgumentException invalid) {
                throw error("INVALID_RESULT", 0, safeMessage(invalid));
            }
        }
        ParseMovesResult moves = parseMoves(
                text.substring(tagSection.moveTextOffset()),
                tagSection.moveTextOffset(), tree, notices);
        if (tagResult != null && tagResult != moves.result()) {
            throw error("RESULT_MISMATCH", tagSection.moveTextOffset(),
                    "Result tag and movetext result differ");
        }

        Map<String, String> metadata = new LinkedHashMap<>(tags);
        metadata.keySet().removeAll(List.of("Game", "FEN", "Format", "Result"));
        ManualDocument document;
        try {
            document = new ManualDocument(tree, moves.result(), metadata);
        } catch (IllegalArgumentException invalid) {
            throw error("INVALID_METADATA", 0, safeMessage(invalid));
        }
        return new ReadResult(document, notices);
    }

    public WriteResult write(ManualDocument document, OutputStream output) throws IOException {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(output, "output");
        if (document.tree().size() - 1 > MAX_MOVES) {
            throw error("MOVE_LIMIT", 0, "PGN move limit exceeded");
        }

        StringBuilder text = new StringBuilder(4_096);
        appendTag(text, "Game", "Chinese Chess");
        for (Map.Entry<String, String> entry : document.metadata().entrySet()) {
            appendTag(text, entry.getKey(), entry.getValue());
        }
        appendTag(text, "Result", document.result().token());
        appendTag(text, "FEN", document.tree().initialFen());
        appendTag(text, "Format", "ICCS");
        text.append('\n');
        ensureOutputLimit(text);

        appendComment(text, document.tree().root().comment());
        emitTree(document.tree(), text);
        appendToken(text, document.result().token());
        text.append('\n');
        ensureOutputLimit(text);

        byte[] encoded = text.toString().getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_OUTPUT_BYTES) {
            throw error("SIZE_LIMIT", 0, "serialized PGN exceeds output limit");
        }
        output.write(encoded);
        output.flush();
        return new WriteResult(List.of());
    }

    private static String decode(byte[] bytes, List<Notice> notices) throws PgnException {
        if (startsWith(bytes, 0xef, 0xbb, 0xbf)) {
            return strictDecode(bytes, 3, StandardCharsets.UTF_8, "UTF-8");
        }
        if (startsWith(bytes, 0xff, 0xfe)) {
            addNotice(notices,
                    new Notice("LEGACY_ENCODING", "decoded UTF-16LE PGN", 0));
            return strictDecode(bytes, 2, StandardCharsets.UTF_16LE, "UTF-16LE");
        }
        if (startsWith(bytes, 0xfe, 0xff)) {
            addNotice(notices,
                    new Notice("LEGACY_ENCODING", "decoded UTF-16BE PGN", 0));
            return strictDecode(bytes, 2, StandardCharsets.UTF_16BE, "UTF-16BE");
        }
        try {
            return strictDecode(bytes, 0, StandardCharsets.UTF_8, "UTF-8");
        } catch (PgnException notUtf8) {
            try {
                String decoded = strictDecode(bytes, 0,
                        Charset.forName("GB18030"), "GB18030");
                addNotice(notices,
                        new Notice("LEGACY_ENCODING", "decoded GB18030 PGN", 0));
                return decoded;
            } catch (PgnException notGb18030) {
                throw error("INVALID_ENCODING", 0,
                        "PGN is neither strict UTF-8 nor GB18030");
            }
        }
    }

    private static String strictDecode(byte[] bytes,
                                       int offset,
                                       Charset charset,
                                       String label) throws PgnException {
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (CharacterCodingException invalid) {
            throw error("INVALID_ENCODING", offset, "invalid " + label + " text");
        }
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if ((bytes[index] & 0xff) != prefix[index]) return false;
        }
        return true;
    }

    private static TagSection parseTags(String text) throws PgnException {
        Map<String, String> tags = new LinkedHashMap<>();
        int cursor = 0;
        while (cursor < text.length()) {
            int lineEnd = text.indexOf('\n', cursor);
            if (lineEnd < 0) lineEnd = text.length();
            int contentEnd = lineEnd > cursor && text.charAt(lineEnd - 1) == '\r'
                    ? lineEnd - 1 : lineEnd;
            String line = text.substring(cursor, contentEnd).strip();
            int next = lineEnd < text.length() ? lineEnd + 1 : lineEnd;
            if (line.isEmpty()) {
                cursor = next;
                continue;
            }
            if (!line.startsWith("[")) break;
            if (line.length() > MAX_TAG_LINE_CHARS) {
                throw error("TAG_LIMIT", cursor, "PGN tag line is too long");
            }
            Matcher matcher = TAG.matcher(line);
            if (!matcher.matches()) {
                throw error("MALFORMED_TAG", cursor, "malformed PGN tag");
            }
            if (tags.size() >= MAX_TAGS) {
                throw error("TAG_LIMIT", cursor, "PGN tag count exceeded");
            }
            String name = matcher.group(1);
            if (tags.isEmpty() && !name.equals("Game")) {
                throw error("GAME_TAG_ORDER", cursor, "Game must be the first PGN tag");
            }
            String value = unescapeTag(matcher.group(2), cursor);
            if (value.length() > 1_024) {
                throw error("TAG_LIMIT", cursor, "PGN tag value is too long");
            }
            if (tags.put(name, value) != null) {
                throw error("DUPLICATE_TAG", cursor, "duplicate PGN tag: " + name);
            }
            cursor = next;
        }
        if (tags.isEmpty()) {
            throw error("MISSING_TAGS", 0, "PGN tag section is required");
        }
        return new TagSection(tags, cursor);
    }

    private static String unescapeTag(String encoded, int offset) throws PgnException {
        StringBuilder value = new StringBuilder(encoded.length());
        for (int index = 0; index < encoded.length(); index++) {
            char current = encoded.charAt(index);
            if (current != '\\') {
                value.append(current);
                continue;
            }
            if (++index >= encoded.length()) {
                throw error("MALFORMED_TAG", offset, "unterminated tag escape");
            }
            char escaped = encoded.charAt(index);
            if (escaped != '\\' && escaped != '"') {
                throw error("MALFORMED_TAG", offset, "unsupported tag escape");
            }
            value.append(escaped);
        }
        return value.toString();
    }

    private static ParseMovesResult parseMoves(String moveText,
                                               int baseOffset,
                                               GameTree tree,
                                               List<Notice> notices) throws PgnException {
        Lexer lexer = new Lexer(moveText, baseOffset);
        UUID current = tree.root().id();
        Deque<VariationFrame> variations = new ArrayDeque<>();
        ManualDocument.Result result = null;
        int moves = 0;
        Lexeme lexeme;
        while ((lexeme = lexer.next()) != null) {
            if (lexeme.type() == LexemeType.COMMENT) {
                String existing = tree.node(current).comment();
                String combined = existing.isEmpty()
                        ? lexeme.text() : existing + "\n" + lexeme.text();
                if (combined.length() > MAX_COMMENT_CHARS) {
                    throw error("COMMENT_LIMIT", lexeme.offset(), "comment is too long");
                }
                tree.updateComment(current, combined);
                continue;
            }
            if (result != null) {
                throw error("TRAILING_MOVETEXT", lexeme.offset(),
                        "only comments may follow the result");
            }
            if (lexeme.type() == LexemeType.OPEN_VARIATION) {
                if (current.equals(tree.root().id())) {
                    throw error("INVALID_VARIATION", lexeme.offset(),
                            "variation has no preceding move");
                }
                if (variations.size() >= MAX_VARIATION_DEPTH) {
                    throw error("VARIATION_LIMIT", lexeme.offset(),
                            "variation nesting limit exceeded");
                }
                UUID base = tree.node(current).parentId().orElseThrow();
                variations.push(new VariationFrame(current, base));
                current = base;
                continue;
            }
            if (lexeme.type() == LexemeType.CLOSE_VARIATION) {
                if (variations.isEmpty()) {
                    throw error("INVALID_VARIATION", lexeme.offset(),
                            "unmatched closing variation");
                }
                VariationFrame frame = variations.pop();
                if (current.equals(frame.base())) {
                    throw error("INVALID_VARIATION", lexeme.offset(), "empty variation");
                }
                current = frame.resume();
                continue;
            }

            String token = lexeme.text();
            if (MOVE_NUMBER.matcher(token).matches()) continue;
            if (NAG.matcher(token).matches()) {
                addNotice(notices, new Notice("UNSUPPORTED_NAG",
                        "numeric annotation glyph was not retained", lexeme.offset()));
                continue;
            }
            ManualDocument.Result tokenResult = resultToken(token);
            if (tokenResult != null) {
                if (!variations.isEmpty()) {
                    throw error("INVALID_RESULT", lexeme.offset(),
                            "result cannot appear inside a variation");
                }
                result = tokenResult;
                continue;
            }
            if (!MOVE.matcher(token).matches()) {
                throw error("UNSUPPORTED_TOKEN", lexeme.offset(),
                        "unsupported ICCS movetext token");
            }
            if (++moves > MAX_MOVES) {
                throw error("MOVE_LIMIT", lexeme.offset(), "PGN move limit exceeded");
            }
            String move = token.toLowerCase(java.util.Locale.ROOT).replace("-", "");
            try {
                GameTree.MergeResult inserted = tree.insert(current, move);
                if (!variations.isEmpty() && inserted.createdNodes() == 0) {
                    throw error("DUPLICATE_VARIATION", lexeme.offset(),
                            "variation duplicates an existing move at the same position");
                }
                current = inserted.endNodeId();
            } catch (PgnException duplicate) {
                throw duplicate;
            } catch (IllegalArgumentException | IllegalStateException invalid) {
                throw error("INVALID_MOVE", lexeme.offset(), safeMessage(invalid));
            }
        }
        if (!variations.isEmpty()) {
            throw error("INVALID_VARIATION", baseOffset + moveText.length(),
                    "unterminated variation");
        }
        if (result == null) {
            throw error("MISSING_RESULT", baseOffset + moveText.length(),
                    "PGN movetext must end with a result");
        }
        return new ParseMovesResult(result);
    }

    private static ManualDocument.Result resultToken(String token) {
        return switch (token) {
            case "1-0" -> ManualDocument.Result.RED_WIN;
            case "0-1" -> ManualDocument.Result.BLACK_WIN;
            case "1/2-1/2" -> ManualDocument.Result.DRAW;
            case "*" -> ManualDocument.Result.ONGOING;
            default -> null;
        };
    }

    private static void emitTree(GameTree tree, StringBuilder output) throws PgnException {
        Deque<EmitTask> tasks = new ArrayDeque<>();
        tasks.push(new EmitTask(EmitType.CHOICE, tree.root().id(), 0));
        boolean redStarts = tree.initialFen().endsWith(" w");
        int emitted = 0;
        while (!tasks.isEmpty()) {
            EmitTask task = tasks.pop();
            if (task.type() == EmitType.OPEN) {
                appendToken(output, "(");
                continue;
            }
            if (task.type() == EmitType.CLOSE) {
                appendToken(output, ")");
                continue;
            }
            if (task.type() == EmitType.NODE) {
                appendNode(output, tree.node(task.nodeId()), task.ply(), redStarts);
                if (++emitted > MAX_MOVES) {
                    throw error("MOVE_LIMIT", 0, "PGN move limit exceeded");
                }
                continue;
            }

            GameTree.Node parent = tree.node(task.nodeId());
            if (parent.children().isEmpty()) continue;
            UUID mainId = parent.mainlineChildId().orElseThrow(() ->
                    new IllegalStateException("tree position has no mainline child"));
            GameTree.Node main = tree.node(mainId);
            appendNode(output, main, task.ply(), redStarts);
            if (++emitted > MAX_MOVES) {
                throw error("MOVE_LIMIT", 0, "PGN move limit exceeded");
            }
            tasks.push(new EmitTask(EmitType.CHOICE, main.id(), task.ply() + 1));
            List<UUID> children = parent.children();
            for (int index = children.size() - 1; index >= 0; index--) {
                UUID alternative = children.get(index);
                if (alternative.equals(mainId)) continue;
                tasks.push(new EmitTask(EmitType.CLOSE, alternative, task.ply()));
                tasks.push(new EmitTask(EmitType.CHOICE, alternative, task.ply() + 1));
                tasks.push(new EmitTask(EmitType.NODE, alternative, task.ply()));
                tasks.push(new EmitTask(EmitType.OPEN, alternative, task.ply()));
            }
        }
    }

    private static void appendNode(StringBuilder output,
                                   GameTree.Node node,
                                   int ply,
                                   boolean redStarts) throws PgnException {
        boolean redTurn = redStarts ? ply % 2 == 0 : ply % 2 != 0;
        int moveNumber = redStarts ? ply / 2 + 1 : (ply + 1) / 2 + 1;
        appendToken(output, moveNumber + (redTurn ? "." : "..."));
        String move = node.move().toUpperCase(java.util.Locale.ROOT);
        appendToken(output, move.substring(0, 2) + "-" + move.substring(2));
        appendComment(output, node.comment());
    }

    private static void appendComment(StringBuilder output, String comment) throws PgnException {
        if (comment.isEmpty()) return;
        if (comment.length() > MAX_COMMENT_CHARS || comment.indexOf('}') >= 0) {
            throw error("UNREPRESENTABLE_COMMENT", 0,
                    "PGN brace comments cannot losslessly represent this comment");
        }
        appendToken(output, "{" + comment + "}");
    }

    private static void appendTag(StringBuilder output,
                                  String name,
                                  String value) throws PgnException {
        output.append('[').append(name).append(" \"")
                .append(value.replace("\\", "\\\\").replace("\"", "\\\""))
                .append("\"]\n");
        ensureOutputLimit(output);
    }

    private static void appendToken(StringBuilder output,
                                    String token) throws PgnException {
        if (!output.isEmpty() && !Character.isWhitespace(output.charAt(output.length() - 1))) {
            output.append(' ');
        }
        output.append(token);
        ensureOutputLimit(output);
    }

    private static void ensureOutputLimit(StringBuilder output) throws PgnException {
        if (output.length() > MAX_OUTPUT_BYTES) {
            throw error("SIZE_LIMIT", 0, "serialized PGN exceeds output limit");
        }
    }

    private static void addNotice(List<Notice> notices, Notice notice) throws PgnException {
        if (notices.size() >= MAX_NOTICES) {
            throw error("NOTICE_LIMIT", notice.offset(), "PGN notice limit exceeded");
        }
        notices.add(notice);
    }

    private static String safeMessage(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message;
    }

    private static PgnException error(String code, int offset, String message) {
        return new PgnException(code, Math.max(0, offset), message);
    }

    public record Notice(String code, String message, int offset) {
        public Notice {
            code = Objects.requireNonNull(code, "code");
            message = Objects.requireNonNull(message, "message");
            if (code.isBlank() || message.isBlank() || offset < 0) {
                throw new IllegalArgumentException("invalid PGN notice");
            }
        }
    }

    public record ReadResult(ManualDocument document, List<Notice> notices) {
        public ReadResult {
            Objects.requireNonNull(document, "document");
            notices = List.copyOf(Objects.requireNonNull(notices, "notices"));
            if (notices.size() > MAX_NOTICES) {
                throw new IllegalArgumentException("PGN notice limit exceeded");
            }
        }
    }

    public record WriteResult(List<Notice> notices) {
        public WriteResult {
            notices = List.copyOf(Objects.requireNonNull(notices, "notices"));
            if (notices.size() > MAX_NOTICES) {
                throw new IllegalArgumentException("PGN notice limit exceeded");
            }
        }
    }

    public static final class PgnException extends IOException {
        private final String code;
        private final int offset;

        private PgnException(String code, int offset, String message) {
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

    private enum LexemeType { WORD, COMMENT, OPEN_VARIATION, CLOSE_VARIATION }
    private enum EmitType { CHOICE, NODE, OPEN, CLOSE }

    private record Lexeme(LexemeType type, String text, int offset) { }
    private record TagSection(Map<String, String> tags, int moveTextOffset) { }
    private record ParseMovesResult(ManualDocument.Result result) { }
    private record VariationFrame(UUID resume, UUID base) { }
    private record EmitTask(EmitType type, UUID nodeId, int ply) { }

    private static final class Lexer {
        private final String text;
        private final int baseOffset;
        private int index;

        private Lexer(String text, int baseOffset) {
            this.text = text;
            this.baseOffset = baseOffset;
        }

        private Lexeme next() throws PgnException {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
            if (index >= text.length()) return null;
            int start = index;
            char current = text.charAt(index++);
            if (current == '(') return lexeme(LexemeType.OPEN_VARIATION, "(", start);
            if (current == ')') return lexeme(LexemeType.CLOSE_VARIATION, ")", start);
            if (current == '}') {
                throw error("INVALID_COMMENT", baseOffset + start,
                        "unmatched closing comment brace");
            }
            if (current == '{') {
                int end = text.indexOf('}', index);
                if (end < 0) {
                    throw error("INVALID_COMMENT", baseOffset + start,
                            "unterminated brace comment");
                }
                String comment = text.substring(index, end).trim();
                if (comment.indexOf('{') >= 0) {
                    throw error("INVALID_COMMENT", baseOffset + start,
                            "nested brace comment is not supported");
                }
                if (comment.length() > MAX_COMMENT_CHARS) {
                    throw error("COMMENT_LIMIT", baseOffset + start, "comment is too long");
                }
                index = end + 1;
                return lexeme(LexemeType.COMMENT, comment, start);
            }
            if (current == ';') {
                int end = index;
                while (end < text.length() && text.charAt(end) != '\n'
                        && text.charAt(end) != '\r') end++;
                String comment = text.substring(index, end).trim();
                if (comment.length() > MAX_COMMENT_CHARS) {
                    throw error("COMMENT_LIMIT", baseOffset + start, "comment is too long");
                }
                index = end;
                return lexeme(LexemeType.COMMENT, comment, start);
            }

            while (index < text.length()) {
                char next = text.charAt(index);
                if (Character.isWhitespace(next) || next == '(' || next == ')'
                        || next == '{' || next == '}' || next == ';') break;
                index++;
            }
            String word = text.substring(start, index);
            if (word.length() > 64) {
                throw error("TOKEN_LIMIT", baseOffset + start, "movetext token is too long");
            }
            return lexeme(LexemeType.WORD, word, start);
        }

        private Lexeme lexeme(LexemeType type, String value, int localOffset) {
            return new Lexeme(type, value, baseOffset + localOffset);
        }
    }
}
