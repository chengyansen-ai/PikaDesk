package com.sojourners.chess.book;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Adds cross-process, per-source checkpoints around the bounded XQB builder.
 * Each completed source becomes a canonical XQB chunk; only the final merge can
 * replace the user's destination.
 */
public final class XqbResumableBatchService {

    private static final int HASH_BUFFER_BYTES = 64 * 1024;

    private final XqbBatchService.Limits limits;

    public XqbResumableBatchService() {
        this(XqbBatchService.Limits.defaults());
    }

    public XqbResumableBatchService(XqbBatchService.Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public XqbBatchService.BatchReport buildOrResume(
            List<Path> sourceFiles,
            Path destination,
            BooleanSupplier cancellationRequested,
            Consumer<XqbBatchService.Progress> progressListener) throws IOException {
        Objects.requireNonNull(cancellationRequested, "cancellationRequested");
        Objects.requireNonNull(progressListener, "progressListener");
        List<Path> sources = normalizeSources(sourceFiles);
        Path target = normalizeDestination(destination, sources);
        XqbRecoveryStore store = new XqbRecoveryStore(recoveryDirectory(target));

        try (XqbRecoveryStore.LockHandle ignored = store.acquire()) {
            checkCancelled(cancellationRequested);
            XqbRecoveryStore.Manifest manifest = store.exists()
                    ? loadMatchingManifest(store, sources, target, cancellationRequested)
                    : createManifest(store, sources, target, cancellationRequested,
                    progressListener);
            verifyCompletedChunks(store, manifest, cancellationRequested);
            Aggregate aggregate = Aggregate.from(manifest, limits.maxIssueSamples());

            for (int sourceIndex = 0; sourceIndex < sources.size(); sourceIndex++) {
                XqbRecoveryStore.Entry entry = manifest.entries().get(sourceIndex);
                if (entry.completed() != null) {
                    progressListener.accept(aggregate.progress(
                            XqbBatchService.Phase.CHECKPOINTING,
                            sourceIndex + 1, sources.size(), entry.source().path()));
                    checkCancelled(cancellationRequested);
                    continue;
                }
                long remainingRows = limits.maxTotalRows() - aggregate.scannedRows;
                if (remainingRows < 1) throw rowLimitExceeded();
                int remainingIssues = Math.max(0,
                        limits.maxIssueSamples() - aggregate.issues.size());
                verifyFingerprint(entry.source(), cancellationRequested,
                        "CHECKPOINT_MISMATCH");
                Path chunk = store.chunk(sourceIndex);
                XqbBatchService service = new XqbBatchService(
                        sourceLimits(remainingRows, remainingIssues));
                int index = sourceIndex;
                Aggregate before = aggregate;
                XqbBatchService.BatchReport sourceReport = service.build(
                        List.of(entry.source().path()), chunk, cancellationRequested,
                        progress -> progressListener.accept(mapSourceProgress(
                                progress, before, index, sources.size(), entry.source().path())));
                verifyFingerprint(entry.source(), cancellationRequested,
                        "SOURCE_CHANGED");
                XqbRecoveryStore.Fingerprint chunkFingerprint = fingerprint(
                        chunk, Long.MAX_VALUE, cancellationRequested);
                XqbRecoveryStore.Completed completed = new XqbRecoveryStore.Completed(
                        sourceReport, chunkFingerprint.size(), chunkFingerprint.sha256());
                manifest = manifest.withCompleted(sourceIndex, completed);
                store.write(manifest);
                aggregate = Aggregate.from(manifest, limits.maxIssueSamples());
                progressListener.accept(aggregate.progress(
                        XqbBatchService.Phase.CHECKPOINTING,
                        sourceIndex + 1, sources.size(), entry.source().path()));
                checkCancelled(cancellationRequested);
            }

            verifyManifestSources(manifest, sources, target, cancellationRequested);
            verifyCompletedChunks(store, manifest, cancellationRequested);
            XqbBatchService.BatchReport finalMerge = mergeChunks(
                    store, manifest, aggregate, target, cancellationRequested,
                    progressListener);
            XqbBatchService.BatchReport report = aggregate.finish(finalMerge);
            checkCancelled(cancellationRequested);
            verifyManifestSources(manifest, sources, target, cancellationRequested);
            verifyCompletedChunks(store, manifest, cancellationRequested);
            replaceAtomically(store.finalOutput(), target);
            try {
                store.discardKnownFiles(limits.maxInputFiles());
            } catch (IOException ignoredCleanupFailure) {
                // The completed destination is authoritative. A stale checkpoint is
                // checksummed and will be rejected if any chunk was only partly removed.
            }
            progressListener.accept(new XqbBatchService.Progress(
                    XqbBatchService.Phase.COMPLETED, sources.size(), sources.size(),
                    report.scannedRows(), report.writtenRows(), report.duplicateRows(),
                    report.rejectedRows(), report.writtenRows(), target));
            return report;
        }
    }

    public RecoveryStatus inspect(Path destination) throws IOException {
        Path target = normalizePath(destination, "destination");
        Path workspace = recoveryDirectory(target);
        XqbRecoveryStore store = new XqbRecoveryStore(workspace);
        if (!store.exists()) return RecoveryStatus.unavailable();
        try (XqbRecoveryStore.LockHandle ignored = store.acquire()) {
            XqbRecoveryStore.Manifest manifest = store.read();
            if (!manifest.destination().equals(target)) {
                throw failure("CHECKPOINT_MISMATCH",
                        "checkpoint belongs to a different destination");
            }
            int completed = 0;
            long scanned = 0;
            for (XqbRecoveryStore.Entry entry : manifest.entries()) {
                if (entry.completed() == null) break;
                completed++;
                scanned += entry.completed().report().scannedRows();
            }
            return new RecoveryStatus(true, completed, manifest.entries().size(), scanned,
                    manifest.entries().stream().map(entry -> entry.source().path()).toList());
        }
    }

    public void discard(Path destination) throws IOException {
        Path target = normalizePath(destination, "destination");
        Path workspace = recoveryDirectory(target);
        if (!Files.exists(workspace)) return;
        XqbRecoveryStore store = new XqbRecoveryStore(workspace);
        try (XqbRecoveryStore.LockHandle ignored = store.acquire()) {
            store.discardKnownFiles(limits.maxInputFiles());
        }
    }

    static Path recoveryDirectory(Path destination) {
        Path target = normalizePath(destination, "destination");
        return target.resolveSibling("." + target.getFileName() + ".pikadesk-resume");
    }

    private XqbRecoveryStore.Manifest createManifest(
            XqbRecoveryStore store,
            List<Path> sources,
            Path target,
            BooleanSupplier cancellationRequested,
            Consumer<XqbBatchService.Progress> progressListener) throws IOException {
        List<XqbRecoveryStore.Entry> entries = new ArrayList<>(sources.size());
        for (int sourceIndex = 0; sourceIndex < sources.size(); sourceIndex++) {
            Path source = sources.get(sourceIndex);
            progressListener.accept(new XqbBatchService.Progress(
                    XqbBatchService.Phase.VALIDATING, sourceIndex, sources.size(),
                    0, 0, 0, 0, 0, source));
            entries.add(new XqbRecoveryStore.Entry(
                    fingerprint(source, limits.maxSourceBytes(), cancellationRequested), null));
        }
        XqbRecoveryStore.Manifest manifest = new XqbRecoveryStore.Manifest(
                target, limits, entries);
        store.write(manifest);
        return manifest;
    }

    private XqbRecoveryStore.Manifest loadMatchingManifest(
            XqbRecoveryStore store,
            List<Path> sources,
            Path target,
            BooleanSupplier cancellationRequested) throws IOException {
        XqbRecoveryStore.Manifest manifest = store.read();
        if (!manifest.destination().equals(target) || !manifest.limits().equals(limits)
                || manifest.entries().size() != sources.size()) {
            throw failure("CHECKPOINT_MISMATCH",
                    "checkpoint destination, limits, or source count changed");
        }
        verifyManifestSources(manifest, sources, target, cancellationRequested);
        return manifest;
    }

    private void verifyManifestSources(XqbRecoveryStore.Manifest manifest,
                                       List<Path> sources,
                                       Path target,
                                       BooleanSupplier cancellationRequested) throws IOException {
        if (!manifest.destination().equals(target)
                || manifest.entries().size() != sources.size()) {
            throw failure("CHECKPOINT_MISMATCH", "checkpoint task identity changed");
        }
        for (int sourceIndex = 0; sourceIndex < sources.size(); sourceIndex++) {
            XqbRecoveryStore.Fingerprint expected = manifest.entries().get(sourceIndex).source();
            if (!expected.path().equals(sources.get(sourceIndex))) {
                throw failure("CHECKPOINT_MISMATCH", "checkpoint source order or path changed");
            }
            verifyFingerprint(expected, cancellationRequested, "CHECKPOINT_MISMATCH");
        }
    }

    private void verifyCompletedChunks(XqbRecoveryStore store,
                                       XqbRecoveryStore.Manifest manifest,
                                       BooleanSupplier cancellationRequested) throws IOException {
        boolean foundIncomplete = false;
        for (int sourceIndex = 0; sourceIndex < manifest.entries().size(); sourceIndex++) {
            XqbRecoveryStore.Completed completed = manifest.entries().get(sourceIndex).completed();
            if (completed == null) {
                foundIncomplete = true;
                continue;
            }
            if (foundIncomplete) {
                throw failure("CHECKPOINT_CORRUPT",
                        "completed checkpoint sources are not contiguous");
            }
            XqbRecoveryStore.Fingerprint actual = fingerprint(
                    store.chunk(sourceIndex), Long.MAX_VALUE, cancellationRequested);
            if (actual.size() != completed.chunkSize()
                    || !Arrays.equals(actual.sha256(), completed.chunkSha256())) {
                throw failure("CHECKPOINT_CORRUPT", "checkpoint chunk fingerprint changed");
            }
        }
    }

    private XqbBatchService.BatchReport mergeChunks(
            XqbRecoveryStore store,
            XqbRecoveryStore.Manifest manifest,
            Aggregate aggregate,
            Path target,
            BooleanSupplier cancellationRequested,
            Consumer<XqbBatchService.Progress> progressListener) throws IOException {
        List<Path> chunks = new ArrayList<>(manifest.entries().size());
        for (int sourceIndex = 0; sourceIndex < manifest.entries().size(); sourceIndex++) {
            chunks.add(store.chunk(sourceIndex));
        }
        XqbBatchService.Limits mergeLimits = new XqbBatchService.Limits(
                limits.maxInputFiles(), Long.MAX_VALUE, limits.maxTotalRows(),
                limits.maxKeyBytes(), limits.maxMemoBytes(), 0,
                limits.progressEveryRows(), limits.queryTimeoutSeconds());
        return new XqbBatchService(mergeLimits).build(
                chunks, store.finalOutput(), cancellationRequested,
                progress -> progressListener.accept(mapMergeProgress(
                        progress, aggregate, target, chunks.size())));
    }

    private XqbBatchService.Progress mapSourceProgress(
            XqbBatchService.Progress progress,
            Aggregate base,
            int sourceIndex,
            int sourceCount,
            Path source) {
        XqbBatchService.Phase phase = progress.phase() == XqbBatchService.Phase.VALIDATING
                ? XqbBatchService.Phase.VALIDATING : XqbBatchService.Phase.READING;
        return new XqbBatchService.Progress(phase, sourceIndex, sourceCount,
                base.scannedRows + progress.scannedRows(),
                base.chunkRows + progress.acceptedRows(),
                base.duplicateRows + progress.duplicateRows(),
                base.rejectedRows + progress.rejectedRows(), 0, source);
    }

    private XqbBatchService.Progress mapMergeProgress(
            XqbBatchService.Progress progress,
            Aggregate aggregate,
            Path target,
            int sourceCount) {
        long crossSourceDuplicates = progress.duplicateRows();
        long accepted = progress.phase() == XqbBatchService.Phase.WRITING
                || progress.phase() == XqbBatchService.Phase.COMPLETED
                ? progress.writtenRows() : progress.acceptedRows();
        return new XqbBatchService.Progress(XqbBatchService.Phase.WRITING,
                sourceCount, sourceCount, aggregate.scannedRows, accepted,
                aggregate.duplicateRows + crossSourceDuplicates,
                aggregate.rejectedRows, progress.writtenRows(), target);
    }

    private XqbBatchService.Limits sourceLimits(long remainingRows, int remainingIssues) {
        return new XqbBatchService.Limits(1, limits.maxSourceBytes(), remainingRows,
                limits.maxKeyBytes(), limits.maxMemoBytes(), remainingIssues,
                limits.progressEveryRows(), limits.queryTimeoutSeconds());
    }

    private List<Path> normalizeSources(List<Path> sourceFiles) throws IOException {
        Objects.requireNonNull(sourceFiles, "sourceFiles");
        if (sourceFiles.isEmpty()) {
            throw failure("NO_SOURCES", "at least one XQB source is required");
        }
        if (sourceFiles.size() > limits.maxInputFiles()) {
            throw failure("TOO_MANY_SOURCES", "XQB batch exceeds the input-file limit");
        }
        LinkedHashSet<Path> sources = new LinkedHashSet<>();
        for (Path source : sourceFiles) sources.add(normalizePath(source, "source"));
        return List.copyOf(sources);
    }

    private Path normalizeDestination(Path destination, List<Path> sources) throws IOException {
        Path target = normalizePath(destination, "destination");
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw failure("INVALID_DESTINATION", "XQB destination directory does not exist");
        }
        if (!hasXqbExtension(target) || Files.isDirectory(target)) {
            throw failure("INVALID_DESTINATION", "XQB destination must be an .xqb file");
        }
        for (Path source : sources) {
            if (source.equals(target) || Files.exists(source) && Files.exists(target)
                    && Files.isSameFile(source, target)) {
                throw failure("SOURCE_DESTINATION_OVERLAP",
                        "XQB destination must not also be an input source");
            }
        }
        return target;
    }

