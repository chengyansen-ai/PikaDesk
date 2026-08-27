package com.sojourners.chess.manual.adapter;

import com.sojourners.chess.game.tree.GameTree;
import com.sojourners.chess.game.tree.GameTreeCodec;
import com.sojourners.chess.manual.ChessManual;
import com.sojourners.chess.model.ManualRecord;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.io.OutputStream;
import java.io.StreamCorruptedException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Safe TXQ persistence plus a tightly filtered, one-way migration path for the
 * historical TCHESS Java-serialization format.
 */
public final class TxqAdapter {

    public static final int MAX_INPUT_BYTES = 16 * 1024 * 1024;
    public static final int MAX_OUTPUT_BYTES = 16 * 1024 * 1024;
    private static final int MAGIC = 0x50445458; // PDTX
    private static final int VERSION = 1;
    private static final int HEADER_BYTES = 16;
    private static final int MAX_NODES = 50_000;
    private static final int MAX_GRAPH_DEPTH = 256;
    private static final int MAX_REFERENCES = 100_000;
    private static final int MAX_ARRAY_LENGTH = 50_000;
    private static final int MAX_METADATA = 64;
    private static final int MAX_KEY_BYTES = 128;
    private static final int MAX_VALUE_BYTES = 4_096;
    private static final int MAX_NOTICES = 16;
    private static final byte[] LEGACY_MAGIC = {(byte) 0xac, (byte) 0xed, 0, 5};
    private static final Set<String> LEGACY_CLASS_NAMES = Set.of(
            ChessManual.class.getName(), ManualRecord.class.getName(),
            ArrayList.class.getName(), Integer.class.getName(), Number.class.getName(),
            "[Ljava.lang.Object;", "[L" + ManualRecord.class.getName() + ";");

    public ReadResult read(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        byte[] bytes = input.readNBytes(MAX_INPUT_BYTES + 1);
        if (bytes.length > MAX_INPUT_BYTES) {
            throw error("SIZE_LIMIT", MAX_INPUT_BYTES,
                    "TXQ exceeds " + MAX_INPUT_BYTES + " bytes");
        }
        if (bytes.length < 4) {
            throw error("TRUNCATED_HEADER", bytes.length, "TXQ header is truncated");
        }
        if (bigEndian(bytes, 0) == MAGIC) return readSafe(bytes);
        if (matches(bytes, LEGACY_MAGIC)) return readLegacy(bytes);
        throw error("INVALID_MAGIC", 0, "not a supported PikaDesk or legacy TCHESS TXQ");
    }

    public WriteResult write(ManualDocument document, OutputStream output) throws IOException {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(output, "output");
        if (document.tree().size() > MAX_NODES) {
            throw error("NODE_LIMIT", 0, "TXQ node limit exceeded");
        }

        BoundedBuffer encoded = new BoundedBuffer(MAX_OUTPUT_BYTES);
        encoded.writeInt(MAGIC);
        encoded.writeInt(VERSION);
        encoded.writeInt(resultCode(document.result()));
        encoded.writeInt(document.metadata().size());
        for (Map.Entry<String, String> entry : document.metadata().entrySet()) {
            writeText(encoded, entry.getKey(), MAX_KEY_BYTES, "metadata key");
            writeText(encoded, entry.getValue(), MAX_VALUE_BYTES, "metadata value");
        }
        GameTreeCodec.write(document.tree(), encoded);
        output.write(encoded.toByteArray());
        output.flush();
        return new WriteResult(List.of());
    }

