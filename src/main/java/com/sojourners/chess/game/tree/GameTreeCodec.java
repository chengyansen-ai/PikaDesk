package com.sojourners.chess.game.tree;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Versioned, bounded native persistence for {@link GameTree}. */
public final class GameTreeCodec {

    private static final int MAGIC = 0x50445452; // PDTR
    private static final int VERSION = 1;
    private static final int MAX_STRING_BYTES = 65_536;
    private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;

    private GameTreeCodec() {
    }

    public static void write(GameTree tree, OutputStream output) throws IOException {
        Objects.requireNonNull(tree, "tree");
        DataOutputStream data = new DataOutputStream(new BoundedOutputStream(
                Objects.requireNonNull(output, "output"), MAX_TOTAL_BYTES));
        data.writeInt(MAGIC);
        data.writeInt(VERSION);
        writeString(data, tree.initialFen());
        writeUuid(data, tree.root().id());
        writeUuid(data, tree.current().id());
        List<GameTree.Node> nodes = tree.snapshot().values().stream()
                .sorted(Comparator.comparing(node -> node.id().toString()))
                .toList();
        data.writeInt(nodes.size());
        for (GameTree.Node node : nodes) writeNode(data, node);
        data.flush();
    }

    public static GameTree read(InputStream input) throws IOException {
        DataInputStream data = new DataInputStream(new BoundedInputStream(
                Objects.requireNonNull(input, "input"), MAX_TOTAL_BYTES));
        try {
            if (data.readInt() != MAGIC) throw new IOException("not a PikaDesk game tree");
            int version = data.readInt();
            if (version != VERSION) throw new IOException("unsupported game tree version: " + version);
            String initialFen = readString(data);
            UUID rootId = readUuid(data);
            UUID currentId = readUuid(data);
            int count = data.readInt();
            if (count < 1 || count > GameTree.MAX_NODES) {
                throw new IOException("game tree node count exceeds limit");
            }
            Map<UUID, GameTree.Node> nodes = new LinkedHashMap<>();
            long childReferences = 0;
            for (int index = 0; index < count; index++) {
                GameTree.Node node = readNode(data);
                childReferences += node.children().size();
                if (childReferences > count - 1L) {
                    throw new IOException("game tree contains too many child references");
                }
                if (nodes.put(node.id(), node) != null) {
                    throw new IOException("game tree contains a duplicate node identity");
                }
            }
            if (data.read() != -1) throw new IOException("game tree contains trailing data");
            try {
                return GameTree.restore(initialFen, rootId, currentId, nodes);
            } catch (RuntimeException invalid) {
                throw new IOException("invalid game tree: " + message(invalid), invalid);
            }
        } catch (EOFException truncated) {
            throw new IOException("truncated game tree", truncated);
        } catch (RuntimeException invalid) {
            throw new IOException("invalid game tree: " + message(invalid), invalid);
        }
    }

    private static void writeNode(DataOutputStream data, GameTree.Node node) throws IOException {
        writeUuid(data, node.id());
        writeOptionalUuid(data, node.parentId());
        writeString(data, node.move());
        writeString(data, node.positionFen());
        writeString(data, node.comment());
        data.writeBoolean(node.evaluation().isPresent());
        if (node.evaluation().isPresent()) {
            GameTree.Evaluation evaluation = node.evaluation().orElseThrow();
            data.writeBoolean(evaluation.centipawns() != null);
            if (evaluation.centipawns() != null) data.writeInt(evaluation.centipawns());
            data.writeBoolean(evaluation.mateIn() != null);
            if (evaluation.mateIn() != null) data.writeInt(evaluation.mateIn());
            data.writeInt(evaluation.depth());
            writeString(data, evaluation.engine());
        }
        data.writeInt(node.children().size());
        for (UUID child : node.children()) writeUuid(data, child);
        writeOptionalUuid(data, node.mainlineChildId());
    }

    private static GameTree.Node readNode(DataInputStream data) throws IOException {
        UUID id = readUuid(data);
        Optional<UUID> parentId = readOptionalUuid(data);
        String move = readString(data);
        String fen = readString(data);
        String comment = readString(data);
        Optional<GameTree.Evaluation> evaluation = Optional.empty();
        if (data.readBoolean()) {
            Integer centipawns = data.readBoolean() ? data.readInt() : null;
            Integer mateIn = data.readBoolean() ? data.readInt() : null;
            int depth = data.readInt();
            String engine = readString(data);
            evaluation = Optional.of(new GameTree.Evaluation(
                    centipawns, mateIn, depth, engine));
        }
        int childCount = data.readInt();
        if (childCount < 0 || childCount > GameTree.MAX_NODES) {
            throw new IOException("node child count exceeds limit");
        }
        List<UUID> children = new ArrayList<>(childCount);
        for (int index = 0; index < childCount; index++) children.add(readUuid(data));
        Optional<UUID> mainline = readOptionalUuid(data);
        return new GameTree.Node(
                id, parentId, move, fen, children, mainline, comment, evaluation);
    }

    private static void writeString(DataOutputStream data, String value) throws IOException {
        byte[] bytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) throw new IOException("string exceeds persistence limit");
        data.writeInt(bytes.length);
        data.write(bytes);
    }

    private static String readString(DataInputStream data) throws IOException {
        int length = data.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("string length exceeds persistence limit");
        }
        byte[] bytes = new byte[length];
        data.readFully(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IOException("game tree contains invalid UTF-8 text", invalid);
        }
    }

    private static void writeUuid(DataOutputStream data, UUID id) throws IOException {
        data.writeLong(id.getMostSignificantBits());
        data.writeLong(id.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream data) throws IOException {
        return new UUID(data.readLong(), data.readLong());
    }

    private static void writeOptionalUuid(DataOutputStream data, Optional<UUID> id)
            throws IOException {
        data.writeBoolean(id.isPresent());
        if (id.isPresent()) writeUuid(data, id.orElseThrow());
    }

    private static Optional<UUID> readOptionalUuid(DataInputStream data) throws IOException {
        return data.readBoolean() ? Optional.of(readUuid(data)) : Optional.empty();
    }

    private static String message(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message.trim();
    }

    private static final class BoundedInputStream extends InputStream {

        private final InputStream delegate;
        private long remaining;

        private BoundedInputStream(InputStream delegate, long limit) {
            this.delegate = delegate;
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining == 0) return endOrLimitExceeded();
            int value = delegate.read();
            if (value >= 0) remaining--;
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) return 0;
            if (remaining == 0) return endOrLimitExceeded();
            int allowed = (int) Math.min(length, remaining);
            int count = delegate.read(bytes, offset, allowed);
            if (count > 0) remaining -= count;
            return count;
        }

        private int endOrLimitExceeded() throws IOException {
            if (delegate.read() == -1) return -1;
            throw new IOException("game tree exceeds persistence size limit");
        }
    }

    private static final class BoundedOutputStream extends OutputStream {

        private final OutputStream delegate;
        private long remaining;

        private BoundedOutputStream(OutputStream delegate, long limit) {
            this.delegate = delegate;
            this.remaining = limit;
        }

        @Override
        public void write(int value) throws IOException {
            requireRemaining(1);
            delegate.write(value);
            remaining--;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            requireRemaining(length);
            delegate.write(bytes, offset, length);
            remaining -= length;
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        private void requireRemaining(int requested) throws IOException {
            if (requested > remaining) {
                throw new IOException("game tree exceeds persistence size limit");
            }
        }
    }
}
