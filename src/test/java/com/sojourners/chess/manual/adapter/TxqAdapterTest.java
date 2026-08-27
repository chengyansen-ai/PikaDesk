package com.sojourners.chess.manual.adapter;

import com.sojourners.chess.game.tree.GameTree;
import com.sojourners.chess.manual.ChessManual;
import com.sojourners.chess.manual.TxqChessManualImpl;
import com.sojourners.chess.model.ManualRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TxqAdapterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void migratesTheExactLegacyModelWithMainlineCommentsMetadataAndScores() throws Exception {
        byte[] legacyBytes = serialize(legacyManual());
        assertArrayEquals(new byte[]{(byte) 0xac, (byte) 0xed, 0, 5},
                java.util.Arrays.copyOf(legacyBytes, 4));

        TxqAdapter.ReadResult result = new TxqAdapter().read(
                new ByteArrayInputStream(legacyBytes));
        ManualDocument document = result.document();

        assertEquals(ManualDocument.Result.ONGOING, document.result());
        assertEquals(Map.of("Title", "旧谱", "Date", "2026-08-26", "Site", "上海",
                "Red", "甲", "Black", "乙"), document.metadata());
        assertEquals("根评注", document.tree().root().comment());
        assertEquals(List.of("h0g2", "b0c2"), document.tree().children(
                document.tree().root().id()).stream().map(GameTree.Node::move).toList());
        GameTree.Node mainline = document.tree().node(
                document.tree().root().mainlineChildId().orElseThrow());
        assertEquals("h0g2", mainline.move());
        assertEquals("主线", mainline.comment());
        assertEquals(32, mainline.evaluation().orElseThrow().centipawns());
        assertEquals("h9g7", document.tree().node(
                mainline.mainlineChildId().orElseThrow()).move());
        assertEquals(List.of("LEGACY_JAVA_SERIALIZATION", "LEGACY_RESULT_UNAVAILABLE"),
                result.notices().stream().map(TxqAdapter.Notice::code).toList());
    }

    @Test
    void safeFormatRoundTripPreservesTreeIdentityResultMetadataCommentsAndEvaluation()
            throws Exception {
        GameTree tree = GameTree.create(ManualDocument.STANDARD_FEN);
        tree.updateComment(tree.root().id(), "根");
        UUID main = tree.insert(tree.root().id(), "b0c2").endNodeId();
        tree.updateComment(main, "主");
        tree.updateEvaluation(main, new GameTree.Evaluation(18, null, 12, "Pikafish"));
        tree.insert(main, "b9c7");
        tree.insert(tree.root().id(), "h0g2");
        ManualDocument source = new ManualDocument(tree, ManualDocument.Result.RED_WIN,
                Map.of("Title", "安全 TXQ", "Date", "2026-08-26"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        TxqAdapter.WriteResult written = new TxqAdapter().write(source, output);
        TxqAdapter.ReadResult decoded = new TxqAdapter().read(
                new ByteArrayInputStream(output.toByteArray()));

        assertArrayEquals(new byte[]{'P', 'D', 'T', 'X'},
                java.util.Arrays.copyOf(output.toByteArray(), 4));
        assertFalse(output.toByteArray()[0] == (byte) 0xac
                && output.toByteArray()[1] == (byte) 0xed);
        assertTrue(written.notices().isEmpty());
        assertTrue(decoded.notices().isEmpty());
        assertEquals(source.result(), decoded.document().result());
        assertEquals(source.metadata(), decoded.document().metadata());
        assertEquals(source.tree().root().id(), decoded.document().tree().root().id());
        assertEquals(source.tree().current().id(), decoded.document().tree().current().id());
        assertEquals(semanticTree(source.tree()), semanticTree(decoded.document().tree()));
    }

    @Test
    void legacyDeserializerRejectsEveryClassOutsideTheExactMigrationAllowlist()
            throws Exception {
        byte[] hostile = serialize(new HashMap<>(Map.of("payload", "not a chess manual")));

        TxqAdapter.TxqException failure = assertThrows(TxqAdapter.TxqException.class,
                () -> new TxqAdapter().read(new ByteArrayInputStream(hostile)));

        assertEquals("UNSAFE_LEGACY_CLASS", failure.code());
    }

    @Test
    void legacyDeserializerReportsObjectGraphResourceLimitSeparatelyFromClassRejection()
            throws Exception {
        ChessManual manual = legacyManual();
        manual.getHead().getList().clear();
        manual.getHead().setNext(0);
        ManualRecord current = manual.getHead();
        String[] cycle = {"b0c2", "b9c7", "c2b0", "c7b9"};
        for (int index = 0; index < 300; index++) {
            ManualRecord child = new ManualRecord(index + 1,
                    cycle[index % cycle.length], "");
            current.getList().add(child);
            current = child;
        }

        TxqAdapter.TxqException failure = assertThrows(TxqAdapter.TxqException.class,
                () -> new TxqAdapter().read(
                        new ByteArrayInputStream(serialize(manual))));

        assertEquals("LEGACY_FILTER_LIMIT", failure.code());
    }

    @Test
    void legacyDeserializerRejectsCyclesBeforeTheyReachTheGameTree() throws Exception {
        ChessManual manual = legacyManual();
        manual.getHead().getList().clear();
        manual.getHead().getList().add(manual.getHead());
        manual.getHead().setNext(0);

        TxqAdapter.TxqException failure = assertThrows(TxqAdapter.TxqException.class,
                () -> new TxqAdapter().read(new ByteArrayInputStream(serialize(manual))));

        assertEquals("LEGACY_GRAPH_ALIAS", failure.code());
    }

    @Test
    void legacyDeserializerRejectsAnInvalidMainlineIndex() throws Exception {
        ChessManual manual = legacyManual();
        manual.getHead().setNext(4);

        TxqAdapter.TxqException failure = assertThrows(TxqAdapter.TxqException.class,
                () -> new TxqAdapter().read(new ByteArrayInputStream(serialize(manual))));

        assertEquals("INVALID_LEGACY_MAINLINE", failure.code());
    }

    @Test
    void legacyModelRejectsAnOversizedChildListBeforeAllocatingMigrationState() {
        ChessManual manual = legacyManual();
        manual.getHead().setList(Collections.nCopies(50_000,
                new ManualRecord(1, "b0c2", "")));

        TxqAdapter.TxqException failure = assertThrows(TxqAdapter.TxqException.class,
                () -> new TxqAdapter().fromLegacyModel(manual));

        assertEquals("NODE_LIMIT", failure.code());
    }

    @Test
    void legacyDeserializerRejectsDuplicateSiblingMoves() throws Exception {
        ChessManual manual = legacyManual();
        ManualRecord duplicate = new ManualRecord(1, "b0c2", "");
        manual.getHead().getList().add(duplicate);

        TxqAdapter.TxqException failure = assertThrows(TxqAdapter.TxqException.class,
                () -> new TxqAdapter().read(new ByteArrayInputStream(serialize(manual))));

        assertEquals("DUPLICATE_VARIATION", failure.code());
    }

    @Test
    void invalidLegacyRootDataIsNotMisreportedAsAFenFailure() throws Exception {
        ChessManual manual = legacyManual();
        manual.getHead().setRemark("x".repeat(16_385));

        TxqAdapter.TxqException failure = assertThrows(TxqAdapter.TxqException.class,
                () -> new TxqAdapter().read(new ByteArrayInputStream(serialize(manual))));

        assertEquals("INVALID_LEGACY_NODE", failure.code());
    }

    @Test
    void legacyDeserializerRejectsTrailingBytes() throws Exception {
        byte[] serialized = serialize(legacyManual());
        byte[] source = java.util.Arrays.copyOf(serialized, serialized.length + 1);
        source[source.length - 1] = 1;

        TxqAdapter.TxqException failure = assertThrows(TxqAdapter.TxqException.class,
                () -> new TxqAdapter().read(new ByteArrayInputStream(source)));

        assertEquals("TRAILING_DATA", failure.code());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MAGIC", "VERSION", "RESULT", "METADATA_COUNT",
            "TEXT_LENGTH", "BAD_UTF8", "TREE", "TRAILING_DATA"})
    void corruptedSafeInputFailsClosedWithAStructuredOffset(String kind) throws Exception {
        ByteArrayOutputStream valid = new ByteArrayOutputStream();
        new TxqAdapter().write(new ManualDocument(GameTree.create(ManualDocument.STANDARD_FEN),
                ManualDocument.Result.DRAW, Map.of("Title", "T")), valid);
        byte[] damaged = damage(valid.toByteArray(), kind);

        TxqAdapter.TxqException failure = assertThrows(TxqAdapter.TxqException.class,
                () -> new TxqAdapter().read(new ByteArrayInputStream(damaged)));

        assertFalse(failure.code().isBlank());
        assertTrue(failure.offset() >= 0);
    }

    @Test
    void shortAndOversizedInputsAreReadWithHardBounds() {
        TxqAdapter.TxqException shortFailure = assertThrows(TxqAdapter.TxqException.class,
                () -> new TxqAdapter().read(new ByteArrayInputStream(new byte[]{'P', 'D'})));
        CountingInputStream oversized = new CountingInputStream(
                TxqAdapter.MAX_INPUT_BYTES + 100_000L);
        TxqAdapter.TxqException sizeFailure = assertThrows(TxqAdapter.TxqException.class,
                () -> new TxqAdapter().read(oversized));

        assertEquals("TRUNCATED_HEADER", shortFailure.code());
        assertEquals("SIZE_LIMIT", sizeFailure.code());
        assertEquals(TxqAdapter.MAX_INPUT_BYTES + 1L, oversized.bytesRead());
    }

    @Test
    void safeTreeRejectsMalformedUtf8InsteadOfUsingAReplacementCharacter() throws Exception {
        GameTree tree = GameTree.create(ManualDocument.STANDARD_FEN);
        tree.updateComment(tree.root().id(), "ONLY_TREE_COMMENT");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new TxqAdapter().write(new ManualDocument(tree, ManualDocument.Result.ONGOING,
                Map.of()), output);
        byte[] damaged = output.toByteArray();
        int commentOffset = indexOf(damaged,
                "ONLY_TREE_COMMENT".getBytes(StandardCharsets.UTF_8));
        assertTrue(commentOffset > 0);
        damaged[commentOffset] = (byte) 0xc3;

        TxqAdapter.TxqException failure = assertThrows(TxqAdapter.TxqException.class,
                () -> new TxqAdapter().read(new ByteArrayInputStream(damaged)));

        assertEquals("INVALID_TREE", failure.code());
    }

    @Test
    void oversizedSerializationDoesNotWriteAPartialFile() {
        GameTree tree = GameTree.create(ManualDocument.STANDARD_FEN);
        UUID current = tree.root().id();
        String comment = "x".repeat(16_384);
        String[] cycle = {"b0c2", "b9c7", "c2b0", "c7b9"};
        for (int index = 0; index < 1_024; index++) {
            current = tree.insert(current, cycle[index % cycle.length]).endNodeId();
            tree.updateComment(current, comment);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ManualDocument document = new ManualDocument(tree, ManualDocument.Result.ONGOING,
                Map.of());

        TxqAdapter.TxqException failure = assertThrows(TxqAdapter.TxqException.class,
                () -> new TxqAdapter().write(document, output));

        assertEquals("SIZE_LIMIT", failure.code());
        assertEquals(0, output.size());
    }

    @Test
    void legacyUiServiceNowWritesSafeTxqAndCanReadItBack() throws Exception {
        File file = temporaryDirectory.resolve("round-trip.txq").toFile();
        TxqChessManualImpl service = new TxqChessManualImpl();

        service.saveChessManual(legacyManual(), file);
        byte[] bytes = Files.readAllBytes(file.toPath());
        ChessManual decoded = service.openChessManual(file);

        assertArrayEquals(new byte[]{'P', 'D', 'T', 'X'},
                java.util.Arrays.copyOf(bytes, 4));
        assertNotNull(decoded);
        assertEquals("旧谱", decoded.getName());
        assertEquals("根评注", decoded.getHead().getRemark());
        assertEquals(0, decoded.getHead().getNext());
        assertEquals("h0g2", decoded.getHead().getList()
                .get(decoded.getHead().getNext()).getMove());
    }

    @Test
    void legacyUiServiceDoesNotTruncateAnExistingFileWhenValidationFails() throws Exception {
        Path path = temporaryDirectory.resolve("preserved.txq");
        byte[] original = "ORIGINAL".getBytes(StandardCharsets.US_ASCII);
        Files.write(path, original);
        ChessManual invalid = legacyManual();
        invalid.setFenCode(null);

        new TxqChessManualImpl().saveChessManual(invalid, path.toFile());

        assertArrayEquals(original, Files.readAllBytes(path));
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(List.of("preserved.txq"), files
                    .map(file -> file.getFileName().toString()).sorted().toList());
        }
    }

    private static ChessManual legacyManual() {
        ChessManual manual = new ChessManual();
        manual.setName("旧谱");
        manual.setDate("2026-08-26");
        manual.setCity("上海");
        manual.setRed("甲");
        manual.setBlack("乙");
        manual.setFenCode(ManualDocument.STANDARD_FEN);
        ManualRecord root = new ManualRecord(0, "开始局面", 0);
        root.setRemark("根评注");
        ManualRecord variation = new ManualRecord(1, "b0c2", "马八进七");
        variation.setRemark("变例");
        ManualRecord variationReply = new ManualRecord(2, "b9c7", "马2进3");
        variation.getList().add(variationReply);
        ManualRecord mainline = new ManualRecord(1, "h0g2", "马二进三");
        mainline.setRemark("主线");
        mainline.setScore(32);
        ManualRecord mainlineReply = new ManualRecord(2, "h9g7", "马8进7");
        mainline.getList().add(mainlineReply);
        root.getList().add(variation);
        root.getList().add(mainline);
        root.setNext(1);
        manual.setHead(root);
        return manual;
    }

    private static byte[] serialize(Object value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(value);
        }
        return bytes.toByteArray();
    }

    private static byte[] damage(byte[] source, String kind) {
        byte[] bytes = source.clone();
        switch (kind) {
            case "MAGIC" -> bytes[0] = 'X';
            case "VERSION" -> putInt(bytes, 4, 2);
            case "RESULT" -> putInt(bytes, 8, 4);
            case "METADATA_COUNT" -> putInt(bytes, 12, 65);
            case "TEXT_LENGTH" -> putInt(bytes, 16, Integer.MAX_VALUE);
            case "BAD_UTF8" -> bytes[20] = (byte) 0xc3;
            case "TREE" -> bytes[30] = 'X';
            case "TRAILING_DATA" -> {
                bytes = java.util.Arrays.copyOf(bytes, bytes.length + 1);
                bytes[bytes.length - 1] = 1;
            }
            default -> throw new IllegalArgumentException(kind);
        }
        return bytes;
    }

    private static void putInt(byte[] bytes, int offset, int value) {
        for (int index = 0; index < 4; index++) {
            bytes[offset + index] = (byte) (value >>> (24 - index * 8));
        }
    }

    private static int indexOf(byte[] source, byte[] target) {
        outer:
        for (int offset = 0; offset <= source.length - target.length; offset++) {
            for (int index = 0; index < target.length; index++) {
                if (source[offset + index] != target[index]) continue outer;
            }
            return offset;
        }
        return -1;
    }

    private static List<String> semanticTree(GameTree tree) {
        List<String> result = new ArrayList<>();
        ArrayDeque<GameTree.Node> pending = new ArrayDeque<>();
        pending.add(tree.root());
        while (!pending.isEmpty()) {
            GameTree.Node node = pending.removeFirst();
            result.add(node.id() + "|" + node.move() + "|" + node.comment() + "|"
                    + node.evaluation() + "|" + node.children());
            pending.addAll(tree.children(node.id()));
        }
        return result;
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