    /** Converts the historical in-memory model after applying the same migration checks. */
    public ManualDocument fromLegacyModel(ChessManual legacy) throws TxqException {
        Objects.requireNonNull(legacy, "legacy");
        String fen = legacy.getFenCode();
        if (fen == null || fen.isBlank()) {
            throw error("INVALID_LEGACY_FEN", 0, "legacy TXQ has no initial position");
        }
        ManualRecord root = legacy.getHead();
        if (root == null) {
            throw error("INVALID_LEGACY_ROOT", 0, "legacy TXQ has no root record");
        }
        if (root.getMove() != null && !root.getMove().isBlank()) {
            throw error("INVALID_LEGACY_ROOT", 0, "legacy TXQ root must not contain a move");
        }

        GameTree tree;
        try {
            tree = GameTree.create(fen);
            copyLegacyNodeData(root, tree, tree.root().id());
        } catch (IllegalArgumentException invalid) {
            throw error("INVALID_LEGACY_FEN", 0, message(invalid));
        }

        IdentityHashMap<ManualRecord, Boolean> seen = new IdentityHashMap<>();
        seen.put(root, Boolean.TRUE);
        ArrayDeque<MigrationTask> pending = new ArrayDeque<>();
        pending.add(new MigrationTask(root, tree.root().id(), 0));
        int nodes = 1;
        while (!pending.isEmpty()) {
            MigrationTask task = pending.removeFirst();
            List<ManualRecord> children = task.legacy().getList();
            if (children == null) {
                throw error("INVALID_LEGACY_CHILDREN", 0,
                        "legacy TXQ record has a null child list");
            }
            if (children.size() > MAX_NODES - nodes) {
                throw error("NODE_LIMIT", 0, "legacy TXQ node limit exceeded");
            }
            int mainline = task.legacy().getNext();
            if (children.isEmpty()) {
                if (mainline != 0) {
                    throw error("INVALID_LEGACY_MAINLINE", 0,
                            "leaf record has a nonzero mainline index");
                }
                continue;
            }
            if (mainline < 0 || mainline >= children.size()) {
                throw error("INVALID_LEGACY_MAINLINE", 0,
                        "legacy mainline index is outside the child list");
            }
            if (task.depth() >= MAX_GRAPH_DEPTH) {
                throw error("DEPTH_LIMIT", 0, "legacy TXQ graph depth exceeded");
            }

            List<UUID> inserted = new ArrayList<>(children.size());
            for (ManualRecord child : children) {
                if (child == null) {
                    throw error("INVALID_LEGACY_CHILDREN", 0,
                            "legacy TXQ contains a null child record");
                }
                if (seen.put(child, Boolean.TRUE) != null) {
                    throw error("LEGACY_GRAPH_ALIAS", 0,
                            "legacy TXQ contains a cycle or shared child object");
                }
                if (++nodes > MAX_NODES) {
                    throw error("NODE_LIMIT", 0, "legacy TXQ node limit exceeded");
                }
                String move = child.getMove();
                if (move == null || move.length() != 4) {
                    throw error("INVALID_LEGACY_MOVE", 0,
                            "legacy TXQ child has no four-character UCCI move");
                }
                GameTree.MergeResult result;
                try {
                    result = tree.insert(task.parentId(), move);
                    if (result.createdNodes() == 0) {
                        throw error("DUPLICATE_VARIATION", 0,
                                "legacy TXQ contains duplicate sibling moves");
                    }
                    copyLegacyNodeData(child, tree, result.endNodeId());
                } catch (TxqException failure) {
                    throw failure;
                } catch (IllegalArgumentException | IllegalStateException invalid) {
                    throw error("INVALID_LEGACY_MOVE", 0, message(invalid));
                }
                inserted.add(result.endNodeId());
                pending.addLast(new MigrationTask(
                        child, result.endNodeId(), task.depth() + 1));
            }
            if (mainline != 0) tree.promoteToMainline(inserted.get(mainline));
        }

        Map<String, String> metadata = new LinkedHashMap<>();
        addLegacyMetadata(metadata, "Title", legacy.getName());
        addLegacyMetadata(metadata, "Date", legacy.getDate());
        addLegacyMetadata(metadata, "Site", legacy.getCity());
        addLegacyMetadata(metadata, "Red", legacy.getRed());
        addLegacyMetadata(metadata, "Black", legacy.getBlack());
        try {
            return new ManualDocument(tree, ManualDocument.Result.ONGOING, metadata);
        } catch (IllegalArgumentException invalid) {
            throw error("INVALID_LEGACY_METADATA", 0, message(invalid));
        }
    }

    /** Rebuilds the legacy UI model without using Java serialization. */
    public ChessManual toLegacyModel(ManualDocument document) {
        Objects.requireNonNull(document, "document");
        ChessManual legacy = new ChessManual();
        legacy.setFenCode(document.tree().initialFen());
        legacy.setName(document.metadata().getOrDefault("Title", ""));
        legacy.setDate(document.metadata().getOrDefault("Date", ""));
        legacy.setCity(document.metadata().getOrDefault("Site", ""));
        legacy.setRed(document.metadata().getOrDefault("Red", ""));
        legacy.setBlack(document.metadata().getOrDefault("Black", ""));

        ManualRecord root = new ManualRecord(0, "开始局面",
                legacyScore(document.tree().root()));
        root.setRemark(document.tree().root().comment());
        legacy.setHead(root);
        ArrayDeque<LegacyOutputTask> pending = new ArrayDeque<>();
        pending.add(new LegacyOutputTask(document.tree().root(), root, 0));
        while (!pending.isEmpty()) {
            LegacyOutputTask task = pending.removeFirst();
            List<GameTree.Node> children = orderedChildren(document.tree(), task.node());
            for (GameTree.Node child : children) {
                ManualRecord record = new ManualRecord(task.depth() + 1, child.move(), "");
                record.setRemark(child.comment());
                record.setScore(legacyScore(child));
                task.record().getList().add(record);
                pending.addLast(new LegacyOutputTask(child, record, task.depth() + 1));
            }
            task.record().setNext(0);
        }
        return legacy;
    }

