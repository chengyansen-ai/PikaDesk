package com.sojourners.chess.book;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Strict, checksummed persistence for resumable XQB batch metadata. */
final class XqbRecoveryStore {

    private static final byte[] MAGIC = "PDRSM001".getBytes(StandardCharsets.US_ASCII);
    private static final int FORMAT_VERSION = 1;
    private static final int CHECKSUM_BYTES = 32;
    private static final int MAX_MANIFEST_BYTES = 1_048_576;
    private static final int MAX_PATH_BYTES = 32_768;
    private static final int MAX_CODE_BYTES = 128;
    private static final int MAX_MESSAGE_BYTES = 8_192;

    private final Path directory;
    private final Path manifestPath;
    private final Path lockPath;

    XqbRecoveryStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
        manifestPath = directory.resolve("manifest.bin");
        lockPath = directory.resolve("batch.lock");
    }

    LockHandle acquire() throws IOException {
        ensureDirectory();
        FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                throw failure("CHECKPOINT_BUSY", "another XQB batch owns this checkpoint");
            }
            return new LockHandle(channel, lock);
        } catch (OverlappingFileLockException busy) {
            channel.close();
            throw failure("CHECKPOINT_BUSY", "another XQB batch owns this checkpoint", busy);
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
    }

    boolean exists() {
        return Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS);
    }

    Manifest read() throws IOException {
        try {
            if (!Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(manifestPath)) {
                throw corrupt("checkpoint manifest is missing or is not a regular file");
            }
            long size = Files.size(manifestPath);
            if (size <= CHECKSUM_BYTES || size > MAX_MANIFEST_BYTES) {
                throw corrupt("checkpoint manifest size is invalid");
            }
            byte[] stored;
            try (InputStream input = Files.newInputStream(manifestPath)) {
                stored = input.readNBytes(MAX_MANIFEST_BYTES + 1);
            }
            if (stored.length > MAX_MANIFEST_BYTES) {
                throw corrupt("checkpoint manifest grew beyond its size limit");
            }
            byte[] payload = Arrays.copyOf(stored, stored.length - CHECKSUM_BYTES);
            byte[] checksum = Arrays.copyOfRange(stored,
                    stored.length - CHECKSUM_BYTES, stored.length);
            if (!MessageDigest.isEqual(sha256(payload), checksum)) {
                throw corrupt("checkpoint manifest checksum does not match");
            }
            return decode(payload);
        } catch (XqbBatchService.BookBatchException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw corrupt("checkpoint manifest cannot be decoded", failure);
        }
    }

    void write(Manifest manifest) throws IOException {
        Objects.requireNonNull(manifest, "manifest");
        byte[] payload = encode(manifest);
        byte[] checksum = sha256(payload);
        if ((long) payload.length + checksum.length > MAX_MANIFEST_BYTES) {
            throw failure("CHECKPOINT_TOO_LARGE", "checkpoint metadata exceeds its size limit");
        }
        Path temporary = Files.createTempFile(directory, ".manifest-", ".tmp");
        try {
            try (FileChannel output = FileChannel.open(temporary,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                writeFully(output, ByteBuffer.wrap(payload));
                writeFully(output, ByteBuffer.wrap(checksum));
                output.force(true);
            }
            moveAtomically(temporary, manifestPath);
            temporary = null;
        } finally {
            if (temporary != null) Files.deleteIfExists(temporary);
        }
    }

    Path chunk(int sourceIndex) {
        if (sourceIndex < 0) throw new IllegalArgumentException("sourceIndex must not be negative");
        return directory.resolve("source-%04d.xqb".formatted(sourceIndex));
    }

    Path finalOutput() {
        return directory.resolve("final-output.xqb");
    }

    void discardKnownFiles(int maximumSources) throws IOException {
        Files.deleteIfExists(manifestPath);
        deleteDatabaseFiles(finalOutput());
        for (int sourceIndex = 0; sourceIndex < maximumSources; sourceIndex++) {
            deleteDatabaseFiles(chunk(sourceIndex));
        }
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString()
                    .matches("\\.manifest-[A-Za-z0-9._-]+\\.tmp")).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    private void ensureDirectory() throws IOException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(directory)
                    || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw corrupt("checkpoint workspace is not a real directory");
            }
            return;
        }
        try {
            Files.createDirectory(directory);
        } catch (FileAlreadyExistsException raced) {
            if (Files.isSymbolicLink(directory)
                    || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw corrupt("checkpoint workspace is not a real directory", raced);
            }
        }
    }

    private static byte[] encode(Manifest manifest) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(MAGIC);
            output.writeInt(FORMAT_VERSION);
            writeString(output, manifest.destination().toString(), MAX_PATH_BYTES);
            writeLimits(output, manifest.limits());
            output.writeInt(manifest.entries().size());
            for (Entry entry : manifest.entries()) {
                Fingerprint source = entry.source();
                writeString(output, source.path().toString(), MAX_PATH_BYTES);
                output.writeLong(source.size());
                output.writeLong(source.modifiedMillis());
                output.write(source.sha256());
                output.writeBoolean(entry.completed() != null);
                if (entry.completed() != null) writeCompleted(output, entry.completed());
            }
        }
        return bytes.toByteArray();
    }

    private static Manifest decode(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            byte[] magic = input.readNBytes(MAGIC.length);
            if (!Arrays.equals(MAGIC, magic) || input.readInt() != FORMAT_VERSION) {
                throw corrupt("checkpoint manifest version is unsupported");
            }
            Path destination = normalizedPath(readString(input, MAX_PATH_BYTES));
            XqbBatchService.Limits limits = readLimits(input);
            int sourceCount = input.readInt();
            if (sourceCount < 1 || sourceCount > limits.maxInputFiles()) {
                throw corrupt("checkpoint source count is invalid");
            }
            List<Entry> entries = new ArrayList<>(sourceCount);
            for (int sourceIndex = 0; sourceIndex < sourceCount; sourceIndex++) {
                Path sourcePath = normalizedPath(readString(input, MAX_PATH_BYTES));
                long size = input.readLong();
                long modified = input.readLong();
                byte[] digest = input.readNBytes(CHECKSUM_BYTES);
                if (size < 0 || modified < 0 || digest.length != CHECKSUM_BYTES) {
                    throw corrupt("checkpoint source fingerprint is invalid");
                }
                Fingerprint source = new Fingerprint(sourcePath, size, modified, digest);
                Completed completed = input.readBoolean()
                        ? readCompleted(input, source, limits) : null;
                entries.add(new Entry(source, completed));
            }
            if (input.read() != -1) throw corrupt("checkpoint manifest has trailing data");
            return new Manifest(destination, limits, entries);
        } catch (EOFException truncated) {
            throw corrupt("checkpoint manifest is truncated", truncated);
        } catch (IllegalArgumentException invalid) {
            throw corrupt("checkpoint manifest contains invalid values", invalid);
        }
    }

    private static void writeLimits(DataOutputStream output, XqbBatchService.Limits limits)
            throws IOException {
        output.writeInt(limits.maxInputFiles());
        output.writeLong(limits.maxSourceBytes());
        output.writeLong(limits.maxTotalRows());
        output.writeInt(limits.maxKeyBytes());
        output.writeInt(limits.maxMemoBytes());
        output.writeInt(limits.maxIssueSamples());
        output.writeInt(limits.progressEveryRows());
        output.writeInt(limits.queryTimeoutSeconds());
    }

    private static XqbBatchService.Limits readLimits(DataInputStream input) throws IOException {
        return new XqbBatchService.Limits(input.readInt(), input.readLong(), input.readLong(),
                input.readInt(), input.readInt(), input.readInt(), input.readInt(), input.readInt());
    }

    private static void writeCompleted(DataOutputStream output, Completed completed)
            throws IOException {
        output.writeLong(completed.chunkSize());
        output.write(completed.chunkSha256());
        XqbBatchService.BatchReport report = completed.report();
        output.writeLong(report.scannedRows());
        output.writeLong(report.writtenRows());
        output.writeLong(report.duplicateRows());
        output.writeLong(report.rejectedRows());
        output.writeLong(report.omittedIssueCount());
        output.writeInt(report.issues().size());
        for (XqbBatchService.Issue issue : report.issues()) {
            writeString(output, issue.code(), MAX_CODE_BYTES);
            writeString(output, issue.message(), MAX_MESSAGE_BYTES);
            output.writeLong(issue.rowNumber());
        }
    }

    private static Completed readCompleted(DataInputStream input,
                                           Fingerprint source,
                                           XqbBatchService.Limits limits) throws IOException {
        long chunkSize = input.readLong();
        byte[] chunkDigest = input.readNBytes(CHECKSUM_BYTES);
        long scanned = input.readLong();
        long written = input.readLong();
        long duplicate = input.readLong();
        long rejected = input.readLong();
        long omitted = input.readLong();
        int issueCount = input.readInt();
        if (chunkSize < 1 || chunkDigest.length != CHECKSUM_BYTES
                || issueCount < 0 || issueCount > limits.maxIssueSamples()) {
            throw corrupt("checkpoint result metadata is invalid");
        }
        List<XqbBatchService.Issue> issues = new ArrayList<>(issueCount);
        for (int issueIndex = 0; issueIndex < issueCount; issueIndex++) {
            String code = readString(input, MAX_CODE_BYTES);
            String message = readString(input, MAX_MESSAGE_BYTES);
            long row = input.readLong();
            issues.add(new XqbBatchService.Issue(code, message, source.path(), row));
        }
        XqbBatchService.BatchReport report = new XqbBatchService.BatchReport(
                1, scanned, written, duplicate, rejected, issues, omitted);
        return new Completed(report, chunkSize, chunkDigest);
    }

    private static void writeString(DataOutputStream output, String value, int maximumBytes)
            throws IOException {
        byte[] bytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximumBytes) {
            throw failure("CHECKPOINT_TOO_LARGE", "checkpoint text exceeds its size limit");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input, int maximumBytes) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximumBytes) {
            throw corrupt("checkpoint text length is invalid");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw corrupt("checkpoint text is truncated");
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalidUtf8) {
            throw corrupt("checkpoint text is not strict UTF-8", invalidUtf8);
        }
    }

    private static Path normalizedPath(String text) throws IOException {
        Path path = Path.of(text);
        if (!path.isAbsolute() || !path.equals(path.normalize())) {
            throw corrupt("checkpoint path is not normalized and absolute");
        }
        return path;
    }

    private static byte[] sha256(byte[] bytes) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) channel.write(bytes);
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteDatabaseFiles(Path path) throws IOException {
        Files.deleteIfExists(path);
        Files.deleteIfExists(Path.of(path + "-journal"));
        Files.deleteIfExists(Path.of(path + "-wal"));
        Files.deleteIfExists(Path.of(path + "-shm"));
    }

    private static XqbBatchService.BookBatchException corrupt(String message) {
        return failure("CHECKPOINT_CORRUPT", message);
    }

    private static XqbBatchService.BookBatchException corrupt(String message, Throwable cause) {
        return failure("CHECKPOINT_CORRUPT", message, cause);
    }

    private static XqbBatchService.BookBatchException failure(String code, String message) {
        return new XqbBatchService.BookBatchException(code, message);
    }

    private static XqbBatchService.BookBatchException failure(
            String code, String message, Throwable cause) {
        return new XqbBatchService.BookBatchException(code, message, cause);
    }

    record Fingerprint(Path path, long size, long modifiedMillis, byte[] sha256) {
        Fingerprint {
            Objects.requireNonNull(path, "path");
            sha256 = Objects.requireNonNull(sha256, "sha256").clone();
            if (!path.isAbsolute() || size < 0 || modifiedMillis < 0
                    || sha256.length != CHECKSUM_BYTES) {
                throw new IllegalArgumentException("invalid recovery fingerprint");
            }
        }

        @Override
        public byte[] sha256() {
            return sha256.clone();
        }
    }

    record Completed(XqbBatchService.BatchReport report,
                     long chunkSize,
                     byte[] chunkSha256) {
        Completed {
            Objects.requireNonNull(report, "report");
            chunkSha256 = Objects.requireNonNull(chunkSha256, "chunkSha256").clone();
            if (report.sourceCount() != 1 || chunkSize < 1
                    || chunkSha256.length != CHECKSUM_BYTES) {
                throw new IllegalArgumentException("invalid completed checkpoint");
            }
        }

        @Override
        public byte[] chunkSha256() {
            return chunkSha256.clone();
        }
    }

    record Entry(Fingerprint source, Completed completed) {
        Entry {
            Objects.requireNonNull(source, "source");
        }
    }

    record Manifest(Path destination,
                    XqbBatchService.Limits limits,
                    List<Entry> entries) {
        Manifest {
            Objects.requireNonNull(destination, "destination");
            Objects.requireNonNull(limits, "limits");
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
            if (!destination.isAbsolute() || entries.isEmpty()
                    || entries.size() > limits.maxInputFiles()) {
                throw new IllegalArgumentException("invalid recovery manifest");
            }
        }

        Manifest withCompleted(int sourceIndex, Completed completed) {
            List<Entry> updated = new ArrayList<>(entries);
            Entry prior = updated.get(sourceIndex);
            updated.set(sourceIndex, new Entry(prior.source(), completed));
            return new Manifest(destination, limits, updated);
        }
    }

    record LockHandle(FileChannel channel, FileLock lock) implements AutoCloseable {
        LockHandle {
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(lock, "lock");
        }

        @Override
        public void close() throws IOException {
            try {
                lock.close();
            } finally {
                channel.close();
            }
        }
    }
}
