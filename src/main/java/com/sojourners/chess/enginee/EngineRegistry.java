package com.sojourners.chess.enginee;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * A bounded owner for up to five explicitly configured local UCI/UCCI engine
 * processes. Registration is side-effect free: processes are only created by
 * {@link #startAll()}, after global thread/hash budgets and executable hashes
 * have been validated.
 */
public final class EngineRegistry implements AutoCloseable {

    public static final int MAX_ENGINES = 5;
    private static final int OUTPUT_QUEUE_CAPACITY = 512;
    private static final int MAX_OUTPUT_LINE_LENGTH = 16_384;
    private static final int MAX_COMMAND_LENGTH = 4_096;
    private static final int PROCESS_STOP_TIMEOUT_MILLIS = 500;

    private final Object lock = new Object();
    private final ResourceBudget budget;
    private final CopyOnWriteArrayList<OutputListener> outputListeners =
            new CopyOnWriteArrayList<>();
    private final Map<String, EngineSlot> slots = new LinkedHashMap<>();
    private boolean closed;

    public EngineRegistry(ResourceBudget budget, OutputListener outputListener) {
        this.budget = Objects.requireNonNull(budget, "budget");
        this.outputListeners.add(Objects.requireNonNull(outputListener, "outputListener"));
    }

    public AutoCloseable addOutputListener(OutputListener outputListener) {
        Objects.requireNonNull(outputListener, "outputListener");
        synchronized (lock) {
            ensureOpen();
            outputListeners.add(outputListener);
        }
        return () -> outputListeners.remove(outputListener);
    }

    public EngineSnapshot register(EngineSpec spec) throws IOException {
        Objects.requireNonNull(spec, "spec");
        EngineSlot prepared = prepare(spec);
        synchronized (lock) {
            ensureOpen();
            validateRegistration(List.of(prepared));
            slots.put(spec.id(), prepared);
            return prepared.snapshot();
        }
    }

    public List<EngineSnapshot> registerAll(List<EngineSpec> specs) throws IOException {
        Objects.requireNonNull(specs, "specs");
        if (specs.isEmpty()) {
            return List.of();
        }
        List<EngineSlot> prepared = new ArrayList<>(specs.size());
        for (EngineSpec spec : specs) {
            prepared.add(prepare(Objects.requireNonNull(spec, "spec")));
        }
        synchronized (lock) {
            ensureOpen();
            validateRegistration(prepared);
            for (EngineSlot slot : prepared) {
                slots.put(slot.spec.id(), slot);
            }
            return prepared.stream().map(EngineSlot::snapshot).toList();
        }
    }

    public List<EngineSnapshot> startAll() throws InterruptedException {
        List<EngineSlot> toStart;
        synchronized (lock) {
            ensureOpen();
            toStart = slots.values().stream()
                    .filter(slot -> slot.status == Status.REGISTERED)
                    .toList();
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(toStart.size());
            for (EngineSlot slot : toStart) {
                futures.add(executor.submit(() -> start(slot)));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException impossible) {
                    throw new IllegalStateException("engine start task escaped isolation", impossible);
                }
            }
        }
        return snapshots();
    }

    public void send(String engineId, String command) throws IOException {
        engine(engineId).send(command);
    }

    public EngineSnapshot snapshot(String engineId) {
        return engine(engineId).snapshot();
    }

    public List<EngineSnapshot> snapshots() {
        synchronized (lock) {
            return slots.values().stream().map(EngineSlot::snapshot).toList();
        }
    }

    public int allocatedThreads() {
        synchronized (lock) {
            return slots.values().stream().mapToInt(slot -> slot.spec.threads()).sum();
        }
    }

    public int allocatedHashMiB() {
        synchronized (lock) {
            return slots.values().stream().mapToInt(slot -> slot.spec.hashMiB()).sum();
        }
    }

    @Override
    public void close() {
        List<EngineSlot> toClose;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            toClose = List.copyOf(slots.values());
        }
        for (EngineSlot slot : toClose) {
            slot.close();
        }
    }

    private void start(EngineSlot slot) {
        try {
            slot.start();
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            slot.fail(normalize(failure));
        }
    }

    private EngineSlot prepare(EngineSpec spec) throws IOException {
        Path executable = spec.executable().toRealPath();
        if (!Files.isRegularFile(executable)) {
            throw new IllegalArgumentException("engine executable must be a regular file");
        }
        return new EngineSlot(spec.withExecutable(executable), sha256(executable));
    }

    private void validateRegistration(List<EngineSlot> additions) {
        Map<String, Boolean> ids = new LinkedHashMap<>();
        for (String existing : slots.keySet()) {
            ids.put(existing, true);
        }
        for (EngineSlot slot : additions) {
            if (ids.put(slot.spec.id(), true) != null) {
                throw new IllegalArgumentException("duplicate engine id: " + slot.spec.id());
            }
        }
        if (slots.size() + additions.size() > MAX_ENGINES) {
            throw new IllegalStateException("at most five engines may be registered");
        }
        int threads = allocatedThreads();
        int hashMiB = allocatedHashMiB();
        for (EngineSlot slot : additions) {
            threads = Math.addExact(threads, slot.spec.threads());
            hashMiB = Math.addExact(hashMiB, slot.spec.hashMiB());
        }
        if (threads > budget.maxThreads()) {
            throw new IllegalArgumentException("engine thread budget exceeded: "
                    + threads + "/" + budget.maxThreads());
        }
        if (hashMiB > budget.maxHashMiB()) {
            throw new IllegalArgumentException("engine hash budget exceeded: "
                    + hashMiB + "/" + budget.maxHashMiB() + " MiB");
        }
    }

    private EngineSlot engine(String engineId) {
        Objects.requireNonNull(engineId, "engineId");
        synchronized (lock) {
            EngineSlot slot = slots.get(engineId);
            if (slot == null) {
                throw new IllegalArgumentException("unknown engine id: " + engineId);
            }
            return slot;
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("engine registry is closed");
        }
    }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private String normalize(Throwable failure) {
        String detail = failure.getMessage();
        return detail == null || detail.isBlank()
                ? failure.getClass().getSimpleName() : detail.trim();
    }

    public enum Protocol {
        UCI("uci", "uciok"),
        UCCI("ucci", "ucciok");

        private final String command;
        private final String acknowledgement;

        Protocol(String command, String acknowledgement) {
            this.command = command;
            this.acknowledgement = acknowledgement;
        }

        public String command() {
            return command;
        }
    }

    public enum Status {
        REGISTERED,
        STARTING,
        RUNNING,
        FAILED,
        STOPPED
    }

    public record ResourceBudget(int maxThreads, int maxHashMiB) {
        public ResourceBudget {
            if (maxThreads < 1 || maxThreads > 1_024) {
                throw new IllegalArgumentException("maxThreads must be between 1 and 1024");
            }
            if (maxHashMiB < 1 || maxHashMiB > 1_048_576) {
                throw new IllegalArgumentException("maxHashMiB must be between 1 and 1048576");
            }
        }
    }

    public record EngineSpec(String id,
                             String displayName,
                             Path executable,
                             List<String> arguments,
                             Protocol protocol,
                             int threads,
                             int hashMiB,
                             int multiPv,
                             Duration startupTimeout) {
        public EngineSpec {
            if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
                throw new IllegalArgumentException("invalid engine id");
            }
            if (displayName == null || displayName.isBlank() || displayName.length() > 128) {
                throw new IllegalArgumentException("invalid engine display name");
            }
            Objects.requireNonNull(executable, "executable");
            arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
            if (arguments.size() > 32 || arguments.stream().anyMatch(argument ->
                    argument == null || argument.length() > MAX_COMMAND_LENGTH
                            || argument.indexOf('\0') >= 0)) {
                throw new IllegalArgumentException("invalid engine arguments");
            }
            Objects.requireNonNull(protocol, "protocol");
            if (threads < 1 || threads > 256) {
                throw new IllegalArgumentException("threads must be between 1 and 256");
            }
            if (hashMiB < 1 || hashMiB > 1_048_576) {
                throw new IllegalArgumentException("hashMiB must be between 1 and 1048576");
            }
            if (multiPv < 1 || multiPv > 256) {
                throw new IllegalArgumentException("multiPv must be between 1 and 256");
            }
            Objects.requireNonNull(startupTimeout, "startupTimeout");
            if (startupTimeout.compareTo(Duration.ofMillis(100)) < 0
                    || startupTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
                throw new IllegalArgumentException(
                        "startupTimeout must be between 100 ms and 30 seconds");
            }
        }

        private EngineSpec withExecutable(Path normalizedExecutable) {
            return new EngineSpec(id, displayName, normalizedExecutable, arguments,
                    protocol, threads, hashMiB, multiPv, startupTimeout);
        }
    }

    public record EngineSnapshot(String id,
                                 String displayName,
                                 Path executable,
                                 Protocol protocol,
                                 int threads,
                                 int hashMiB,
                                 int multiPv,
                                 String executableSha256,
                                 Status status,
                                 boolean alive,
                                 Optional<String> lastFailure) {
        public EngineSnapshot {
            Objects.requireNonNull(lastFailure, "lastFailure");
        }
    }

    @FunctionalInterface
    public interface OutputListener {
        /** Implementations must return promptly and offload expensive UI work. */
        void onLine(String engineId, String line);
    }

    private final class EngineSlot {
        private final EngineSpec spec;
        private final String executableSha256;
        private final ArrayBlockingQueue<String> startupLines =
                new ArrayBlockingQueue<>(OUTPUT_QUEUE_CAPACITY);
        private volatile Status status = Status.REGISTERED;
        private volatile String lastFailure;
        private volatile boolean closing;
        private Process process;
        private BufferedReader reader;
        private BufferedWriter writer;

        private EngineSlot(EngineSpec spec, String executableSha256) {
            this.spec = spec;
            this.executableSha256 = executableSha256;
        }

        private void start() throws Exception {
            synchronized (this) {
                if (status != Status.REGISTERED) {
                    return;
                }
                status = Status.STARTING;
            }
            if (!executableSha256.equals(sha256(spec.executable()))) {
                throw new IOException("engine executable changed after registration");
            }
            synchronized (this) {
                if (closing || status == Status.STOPPED) {
                    return;
                }
            }

            List<String> command = new ArrayList<>(spec.arguments().size() + 1);
            command.add(spec.executable().toString());
            command.addAll(spec.arguments());
            Process launched = new ProcessBuilder(command)
                    .directory(spec.executable().getParent().toFile())
                    .redirectErrorStream(true)
                    .start();
            synchronized (this) {
                if (closing || status == Status.STOPPED) {
                    launched.destroyForcibly();
                    return;
                }
                process = launched;
                reader = new BufferedReader(new InputStreamReader(
                        launched.getInputStream(), StandardCharsets.UTF_8));
                writer = new BufferedWriter(new OutputStreamWriter(
                        launched.getOutputStream(), StandardCharsets.UTF_8));
            }
            startReader();
            startExitMonitor();

            long deadline = System.nanoTime() + spec.startupTimeout().toNanos();
            writeInternal(spec.protocol().command);
            await(spec.protocol().acknowledgement, deadline);
            writeOption("Threads", spec.threads());
            writeOption("Hash", spec.hashMiB());
            writeOption("MultiPV", spec.multiPv());
            writeInternal("isready");
            await("readyok", deadline);
            synchronized (this) {
                if (status == Status.FAILED || process == null || !process.isAlive()) {
                    throw new IOException(lastFailure == null
                            ? "engine exited during startup" : lastFailure);
                }
                status = Status.RUNNING;
            }
        }

        private void startReader() {
            Thread.ofVirtual().name("pikadesk-engine-" + spec.id() + "-reader").start(() -> {
                try {
                    String line;
                    while ((line = readBoundedLine()) != null) {
                        if (status == Status.STARTING && !startupLines.offer(line)) {
                            fail("engine output queue limit exceeded");
                            return;
                        }
                        for (OutputListener outputListener : outputListeners) {
                            try {
                                outputListener.onLine(spec.id(), line);
                            } catch (RuntimeException ignored) {
                                // A listener failure must not kill the engine reader.
                            }
                        }
                    }
                } catch (IOException failure) {
                    if (!closing) {
                        fail("engine output failed: " + normalize(failure));
                    }
                }
            });
        }

        private String readBoundedLine() throws IOException {
            StringBuilder line = new StringBuilder(256);
            int value;
            while ((value = reader.read()) != -1) {
                if (value == '\n') {
                    int length = line.length();
                    if (length > 0 && line.charAt(length - 1) == '\r') {
                        line.setLength(length - 1);
                    }
                    return line.toString();
                }
                if (line.length() >= MAX_OUTPUT_LINE_LENGTH) {
                    throw new IOException("engine output line exceeds 16384 characters");
                }
                line.append((char) value);
            }
            return line.isEmpty() ? null : line.toString();
        }

        private void startExitMonitor() {
            Thread.ofVirtual().name("pikadesk-engine-" + spec.id() + "-monitor").start(() -> {
                try {
                    int exitCode = process.waitFor();
                    if (!closing) {
                        fail("engine exited with exit code " + exitCode);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    if (!closing) {
                        fail("engine monitor interrupted");
                    }
                }
            });
        }

        private void writeOption(String name, int value) throws IOException {
            if (spec.protocol() == Protocol.UCI) {
                writeInternal("setoption name " + name + " value " + value);
            } else {
                writeInternal("setoption " + name + " " + value);
            }
        }

        private void await(String expected, long deadlineNanos) throws Exception {
            while (true) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    throw new IOException("startup timeout waiting for " + expected);
                }
                String line = startupLines.poll(
                        Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)),
                        TimeUnit.NANOSECONDS);
                if (expected.equals(line)) {
                    return;
                }
                Process current = process;
                if (line == null && (current == null || !current.isAlive())) {
                    throw new IOException(lastFailure == null
                            ? "engine exited during startup" : lastFailure);
                }
            }
        }

        private synchronized void send(String command) throws IOException {
            validateCommand(command);
            if (status != Status.RUNNING || process == null || !process.isAlive()) {
                throw new IllegalStateException("engine is not running: " + spec.id());
            }
            writeInternal(command);
        }

        private synchronized void writeInternal(String command) throws IOException {
            validateCommand(command);
            if (writer == null) {
                throw new IOException("engine input is unavailable");
            }
            writer.write(command);
            writer.newLine();
            writer.flush();
        }

        private void validateCommand(String command) {
            if (command == null || command.isBlank() || command.length() > MAX_COMMAND_LENGTH
                    || command.indexOf('\r') >= 0 || command.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("invalid engine command");
            }
        }

        private synchronized void fail(String detail) {
            if (closing || status == Status.FAILED || status == Status.STOPPED) {
                return;
            }
            status = Status.FAILED;
            lastFailure = detail == null || detail.isBlank() ? "engine failed" : detail;
            destroyProcess();
        }

        private synchronized EngineSnapshot snapshot() {
            return new EngineSnapshot(
                    spec.id(), spec.displayName(), spec.executable(), spec.protocol(),
                    spec.threads(), spec.hashMiB(), spec.multiPv(), executableSha256,
                    status, process != null && process.isAlive(), Optional.ofNullable(lastFailure));
        }

        private void close() {
            synchronized (this) {
                if (status == Status.STOPPED) {
                    return;
                }
                closing = true;
                if (process != null && process.isAlive() && writer != null) {
                    try {
                        writeInternal("quit");
                    } catch (IOException ignored) {
                    }
                }
            }
            Process current = process;
            if (current != null && current.isAlive()) {
                try {
                    if (!current.waitFor(PROCESS_STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                        current.destroy();
                    }
                    if (current.isAlive()
                            && !current.waitFor(PROCESS_STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                        current.destroyForcibly();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    current.destroyForcibly();
                }
            }
            synchronized (this) {
                closeStreams();
                status = Status.STOPPED;
            }
        }

        private void destroyProcess() {
            Process current = process;
            if (current != null && current.isAlive()) {
                current.destroy();
                try {
                    if (!current.waitFor(PROCESS_STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                        current.destroyForcibly();
                        current.waitFor(PROCESS_STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    current.destroyForcibly();
                }
            }
            closeStreams();
        }

        private void closeStreams() {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException ignored) {
            }
            try {
                if (writer != null) {
                    writer.close();
                }
            } catch (IOException ignored) {
            }
        }
    }

}