    private static ReadResult readSafe(byte[] bytes) throws TxqException {
        if (bytes.length < HEADER_BYTES) {
            throw error("TRUNCATED_HEADER", bytes.length, "PDTX header is truncated");
        }
        Cursor input = new Cursor(bytes);
        input.readInt("magic");
        int version = input.readInt("version");
        if (version != VERSION) {
            throw error("UNSUPPORTED_VERSION", 4,
                    "unsupported PDTX version " + version);
        }
        ManualDocument.Result result = readResult(input.readInt("result"));
        int metadataCount = input.readInt("metadata count");
        if (metadataCount < 0 || metadataCount > MAX_METADATA) {
            throw error("METADATA_LIMIT", 12, "PDTX metadata count exceeds its limit");
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        for (int index = 0; index < metadataCount; index++) {
            int keyOffset = input.position();
            String key = input.readText(MAX_KEY_BYTES, "metadata key");
            String value = input.readText(MAX_VALUE_BYTES, "metadata value");
            if (metadata.put(key, value) != null) {
                throw error("DUPLICATE_METADATA", keyOffset,
                        "PDTX contains duplicate metadata key " + key);
            }
        }
        int treeOffset = input.position();
        if (treeOffset == bytes.length) {
            throw error("TRUNCATED_TREE", treeOffset, "PDTX has no game tree");
        }
        GameTree tree;
        try {
            tree = GameTreeCodec.read(new ByteArrayInputStream(
                    bytes, treeOffset, bytes.length - treeOffset));
        } catch (IOException | RuntimeException invalid) {
            throw error("INVALID_TREE", treeOffset, message(invalid));
        }
        if (tree.size() > MAX_NODES) {
            throw error("NODE_LIMIT", treeOffset, "PDTX node limit exceeded");
        }
        try {
            return new ReadResult(new ManualDocument(tree, result, metadata), List.of());
        } catch (IllegalArgumentException invalid) {
            throw error("INVALID_DOCUMENT", 8, message(invalid));
        }
    }

    private static ReadResult readLegacy(byte[] bytes) throws TxqException {
        ByteArrayInputStream source = new ByteArrayInputStream(bytes);
        try (RestrictedObjectInputStream objects = new RestrictedObjectInputStream(source)) {
            objects.setObjectInputFilter(TxqAdapter::filterLegacyObject);
            Object value = objects.readObject();
            if (!(value instanceof ChessManual legacy)) {
                throw error("INVALID_LEGACY_ROOT", 4,
                        "legacy TXQ root is not a ChessManual");
            }
            if (source.available() != 0) {
                throw error("TRAILING_DATA", bytes.length - source.available(),
                        "data follows the legacy TXQ object graph");
            }
            ManualDocument document = new TxqAdapter().fromLegacyModel(legacy);
            return new ReadResult(document, List.of(
                    new Notice("LEGACY_JAVA_SERIALIZATION",
                            "legacy Java serialization was migrated through an exact class allowlist", 0),
                    new Notice("LEGACY_RESULT_UNAVAILABLE",
                            "legacy TXQ has no result field; result was set to ongoing", 0)));
        } catch (TxqException failure) {
            throw failure;
        } catch (RejectedLegacyClassException | ClassNotFoundException unsafe) {
            throw error("UNSAFE_LEGACY_CLASS", 4,
                    "legacy TXQ requested a class outside the migration allowlist");
        } catch (InvalidClassException filtered) {
            throw error("LEGACY_FILTER_LIMIT", 4,
                    "legacy TXQ exceeded the object graph filter or has an incompatible serialized class shape");
        } catch (EOFException truncated) {
            throw error("TRUNCATED_LEGACY_STREAM", bytes.length,
                    "legacy TXQ object stream is truncated");
        } catch (StreamCorruptedException invalid) {
            throw error("INVALID_LEGACY_STREAM", 4, message(invalid));
        } catch (IOException | RuntimeException invalid) {
            throw error("INVALID_LEGACY_STREAM", 4, message(invalid));
        }
    }

    private static ObjectInputFilter.Status filterLegacyObject(ObjectInputFilter.FilterInfo info) {
        // Java 21 serialization-filter guidance:
        // https://docs.oracle.com/en/java/javase/21/core/java-serialization-filters.html
        if (info.depth() > MAX_GRAPH_DEPTH || info.references() > MAX_REFERENCES
                || info.streamBytes() > MAX_INPUT_BYTES
                || info.arrayLength() > MAX_ARRAY_LENGTH) {
            return ObjectInputFilter.Status.REJECTED;
        }
        // Class authorization is handled in resolveClass/resolveProxyClass so
        // allowlist violations remain distinguishable from resource rejection.
        return ObjectInputFilter.Status.UNDECIDED;
    }

    private static void copyLegacyNodeData(ManualRecord source, GameTree tree, UUID nodeId)
            throws TxqException {
        String comment = source.getRemark() == null ? "" : source.getRemark();
        try {
            tree.updateComment(nodeId, comment);
            if (source.getScore() != null) {
                tree.updateEvaluation(nodeId, new GameTree.Evaluation(
                        source.getScore(), null, 1, "Legacy TXQ"));
            }
        } catch (IllegalArgumentException invalid) {
            throw error("INVALID_LEGACY_NODE", 0, message(invalid));
        }
    }

    private static void addLegacyMetadata(Map<String, String> metadata,
                                          String name,
                                          String value) {
        if (value != null && !value.isEmpty()) metadata.put(name, value);
    }

    private static List<GameTree.Node> orderedChildren(GameTree tree, GameTree.Node parent) {
        if (parent.children().isEmpty()) return List.of();
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        parent.mainlineChildId().ifPresent(ids::add);
        ids.addAll(parent.children());
        return ids.stream().map(tree::node).toList();
    }

    private static Integer legacyScore(GameTree.Node node) {
        if (node.evaluation().isEmpty()) return null;
        GameTree.Evaluation evaluation = node.evaluation().orElseThrow();
        if (evaluation.centipawns() != null) return evaluation.centipawns();
        int mate = evaluation.mateIn();
        return (mate < 0 ? -30_000 : 30_000) - mate;
    }

    private static ManualDocument.Result readResult(int value) throws TxqException {
        return switch (value) {
            case 0 -> ManualDocument.Result.ONGOING;
            case 1 -> ManualDocument.Result.RED_WIN;
            case 2 -> ManualDocument.Result.BLACK_WIN;
            case 3 -> ManualDocument.Result.DRAW;
            default -> throw error("INVALID_RESULT", 8, "invalid PDTX result code");
        };
    }

    private static int resultCode(ManualDocument.Result result) {
        return switch (result) {
            case ONGOING -> 0;
            case RED_WIN -> 1;
            case BLACK_WIN -> 2;
            case DRAW -> 3;
        };
    }

    private static void writeText(BoundedBuffer output,
                                  String value,
                                  int limit,
                                  String label) throws TxqException {
        byte[] encoded = encodeUtf8(value, limit, output.size(), label);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static byte[] encodeUtf8(String value, int limit, int offset, String label)
            throws TxqException {
        try {
            ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(Objects.requireNonNull(value, label)));
            byte[] encoded = new byte[buffer.remaining()];
            buffer.get(encoded);
            if (encoded.length > limit) {
                throw error("TEXT_LIMIT", offset, label + " exceeds its byte limit");
            }
            return encoded;
        } catch (CharacterCodingException invalid) {
            throw error("INVALID_TEXT", offset, label + " is not valid Unicode text");
        }
    }

    private static String decodeUtf8(byte[] bytes, int offset, int length, String label)
            throws TxqException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, length)).toString();
        } catch (CharacterCodingException invalid) {
            throw error("INVALID_TEXT", offset, "invalid UTF-8 " + label);
        }
    }

    private static boolean matches(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (bytes[index] != prefix[index]) return false;
        }
        return true;
    }

    private static int bigEndian(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) << 24
                | (bytes[offset + 1] & 0xff) << 16
                | (bytes[offset + 2] & 0xff) << 8
                | bytes[offset + 3] & 0xff;
    }

    private static String message(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message.trim();
    }

    private static TxqException error(String code, int offset, String message) {
        return new TxqException(code, Math.max(0, offset), message);
    }

    public record Notice(String code, String message, int offset) {
        public Notice {
            code = Objects.requireNonNull(code, "code");
            message = Objects.requireNonNull(message, "message");
            if (code.isBlank() || message.isBlank() || offset < 0) {
                throw new IllegalArgumentException("invalid TXQ notice");
            }
        }
    }

    public record ReadResult(ManualDocument document, List<Notice> notices) {
        public ReadResult {
            Objects.requireNonNull(document, "document");
            notices = List.copyOf(Objects.requireNonNull(notices, "notices"));
            if (notices.size() > MAX_NOTICES) {
                throw new IllegalArgumentException("TXQ notice limit exceeded");
            }
        }
    }

    public record WriteResult(List<Notice> notices) {
        public WriteResult {
            notices = List.copyOf(Objects.requireNonNull(notices, "notices"));
            if (notices.size() > MAX_NOTICES) {
                throw new IllegalArgumentException("TXQ notice limit exceeded");
            }
        }
    }

    public static final class TxqException extends IOException {
        private final String code;
        private final int offset;

        private TxqException(String code, int offset, String message) {
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

    private record MigrationTask(ManualRecord legacy, UUID parentId, int depth) { }
    private record LegacyOutputTask(GameTree.Node node, ManualRecord record, int depth) { }

    private static final class RestrictedObjectInputStream extends ObjectInputStream {
        private RestrictedObjectInputStream(InputStream input) throws IOException {
            super(input);
        }

        @Override
        protected Class<?> resolveClass(ObjectStreamClass descriptor)
                throws IOException, ClassNotFoundException {
            if (!LEGACY_CLASS_NAMES.contains(descriptor.getName())) {
                throw new RejectedLegacyClassException(
                        descriptor.getName(), "legacy TXQ class is not allowed");
            }
            return super.resolveClass(descriptor);
        }

        @Override
        protected Class<?> resolveProxyClass(String[] interfaces) throws InvalidClassException {
            throw new RejectedLegacyClassException(
                    "legacy TXQ proxy classes are not allowed");
        }
    }

    private static final class RejectedLegacyClassException extends InvalidClassException {
        private RejectedLegacyClassException(String reason) {
            super(reason);
        }

        private RejectedLegacyClassException(String className, String reason) {
            super(className, reason);
        }
    }

    private static final class Cursor {
        private final byte[] bytes;
        private int position;

        private Cursor(byte[] bytes) {
            this.bytes = bytes;
        }

        private int position() {
            return position;
        }

        private int readInt(String label) throws TxqException {
            require(4, label);
            int value = bigEndian(bytes, position);
            position += 4;
            return value;
        }

        private String readText(int limit, String label) throws TxqException {
            int lengthOffset = position;
            int length = readInt(label + " length");
            if (length < 0 || length > limit) {
                throw error("TEXT_LIMIT", lengthOffset, label + " length exceeds its limit");
            }
            require(length, label);
            String value = decodeUtf8(bytes, position, length, label);
            position += length;
            return value;
        }

        private void require(int length, String label) throws TxqException {
            if (length < 0 || (long) position + length > bytes.length) {
                throw error("TRUNCATED_DATA", position, "truncated " + label);
            }
        }
    }

    private static final class BoundedBuffer extends OutputStream {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(4_096);

        private BoundedBuffer(int limit) {
            this.limit = limit;
        }

        private int size() {
            return bytes.size();
        }

        @Override
        public void write(int value) throws TxqException {
            require(1);
            bytes.write(value);
        }

        @Override
        public void write(byte[] value, int offset, int length) throws TxqException {
            Objects.checkFromIndexSize(offset, length, value.length);
            require(length);
            bytes.write(value, offset, length);
        }

        @Override
        public void write(byte[] value) throws TxqException {
            write(value, 0, value.length);
        }

        private void writeInt(int value) throws TxqException {
            write(new byte[]{(byte) (value >>> 24), (byte) (value >>> 16),
                    (byte) (value >>> 8), (byte) value});
        }

        private void require(int requested) throws TxqException {
            if ((long) bytes.size() + requested > limit) {
                throw error("SIZE_LIMIT", bytes.size(),
                        "serialized TXQ exceeds output limit");
            }
        }

        private byte[] toByteArray() {
            return bytes.toByteArray();
        }
    }
}
