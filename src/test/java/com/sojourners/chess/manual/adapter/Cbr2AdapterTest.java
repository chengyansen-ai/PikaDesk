package com.sojourners.chess.manual.adapter;

import com.sojourners.chess.game.tree.GameTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Cbr2AdapterTest {

    @Test
    void importsARealCcbridgeV2FixtureWithoutDroppingTheLastUtf16Character()
            throws Exception {
        byte[] source = fixture(MINIMAL_REAL_FIXTURE);
        assertEquals("6897fe17905018560d49e380edb84d29650d38c80c838987478efe49ffea7c64",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source)));

        Cbr2Adapter.ReadResult result = new Cbr2Adapter().read(
                new ByteArrayInputStream(source));
        ManualDocument document = result.document();

        assertEquals("Test", document.metadata().get("Title"));
        assertEquals(ManualDocument.Result.ONGOING, document.result());
        assertEquals(ManualDocument.STANDARD_FEN, document.tree().initialFen());
        assertEquals("李1", document.tree().root().comment());
        assertEquals(List.of("h2e2|李2", "h7e7|李3"), mainline(document.tree()));
        assertTrue(result.notices().isEmpty());
    }

    @Test
    void importsARealBranchHeavyCcbridgeV2Fixture() throws Exception {
        byte[] source = fixture(BRANCHED_REAL_FIXTURE);
        assertEquals("7c1e44171ee27bb0a11c320a601e746e63b87dcee50a63143acce4cbd2c5a193",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source)));
        Cbr2Adapter.ReadResult result = new Cbr2Adapter().read(
                new ByteArrayInputStream(source));
        GameTree tree = result.document().tree();

        assertEquals("黄丹青讲座004-破解新式弃马局的攻防要领",
                result.document().metadata().get("Title"));
        assertEquals(103, tree.size());
        assertEquals("h2e2", tree.node(tree.root().mainlineChildId().orElseThrow()).move());
        assertTrue(allNodes(tree).stream().anyMatch(node -> node.children().size() > 1));
        GameTree.Node firstBranch = followMainline(tree, 20);
        assertEquals("h7h0", firstBranch.move());
        assertEquals(List.of("c2d4", "e3e4"), tree.children(firstBranch.id()).stream()
                .map(GameTree.Node::move).toList());
        assertTrue(result.notices().stream().anyMatch(notice ->
                notice.code().equals("UNSUPPORTED_STEP_ATTRIBUTE")));
    }

    @Test
    void roundTripPreservesVariationsSideResultMetadataAndComments() throws IOException {
        GameTree tree = GameTree.create(ManualDocument.STANDARD_FEN);
        tree.updateComment(tree.root().id(), "根注释");
        UUID main = tree.insert(tree.root().id(), "b0c2").endNodeId();
        UUID reply = tree.insert(main, "b9c7").endNodeId();
        tree.updateComment(main, "主线");
        tree.updateComment(reply, "应对");
        UUID variation = tree.insert(tree.root().id(), "h0g2").endNodeId();
        tree.updateComment(variation, "变例");
        ManualDocument document = new ManualDocument(tree, ManualDocument.Result.RED_WIN,
                Map.of("Title", "本地测试", "Event", "离线赛", "Date", "2026-08-26",
                        "Site", "上海", "Red", "甲", "Black", "乙"));
        Cbr2Adapter adapter = new Cbr2Adapter();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        Cbr2Adapter.WriteResult write = adapter.write(document, output);
        ManualDocument decoded = adapter.read(
                new ByteArrayInputStream(output.toByteArray())).document();

        assertEquals(List.of("UNVERIFIED_EXTERNAL_WRITER"), write.notices().stream()
                .map(Cbr2Adapter.Notice::code).toList());
        assertEquals(document.result(), decoded.result());
        assertEquals(document.metadata(), decoded.metadata());
        assertEquals(semanticTree(document.tree()), semanticTree(decoded.tree()));
    }

    @Test
    void writesCanonicalV2HeaderSideBoardAndNoMoveTerminator() throws IOException {
        String blackFen = ManualDocument.STANDARD_FEN.replace(" w", " b");
        ManualDocument document = new ManualDocument(GameTree.create(blackFen),
                ManualDocument.Result.DRAW, Map.of("Title", "空谱"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        new Cbr2Adapter().write(document, output);
        byte[] bytes = output.toByteArray();

        assertArrayEquals("CCBridge Record\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                java.util.Arrays.copyOf(bytes, 16));
        assertEquals(2, bytes[19]);
        assertEquals(3, bytes[2076]);
        assertEquals(2, bytes[2116]);
        assertEquals(0x21, bytes[2120]);
        assertArrayEquals(new byte[]{0, 0, 0, 0},
                java.util.Arrays.copyOfRange(bytes, 2214, 2218));
        assertArrayEquals(new byte[]{0, 0, 0, 0},
                java.util.Arrays.copyOfRange(bytes, 2218, 2222));
    }

    @Test
    void unsupportedMetadataAndEvaluationsProduceExplicitWriteNotices() throws IOException {
        GameTree tree = GameTree.create(ManualDocument.STANDARD_FEN);
        UUID move = tree.insert(tree.root().id(), "b0c2").endNodeId();
        tree.updateEvaluation(move, new GameTree.Evaluation(18, null, 10, "Pikafish"));
        ManualDocument document = new ManualDocument(tree, ManualDocument.Result.ONGOING,
                Map.of("Round", "2"));

        Cbr2Adapter.WriteResult result = new Cbr2Adapter().write(
                document, new ByteArrayOutputStream());

        assertTrue(result.notices().stream().anyMatch(notice ->
                notice.code().equals("UNSUPPORTED_METADATA") && notice.message().contains("Round")));
        assertTrue(result.notices().stream().anyMatch(notice ->
                notice.code().equals("UNSUPPORTED_EVALUATION")));
        assertTrue(result.notices().stream().anyMatch(notice ->
                notice.code().equals("UNVERIFIED_EXTERNAL_WRITER")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "MAGIC", "VERSION", "RESULT", "SIDE", "BOARD_CODE", "INVALID_BOARD",
            "ROOT_MARKER", "ROOT_LENGTH", "ODD_ROOT_TEXT", "BAD_UTF16", "STEP_FLAG",
            "STEP_RESERVED", "STEP_COORDINATE", "STEP_LENGTH", "ODD_STEP_TEXT",
            "ILLEGAL_MOVE", "TRAILING_DATA"
    })
    void corruptedInputFailsClosedWithAStructuredOffset(String kind) {
        byte[] source = damage(fixture(MINIMAL_REAL_FIXTURE), kind);

        Cbr2Adapter.CbrException failure = assertThrows(Cbr2Adapter.CbrException.class,
                () -> new Cbr2Adapter().read(new ByteArrayInputStream(source)));

        assertFalse(failure.code().isBlank());
        assertTrue(failure.offset() >= 0);
    }

    @Test
    void shortAndOversizedInputsAreReadWithHardBounds() {
        Cbr2Adapter.CbrException shortFailure = assertThrows(Cbr2Adapter.CbrException.class,
                () -> new Cbr2Adapter().read(new ByteArrayInputStream(new byte[32])));
        CountingInputStream oversized = new CountingInputStream(
                Cbr2Adapter.MAX_INPUT_BYTES + 100_000L);
        Cbr2Adapter.CbrException sizeFailure = assertThrows(Cbr2Adapter.CbrException.class,
                () -> new Cbr2Adapter().read(oversized));

        assertEquals("TRUNCATED_HEADER", shortFailure.code());
        assertEquals("SIZE_LIMIT", sizeFailure.code());
        assertEquals(Cbr2Adapter.MAX_INPUT_BYTES + 1L, oversized.bytesRead());
    }

    @Test
    void duplicateSiblingMoveIsRejectedInsteadOfSilentlyMerged() {
        byte[] source = duplicateFirstMoveAsVariation(fixture(MINIMAL_REAL_FIXTURE));

        Cbr2Adapter.CbrException failure = assertThrows(Cbr2Adapter.CbrException.class,
                () -> new Cbr2Adapter().read(new ByteArrayInputStream(source)));

        assertEquals("DUPLICATE_VARIATION", failure.code());
    }

    @Test
    void oversizedSerializationDoesNotWriteAPartialFile() {
        GameTree tree = GameTree.create(ManualDocument.STANDARD_FEN);
        UUID current = tree.root().id();
        String comment = "x".repeat(16_384);
        String[] cycle = {"b0c2", "b9c7", "c2b0", "c7b9"};
        for (int index = 0; index < 512; index++) {
            current = tree.insert(current, cycle[index % cycle.length]).endNodeId();
            tree.updateComment(current, comment);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ManualDocument document = new ManualDocument(tree, ManualDocument.Result.ONGOING,
                Map.of());

        Cbr2Adapter.CbrException failure = assertThrows(Cbr2Adapter.CbrException.class,
                () -> new Cbr2Adapter().write(document, output));

        assertEquals("SIZE_LIMIT", failure.code());
        assertEquals(0, output.size());
    }

    private static byte[] damage(byte[] original, String kind) {
        byte[] bytes = original.clone();
        switch (kind) {
            case "MAGIC" -> bytes[0] = 'X';
            case "VERSION" -> bytes[19] = 1;
            case "RESULT" -> bytes[2076] = 5;
            case "SIDE" -> bytes[2116] = 0;
            case "BOARD_CODE" -> bytes[2120] = 1;
            case "INVALID_BOARD" -> bytes[2124] = 0;
            case "ROOT_MARKER" -> putInt(bytes, 2214, 3);
            case "ROOT_LENGTH" -> putInt(bytes, 2218, Integer.MAX_VALUE);
            case "ODD_ROOT_TEXT" -> putInt(bytes, 2218, 3);
            case "BAD_UTF16" -> {
                java.util.Arrays.fill(bytes, 180, 308, (byte) 0);
                bytes[181] = (byte) 0xd8;
            }
            case "STEP_FLAG" -> bytes[2226] = 8;
            case "STEP_RESERVED" -> bytes[2227] = 2;
            case "STEP_COORDINATE" -> bytes[2228] = 90;
            case "STEP_LENGTH" -> putInt(bytes, 2230, Integer.MAX_VALUE);
            case "ODD_STEP_TEXT" -> putInt(bytes, 2230, 3);
            case "ILLEGAL_MOVE" -> {
                bytes[2228] = 81;
                bytes[2229] = 2;
            }
            case "TRAILING_DATA" -> {
                bytes = java.util.Arrays.copyOf(bytes, bytes.length + 1);
                bytes[bytes.length - 1] = 1;
            }
            default -> throw new IllegalArgumentException(kind);
        }
        return bytes;
    }

    private static byte[] duplicateFirstMoveAsVariation(byte[] fixture) {
        // Remove both comments and encode two identical sibling records.
        byte[] bytes = java.util.Arrays.copyOf(fixture, 2218 + 8);
        putInt(bytes, 2214, 0);
        bytes[2218] = 3; // leaf + sibling
        bytes[2219] = 0;
        bytes[2220] = 70;
        bytes[2221] = 67;
        bytes[2222] = 1; // final leaf
        bytes[2223] = 0;
        bytes[2224] = 70;
        bytes[2225] = 67;
        return bytes;
    }

    private static void putInt(byte[] bytes, int offset, int value) {
        for (int index = 0; index < 4; index++) {
            bytes[offset + index] = (byte) (value >>> (index * 8));
        }
    }

    private static byte[] fixture(String base64) {
        return Base64.getMimeDecoder().decode(base64);
    }

    private static List<String> mainline(GameTree tree) {
        List<String> result = new ArrayList<>();
        GameTree.Node current = tree.root();
        while (current.mainlineChildId().isPresent()) {
            current = tree.node(current.mainlineChildId().orElseThrow());
            result.add(current.move() + "|" + current.comment());
        }
        return result;
    }

    private static GameTree.Node followMainline(GameTree tree, int plies) {
        GameTree.Node current = tree.root();
        for (int index = 0; index < plies; index++) {
            current = tree.node(current.mainlineChildId().orElseThrow());
        }
        return current;
    }

    private static List<GameTree.Node> allNodes(GameTree tree) {
        List<GameTree.Node> result = new ArrayList<>();
        ArrayDeque<GameTree.Node> pending = new ArrayDeque<>();
        pending.add(tree.root());
        while (!pending.isEmpty()) {
            GameTree.Node node = pending.removeFirst();
            result.add(node);
            pending.addAll(tree.children(node.id()));
        }
        return result;
    }

    private static List<String> semanticTree(GameTree tree) {
        return allNodes(tree).stream().map(node -> node.move() + "|" + node.comment() + "|"
                + node.mainlineChildId().map(id -> tree.node(id).move()).orElse("-") + "|"
                + node.children().stream().map(id -> tree.node(id).move()).toList()).toList();
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

    // GPL-3.0 fixture from walker8088/cchess commit de864834, tests/data/test2.cbr.
    private static final String MINIMAL_REAL_FIXTURE = """
            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
            """;

    // GPL-3.0 fixture from walker8088/cchess commit de864834, tests/data/test.cbr.
    private static final String BRANCHED_REAL_FIXTURE = """
            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
            """;
}