    private XqbRecoveryStore.Fingerprint fingerprint(
            Path path, long maximumBytes, BooleanSupplier cancellationRequested)
            throws IOException {
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
            throw failure("INVALID_SOURCE", "checkpoint input is not a regular file");
        }
        if (!hasXqbExtension(path)) {
            throw failure("UNSUPPORTED_EXTENSION", "checkpoint inputs must use .xqb");
        }
        long sizeBefore = Files.size(path);
        long modifiedBefore = Files.getLastModifiedTime(path).toMillis();
        if (sizeBefore > maximumBytes) {
            throw failure("SOURCE_TOO_LARGE", "checkpoint input exceeds its size limit");
        }
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[HASH_BUFFER_BYTES];
            long total = 0;
            for (int count; (count = input.read(buffer)) >= 0; ) {
                if (count > 0) {
                    total += count;
                    if (total > maximumBytes) {
                        throw failure("SOURCE_TOO_LARGE",
                                "checkpoint input grew beyond its size limit");
                    }
                    digest.update(buffer, 0, count);
                }
                checkCancelled(cancellationRequested);
            }
        }
        long sizeAfter = Files.size(path);
        long modifiedAfter = Files.getLastModifiedTime(path).toMillis();
        if (sizeBefore != sizeAfter || modifiedBefore != modifiedAfter) {
            throw failure("SOURCE_CHANGED", "XQB source changed while it was fingerprinted");
        }
        return new XqbRecoveryStore.Fingerprint(path, sizeAfter,
                modifiedAfter, digest.digest());
    }

    private void verifyFingerprint(XqbRecoveryStore.Fingerprint expected,
                                   BooleanSupplier cancellationRequested,
                                   String mismatchCode) throws IOException {
        XqbRecoveryStore.Fingerprint actual = fingerprint(
                expected.path(), limits.maxSourceBytes(), cancellationRequested);
        if (actual.size() != expected.size()
                || actual.modifiedMillis() != expected.modifiedMillis()
                || !Arrays.equals(actual.sha256(), expected.sha256())) {
            throw failure(mismatchCode, "XQB source fingerprint changed");
        }
    }

    private static MessageDigest sha256Digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
    }

    private static void checkCancelled(BooleanSupplier cancellationRequested)
            throws XqbBatchService.BookBatchException {
        if (cancellationRequested.getAsBoolean()) {
            throw failure("CANCELLED", "resumable XQB batch was cancelled");
        }
    }

    private static Path normalizePath(Path path, String label) {
        return Objects.requireNonNull(path, label).toAbsolutePath().normalize();
    }

    private static boolean hasXqbExtension(Path path) {
        Path name = path.getFileName();
        return name != null && name.toString().toLowerCase(Locale.ROOT).endsWith(".xqb");
    }

    private static void replaceAtomically(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static XqbBatchService.BookBatchException rowLimitExceeded() {
        return failure("ROW_LIMIT_EXCEEDED", "XQB batch exceeds its total row limit");
    }

    private static XqbBatchService.BookBatchException failure(String code, String message) {
        return new XqbBatchService.BookBatchException(code, message);
    }

    public record RecoveryStatus(boolean available,
                                 int completedSources,
                                 int sourceCount,
                                 long scannedRows,
                                 List<Path> sources) {
        public RecoveryStatus {
            sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
            if (completedSources < 0 || sourceCount < 0 || completedSources > sourceCount
                    || scannedRows < 0 || !available && (completedSources != 0
                    || sourceCount != 0 || scannedRows != 0 || !sources.isEmpty())
                    || available && sources.size() != sourceCount) {
                throw new IllegalArgumentException("invalid recovery status");
            }
        }

        private static RecoveryStatus unavailable() {
            return new RecoveryStatus(false, 0, 0, 0, List.of());
        }
    }

    private static final class Aggregate {
        private final long scannedRows;
        private final long chunkRows;
        private final long duplicateRows;
        private final long rejectedRows;
        private final List<XqbBatchService.Issue> issues;
        private final long omittedIssues;

        private Aggregate(long scannedRows,
                          long chunkRows,
                          long duplicateRows,
                          long rejectedRows,
                          List<XqbBatchService.Issue> issues,
                          long omittedIssues) {
            this.scannedRows = scannedRows;
            this.chunkRows = chunkRows;
            this.duplicateRows = duplicateRows;
            this.rejectedRows = rejectedRows;
            this.issues = List.copyOf(issues);
            this.omittedIssues = omittedIssues;
        }

        private static Aggregate from(XqbRecoveryStore.Manifest manifest,
                                      int maximumIssueSamples) throws IOException {
            long scanned = 0;
            long chunks = 0;
            long duplicates = 0;
            long rejected = 0;
            long omitted = 0;
            List<XqbBatchService.Issue> issues = new ArrayList<>();
            boolean foundIncomplete = false;
            for (XqbRecoveryStore.Entry entry : manifest.entries()) {
                XqbRecoveryStore.Completed completed = entry.completed();
                if (completed == null) {
                    foundIncomplete = true;
                    continue;
                }
                if (foundIncomplete) {
                    throw failure("CHECKPOINT_CORRUPT",
                            "completed checkpoint sources are not contiguous");
                }
                XqbBatchService.BatchReport report = completed.report();
                scanned += report.scannedRows();
                chunks += report.writtenRows();
                duplicates += report.duplicateRows();
                rejected += report.rejectedRows();
                omitted += report.omittedIssueCount();
                for (XqbBatchService.Issue issue : report.issues()) {
                    if (issues.size() < maximumIssueSamples) issues.add(issue);
                    else omitted++;
                }
            }
            return new Aggregate(scanned, chunks, duplicates, rejected, issues, omitted);
        }

        private XqbBatchService.Progress progress(XqbBatchService.Phase phase,
                                                  int completedSources,
                                                  int sourceCount,
                                                  Path currentFile) {
            return new XqbBatchService.Progress(phase, completedSources, sourceCount,
                    scannedRows, chunkRows, duplicateRows, rejectedRows, 0, currentFile);
        }

        private XqbBatchService.BatchReport finish(
                XqbBatchService.BatchReport finalMerge) throws IOException {
            if (finalMerge.rejectedRows() != 0 || !finalMerge.issues().isEmpty()
                    || finalMerge.scannedRows() != chunkRows) {
                throw failure("CHECKPOINT_CORRUPT",
                        "canonical checkpoint chunks failed final validation");
            }
            return new XqbBatchService.BatchReport(finalMerge.sourceCount(), scannedRows,
                    finalMerge.writtenRows(), duplicateRows + finalMerge.duplicateRows(),
                    rejectedRows, issues, omittedIssues);
        }
    }
}
