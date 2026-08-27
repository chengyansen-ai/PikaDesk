package com.sojourners.chess.game.tree;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.Enumeration;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GameTreeTest {

    private static final String STANDARD_FEN =
            "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w";

    @Test
    void insertsMainlineAndVariationWithStableImmutableNodes() {
        GameTree tree = GameTree.create(STANDARD_FEN);
        UUID root = tree.root().id();

        GameTree.MergeResult first = tree.insert(root, "b0c2");
        GameTree.MergeResult reply = tree.insert(first.endNodeId(), "b9c7");
        GameTree.MergeResult variation = tree.insert(root, "h0g2");

        assertEquals(4, tree.size());
        assertEquals(List.of(first.endNodeId(), variation.endNodeId()),
                tree.root().children());
        assertEquals(first.endNodeId(), tree.root().mainlineChildId().orElseThrow());
        assertEquals("b", tree.node(first.endNodeId()).positionFen().split(" ")[1]);
        assertEquals(variation.endNodeId(), tree.current().id());
        assertThrows(UnsupportedOperationException.class,
                () -> tree.root().children().add(UUID.randomUUID()));
        assertNotEquals(first.endNodeId(), variation.endNodeId());
    }

    @Test
    void rejectsWrongSideMalformedAndSelfCheckingMovesWithoutMutation() {
        GameTree tree = GameTree.create(STANDARD_FEN);
        UUID root = tree.root().id();

        assertThrows(IllegalArgumentException.class, () -> tree.insert(root, "a9a8"));
        assertThrows(IllegalArgumentException.class, () -> tree.insert(root, "b0b2"));
        assertThrows(IllegalArgumentException.class, () -> tree.insert(root, "b0c20"));
        assertEquals(1, tree.size());
        assertThrows(IllegalArgumentException.class, () -> GameTree.create(
                "4k4/4R4/9/9/9/9/9/9/9/4K4 w"));

        GameTree checked = GameTree.create(
                "4k4/9/9/9/9/9/9/9/4r4/R3K4 w");
        assertThrows(IllegalArgumentException.class,
                () -> checked.insert(checked.root().id(), "a0a1"));
        assertEquals(1, checked.size());
    }

    @Test
    void promotesJumpsAndDeletesWholeVariation() {
        GameTree tree = GameTree.create(STANDARD_FEN);
        UUID root = tree.root().id();
        UUID first = tree.insert(root, "b0c2").endNodeId();
        UUID reply = tree.insert(first, "b9c7").endNodeId();
        UUID variation = tree.insert(root, "h0g2").endNodeId();

        tree.promoteToMainline(variation);
        assertEquals(variation, tree.root().mainlineChildId().orElseThrow());
        assertEquals(variation, tree.root().children().getFirst());

        tree.jumpTo(reply);
        assertEquals(reply, tree.current().id());
        assertEquals(2, tree.deleteSubtree(first));
        assertEquals(root, tree.current().id());
        assertFalse(tree.contains(first));
        assertFalse(tree.contains(reply));
        assertEquals(List.of(variation), tree.root().children());
        assertThrows(IllegalArgumentException.class, () -> tree.deleteSubtree(root));
    }

    @Test
    void mergesSharedPrefixAndRejectsInvalidLineAtomically() {
        GameTree tree = GameTree.create(STANDARD_FEN);
        UUID root = tree.root().id();
        GameTree.MergeResult prefix = tree.mergeLine(
                root, List.of("b0c2", "b9c7"));
        GameTree.MergeResult merged = tree.mergeLine(
                root, List.of("b0c2", "b9c7", "h0g2"));

        assertEquals(2, prefix.createdNodes());
        assertEquals(0, prefix.reusedNodes());
        assertEquals(1, merged.createdNodes());
        assertEquals(2, merged.reusedNodes());
        assertEquals(4, tree.size());

        int before = tree.size();
        assertThrows(IllegalArgumentException.class, () -> tree.mergeLine(
                root, List.of("h0g2", "h9g7", "h0g2")));
        assertEquals(before, tree.size());
    }

    @Test
    void preservesIdentityCommentsEvaluationAndMainlineAcrossRoundTrip() throws IOException {
        GameTree tree = GameTree.create(STANDARD_FEN);
        UUID root = tree.root().id();
        UUID first = tree.insert(root, "b0c2").endNodeId();
        UUID variation = tree.insert(root, "h0g2").endNodeId();
        tree.promoteToMainline(variation);
        tree.updateComment(first, "先活马\n保持出子速度");
        tree.updateEvaluation(first,
                new GameTree.Evaluation(34, null, 12, "Pikafish 2026-01-02"));
        tree.updateEvaluation(variation,
                new GameTree.Evaluation(null, 5, 16, "Pikafish 2026-01-02"));
        tree.jumpTo(first);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        GameTreeCodec.write(tree, output);
        GameTree restored = GameTreeCodec.read(
                new ByteArrayInputStream(output.toByteArray()));

        assertEquals(tree.initialFen(), restored.initialFen());
        assertEquals(tree.root().id(), restored.root().id());
        assertEquals(tree.current().id(), restored.current().id());
        assertEquals(tree.snapshot(), restored.snapshot());
        assertEquals("先活马\n保持出子速度", restored.node(first).comment());
        assertEquals(34, restored.node(first).evaluation()
                .orElseThrow().centipawns());
        assertEquals(variation,
                restored.root().mainlineChildId().orElseThrow());
    }

    @Test
    void rejectsCorruptOrTruncatedNativeFiles() throws IOException {
        GameTree tree = GameTree.create(STANDARD_FEN);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        GameTreeCodec.write(tree, output);
        byte[] valid = output.toByteArray();
        byte[] truncated = java.util.Arrays.copyOf(valid, valid.length - 3);
        valid[0] ^= 0x5a;

        assertThrows(IOException.class,
                () -> GameTreeCodec.read(new ByteArrayInputStream(valid)));
        assertThrows(IOException.class,
                () -> GameTreeCodec.read(new ByteArrayInputStream(truncated)));
    }

    @Test
    void rejectsNativeFilesOverAggregateLimitWithoutBufferingThem() throws IOException {
        IOException failure = assertThrows(IOException.class,
                () -> GameTreeCodec.read(oversizedNativeTree()));

        assertTrue(failure.getMessage().contains("persistence size limit"));
    }

    @Test
    void duplicateInsertionReusesExistingNodeIdentity() {
        GameTree tree = GameTree.create(STANDARD_FEN);
        UUID root = tree.root().id();
        GameTree.MergeResult first = tree.insert(root, "b0c2");
        GameTree.MergeResult duplicate = tree.insert(root, "b0c2");

        assertEquals(first.endNodeId(), duplicate.endNodeId());
        assertEquals(0, duplicate.createdNodes());
        assertEquals(1, duplicate.reusedNodes());
        assertEquals(2, tree.size());
        assertTrue(tree.current().id().equals(first.endNodeId()));
    }

    private static InputStream oversizedNativeTree() throws IOException {
        ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
        try (DataOutputStream header = new DataOutputStream(headerBytes)) {
            header.writeInt(0x50445452);
            header.writeInt(1);
            writeString(header, STANDARD_FEN);
            writeUuid(header, new UUID(0, 1));
            writeUuid(header, new UUID(0, 1));
            header.writeInt(100_000);
        }
        Enumeration<InputStream> parts = new Enumeration<>() {
            private int index;

            @Override
            public boolean hasMoreElements() {
                return index <= 100_000;
            }

            @Override
            public InputStream nextElement() {
                if (!hasMoreElements()) throw new NoSuchElementException();
                if (index++ == 0) return new ByteArrayInputStream(headerBytes.toByteArray());
                try {
                    return oversizedNode(index);
                } catch (IOException impossible) {
                    throw new IllegalStateException(impossible);
                }
            }
        };
        return new SequenceInputStream(parts);
    }

    private static InputStream oversizedNode(int index) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(16_448);
        try (DataOutputStream node = new DataOutputStream(bytes)) {
            writeUuid(node, new UUID(0, index));
            node.writeBoolean(false);
            writeString(node, "");
            writeString(node, STANDARD_FEN);
            node.writeInt(16_384);
            node.write(new byte[16_384]);
            node.writeBoolean(false);
            node.writeInt(0);
            node.writeBoolean(false);
        }
        return new ByteArrayInputStream(bytes.toByteArray());
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static void writeUuid(DataOutputStream output, UUID id) throws IOException {
        output.writeLong(id.getMostSignificantBits());
        output.writeLong(id.getLeastSignificantBits());
    }
}
