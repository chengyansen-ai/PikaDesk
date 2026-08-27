package com.sojourners.chess.manual.adapter;

import com.sojourners.chess.game.tree.GameTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Xqf10AdapterTest {

    private static final Charset GBK = Charset.forName("GBK");
    private static final byte[] STANDARD_PIECES = {
            80, 70, 60, 50, 40, 30, 20, 10, 0, 72, 12, 83, 63, 43, 23, 3,
            9, 19, 29, 39, 49, 59, 69, 79, 89, 17, 77, 6, 26, 46, 66, 86
    };

    @Test
    void importsThePublishedXqf10LayoutMoveMetadataResultAndComment() throws IOException {
        byte[] source = documentedOneMove();

        Xqf10Adapter.ReadResult result = new Xqf10Adapter().read(
                new ByteArrayInputStream(source));
        ManualDocument document = result.document();
        GameTree.Node move = document.tree().node(
                document.tree().root().mainlineChildId().orElseThrow());

        assertEquals(ManualDocument.STANDARD_FEN, document.tree().initialFen());
        assertEquals(ManualDocument.Result.BLACK_WIN, document.result());
        assertEquals("仙人指路", document.metadata().get("Title"));
        assertEquals("本地测试", document.metadata().get("Event"));
        assertEquals("FULL", document.metadata().get("XqfType"));
        assertEquals("c3c4", move.move());
        assertEquals("先挺兵", move.comment());
        assertTrue(result.notices().stream().anyMatch(notice ->
                notice.code().equals("ASSUMED_RED_TO_MOVE")));
    }

    @Test
    void canonicalRoundTripPreservesAllRepresentableFields() throws IOException {
        GameTree tree = GameTree.create(ManualDocument.STANDARD_FEN);
        UUID first = tree.insert(tree.root().id(), "b0c2").endNodeId();
        UUID second = tree.insert(first, "b9c7").endNodeId();
        tree.updateComment(first, "先活马");
        tree.updateComment(second, "对称应对");
        ManualDocument document = new ManualDocument(tree, ManualDocument.Result.DRAW,
                Map.ofEntries(Map.entry("Title", "本地棋谱"), Map.entry("Event", "离线测试赛"),
                        Map.entry("Date", "2026-08-26"), Map.entry("Site", "上海"),
                        Map.entry("Red", "甲"), Map.entry("Black", "乙"),
                        Map.entry("Author", "PikaDesk"), Map.entry("Annotator", "本地用户"),
                        Map.entry("TimeControl", "10+5"), Map.entry("RedTime", "09:12"),
                        Map.entry("BlackTime", "09:09"), Map.entry("XqfType", "FULL")));
        Xqf10Adapter adapter = new Xqf10Adapter();
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();

        Xqf10Adapter.WriteResult write = adapter.write(document, encoded);
        Xqf10Adapter.ReadResult decoded = adapter.read(
                new ByteArrayInputStream(encoded.toByteArray()));

        assertTrue(write.notices().isEmpty());
        assertEquals(document.result(), decoded.document().result());
        assertEquals(document.metadata(), decoded.document().metadata());
        assertEquals(List.of("b0c2|先活马", "b9c7|对称应对"),
                mainline(decoded.document().tree()));
        byte[] bytes = encoded.toByteArray();
        assertArrayEquals(new byte[]{0x18, 0x20, (byte) 0xf0, (byte) 0xff},
                Arrays.copyOfRange(bytes, 0x400, 0x404));
        assertArrayEquals(new byte[]{0x22, 0x36, (byte) 0xf0, 0x00},
                Arrays.copyOfRange(bytes, 0x408, 0x40c));
    }

    @Test
    void writesThePublishedEmptyRecordAndStandardPieceOrder() throws IOException {
        ManualDocument document = new ManualDocument(
                GameTree.create(ManualDocument.STANDARD_FEN), ManualDocument.Result.ONGOING,
                Map.of("XqfType", "FULL"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        new Xqf10Adapter().write(document, output);
        byte[] bytes = output.toByteArray();

        assertEquals(0x408, bytes.length);
        assertArrayEquals(new byte[]{'X', 'Q', 0x0a}, Arrays.copyOf(bytes, 3));
        assertArrayEquals(STANDARD_PIECES, Arrays.copyOfRange(bytes, 0x10, 0x30));
        assertArrayEquals(new byte[]{0x18, 0x20, 0x00, (byte) 0xff, 0, 0, 0, 0},
                Arrays.copyOfRange(bytes, 0x400, 0x408));
    }

    @Test
    void inputReaderStopsAtTheConfiguredLimitPlusOneByte() {
        CountingInputStream source = new CountingInputStream(
                Xqf10Adapter.MAX_INPUT_BYTES + 50_000L);

        Xqf10Adapter.XqfException failure = assertThrows(Xqf10Adapter.XqfException.class,
                () -> new Xqf10Adapter().read(source));

        assertEquals("SIZE_LIMIT", failure.code());
        assertEquals(Xqf10Adapter.MAX_INPUT_BYTES + 1L, source.bytesRead());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "BAD_MAGIC", "BAD_VERSION", "RESERVED_HEADER", "BAD_RESULT", "BAD_TYPE",
            "BAD_STRING_LENGTH", "BAD_TEXT_ENCODING", "BAD_METADATA_VALUE", "BAD_ROOT", "BAD_FLAG",
            "BAD_MOVE_RESERVED", "BAD_FROM", "TRUNCATED_COMMENT", "HUGE_COMMENT",
            "BAD_COMMENT_ENCODING", "TRAILING_DATA", "ILLEGAL_MOVE"
    })
    void damagedOrUnsupportedInputFailsClosedWithOffset(String damage) {
        byte[] source = damage(documentedOneMove(), damage);

        Xqf10Adapter.XqfException failure = assertThrows(Xqf10Adapter.XqfException.class,
                () -> new Xqf10Adapter().read(new ByteArrayInputStream(source)));

        assertFalse(failure.code().isBlank());
        assertTrue(failure.offset() >= 0);
    }

    @Test
    void shortHeaderIsRejectedBeforeAnyFixedOffsetIsRead() {
        byte[] shortFile = {'X', 'Q', 0x0a};

        Xqf10Adapter.XqfException failure = assertThrows(Xqf10Adapter.XqfException.class,
                () -> new Xqf10Adapter().read(new ByteArrayInputStream(shortFile)));

        assertEquals("TRUNCATED_HEADER", failure.code());
    }

    @Test
    void encryptedLaterVersionsAreExplicitlyOutsideThisAuditedAdapter() {
        byte[] source = documentedOneMove();
        source[2] = 0x0c;

        Xqf10Adapter.XqfException failure = assertThrows(Xqf10Adapter.XqfException.class,
                () -> new Xqf10Adapter().read(new ByteArrayInputStream(source)));

        assertEquals("UNSUPPORTED_VERSION", failure.code());
    }

    @Test
    void variationsAndBlackToMoveCannotBeSilentlyFlattenedOrFlipped() {
        GameTree variations = GameTree.create(ManualDocument.STANDARD_FEN);
        variations.insert(variations.root().id(), "b0c2");
        variations.insert(variations.root().id(), "h0g2");
        ManualDocument branched = new ManualDocument(variations,
                ManualDocument.Result.ONGOING, Map.of());
        ManualDocument blackToMove = new ManualDocument(
                GameTree.create(ManualDocument.STANDARD_FEN.replace(" w", " b")),
                ManualDocument.Result.ONGOING, Map.of());

        assertEquals("UNSUPPORTED_VARIATIONS", assertThrows(Xqf10Adapter.XqfException.class,
                () -> new Xqf10Adapter().write(branched, new ByteArrayOutputStream())).code());
        assertEquals("UNSUPPORTED_SIDE_TO_MOVE", assertThrows(Xqf10Adapter.XqfException.class,
                () -> new Xqf10Adapter().write(blackToMove, new ByteArrayOutputStream())).code());
    }

    @Test
    void unrepresentableMetadataAndRootCommentsFailBeforeWriting() {
        GameTree rootComment = GameTree.create(ManualDocument.STANDARD_FEN);
        rootComment.updateComment(rootComment.root().id(), "XQF 1.0 没有根注释字段");
        ManualDocument withRootComment = new ManualDocument(rootComment,
                ManualDocument.Result.ONGOING, Map.of());
        ManualDocument emoji = new ManualDocument(GameTree.create(ManualDocument.STANDARD_FEN),
                ManualDocument.Result.ONGOING, Map.of("Title", "测试🙂"));

        assertEquals("UNSUPPORTED_ROOT_COMMENT", assertThrows(Xqf10Adapter.XqfException.class,
                () -> new Xqf10Adapter().write(withRootComment,
                        new ByteArrayOutputStream())).code());
        assertEquals("UNREPRESENTABLE_TEXT", assertThrows(Xqf10Adapter.XqfException.class,
                () -> new Xqf10Adapter().write(emoji, new ByteArrayOutputStream())).code());
    }

    @Test
    void evaluationAndUnknownMetadataAreOmittedOnlyWithExplicitNotices() throws IOException {
        GameTree tree = GameTree.create(ManualDocument.STANDARD_FEN);
        UUID move = tree.insert(tree.root().id(), "b0c2").endNodeId();
        tree.updateEvaluation(move, new GameTree.Evaluation(23, null, 12, "Pikafish"));
        ManualDocument document = new ManualDocument(tree, ManualDocument.Result.ONGOING,
                Map.of("Round", "3"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        Xqf10Adapter.WriteResult result = new Xqf10Adapter().write(document, output);

        assertTrue(result.notices().stream().anyMatch(notice ->
                notice.code().equals("UNSUPPORTED_EVALUATION")));
        assertTrue(result.notices().stream().anyMatch(notice ->
                notice.code().equals("UNSUPPORTED_METADATA") && notice.message().contains("Round")));
    }

    @Test
    void invalidTypeAndOverlongHeaderTextAreRejectedBeforeOutput() {
        ManualDocument badType = new ManualDocument(GameTree.create(ManualDocument.STANDARD_FEN),
                ManualDocument.Result.ONGOING, Map.of("XqfType", "PUZZLE"));
        ManualDocument longTitle = new ManualDocument(GameTree.create(ManualDocument.STANDARD_FEN),
                ManualDocument.Result.ONGOING, Map.of("Title", "x".repeat(64)));

        assertEquals("INVALID_TYPE", assertThrows(Xqf10Adapter.XqfException.class,
                () -> new Xqf10Adapter().write(badType, new ByteArrayOutputStream())).code());
        assertEquals("TEXT_LIMIT", assertThrows(Xqf10Adapter.XqfException.class,
                () -> new Xqf10Adapter().write(longTitle, new ByteArrayOutputStream())).code());
    }

    @Test
    void oversizedSerializationFailsBeforeWritingAnyPartialFile() {
        GameTree tree = GameTree.create(ManualDocument.STANDARD_FEN);
        UUID current = tree.root().id();
        String fullComment = "x".repeat(16_384);
        String[] cycle = {"b0c2", "b9c7", "c2b0", "c7b9"};
        for (int index = 0; index < 1_024; index++) {
            current = tree.insert(current, cycle[index % cycle.length]).endNodeId();
            tree.updateComment(current, fullComment);
        }
        ManualDocument document = new ManualDocument(tree, ManualDocument.Result.ONGOING,
                Map.of());
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        Xqf10Adapter.XqfException failure = assertThrows(Xqf10Adapter.XqfException.class,
                () -> new Xqf10Adapter().write(document, output));

        assertEquals("SIZE_LIMIT", failure.code());
        assertEquals(0, output.size());
    }

    private static byte[] documentedOneMove() {
        byte[] title = "仙人指路".getBytes(GBK);
        byte[] event = "本地测试".getBytes(GBK);
        byte[] comment = "先挺兵".getBytes(GBK);
        byte[] bytes = new byte[0x410 + comment.length];
        bytes[0] = 'X';
        bytes[1] = 'Q';
        bytes[2] = 0x0a;
        System.arraycopy(STANDARD_PIECES, 0, bytes, 0x10, STANDARD_PIECES.length);
        bytes[0x33] = 0x02;
        putBlock(bytes, 0x50, 64, title);
        putBlock(bytes, 0xd0, 64, event);
        bytes[0x400] = 0x18;
        bytes[0x401] = 0x20;
        bytes[0x402] = (byte) 0xf0;
        bytes[0x403] = (byte) 0xff;
        bytes[0x408] = 0x2f; // (2,3) + 24: 兵七进一
        bytes[0x409] = 0x38; // (2,4) + 32
        bytes[0x40a] = 0;
        bytes[0x40b] = 0;
        putLittleEndian(bytes, 0x40c, comment.length);
        System.arraycopy(comment, 0, bytes, 0x410, comment.length);
        return bytes;
    }

    private static byte[] damage(byte[] original, String damage) {
        byte[] bytes = original.clone();
        switch (damage) {
            case "BAD_MAGIC" -> bytes[0] = 'Z';
            case "BAD_VERSION" -> bytes[2] = 9;
            case "RESERVED_HEADER" -> bytes[3] = 1;
            case "BAD_RESULT" -> bytes[0x33] = 4;
            case "BAD_TYPE" -> bytes[0x40] = 4;
            case "BAD_STRING_LENGTH" -> bytes[0x50] = 64;
            case "BAD_TEXT_ENCODING" -> {
                Arrays.fill(bytes, 0x51, 0x90, (byte) 0);
                bytes[0x50] = 1;
                bytes[0x51] = (byte) 0x81;
            }
            case "BAD_METADATA_VALUE" -> {
                Arrays.fill(bytes, 0x51, 0x90, (byte) 0);
                bytes[0x50] = 1;
            }
            case "BAD_ROOT" -> bytes[0x403] = 0;
            case "BAD_FLAG" -> bytes[0x40a] = 1;
            case "BAD_MOVE_RESERVED" -> bytes[0x40b] = 1;
            case "BAD_FROM" -> bytes[0x408] = 0;
            case "TRUNCATED_COMMENT" -> bytes = Arrays.copyOf(bytes, bytes.length - 1);
            case "HUGE_COMMENT" -> putLittleEndian(bytes, 0x40c, Integer.MAX_VALUE);
            case "BAD_COMMENT_ENCODING" -> {
                bytes = Arrays.copyOf(bytes, 0x411);
                putLittleEndian(bytes, 0x40c, 1);
                bytes[0x410] = (byte) 0x81;
            }
            case "TRAILING_DATA" -> bytes = Arrays.copyOf(bytes, bytes.length + 1);
            case "ILLEGAL_MOVE" -> {
                bytes[0x408] = 0x18; // a0 rook
                bytes[0x409] = 0x3c; // c8, blocked and illegal
            }
            default -> throw new IllegalArgumentException(damage);
        }
        return bytes;
    }

    private static void putBlock(byte[] target, int offset, int blockSize, byte[] text) {
        if (text.length >= blockSize) throw new IllegalArgumentException("test text too large");
        target[offset] = (byte) text.length;
        System.arraycopy(text, 0, target, offset + 1, text.length);
    }

    private static void putLittleEndian(byte[] target, int offset, int value) {
        for (int index = 0; index < 4; index++) {
            target[offset + index] = (byte) (value >>> (index * 8));
        }
    }

    private static List<String> mainline(GameTree tree) {
        java.util.ArrayList<String> moves = new java.util.ArrayList<>();
        GameTree.Node current = tree.root();
        while (current.mainlineChildId().isPresent()) {
            current = tree.node(current.mainlineChildId().orElseThrow());
            moves.add(current.move() + "|" + current.comment());
        }
        return moves;
    }

    private static final class CountingInputStream extends InputStream {
        private final long length;
        private long position;

        private CountingInputStream(long length) {
            this.length = length;
        }

        @Override
        public int read() {
            if (position >= length) return -1;
            position++;
            return 0;
        }

        private long bytesRead() {
            return position;
        }
    }
}
