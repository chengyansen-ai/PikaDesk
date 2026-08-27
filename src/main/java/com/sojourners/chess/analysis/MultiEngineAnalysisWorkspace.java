package com.sojourners.chess.analysis;

import com.sojourners.chess.enginee.EngineRegistry;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Generation-safe aggregation for up to five analysis engines. Engine I/O is
 * serialized on a virtual control thread so callers, including JavaFX, never
 * write to process pipes directly.
 */
public final class MultiEngineAnalysisWorkspace implements AutoCloseable {

    private static final int MAX_POSITION_COMMAND_LENGTH = 4_096;
    private static final int MAX_PV_MOVES = 256;

    private final Object lock = new Object();
    private final EnginePort port;
    private final ChangeListener changeListener;
    private final ExecutorService controlExecutor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("pikadesk-analysis-control").factory());
    private final ExecutorService notificationExecutor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("pikadesk-analysis-events").factory());
    private final Map<String, EngineState> states = new LinkedHashMap<>();
    private final List<String> order = new ArrayList<>();
    private final Set<CompletableFuture<?>> pendingOperations =
            ConcurrentHashMap.newKeySet();
    private final AtomicBoolean notificationPending = new AtomicBoolean();
    private final AtomicReference<WorkspaceSnapshot> latestNotification =
            new AtomicReference<>();
    private final AutoCloseable subscription;
    private long generation;
    private AnalysisRequest currentRequest;
    private volatile boolean closed;

    public MultiEngineAnalysisWorkspace(EngineRegistry registry,
                                        ChangeListener changeListener) {
        this(new RegistryPort(registry), changeListener);
    }

    MultiEngineAnalysisWorkspace(EnginePort port,
                                 ChangeListener changeListener) {
        this.port = Objects.requireNonNull(port, "port");
        this.changeListener = Objects.requireNonNull(changeListener, "changeListener");
        synchronized (lock) {
            syncEngines(port.engines());
        }
        this.subscription = port.subscribe(this::onLine);
    }

    public CompletableFuture<Long> analyze(AnalysisRequest request) {
        Objects.requireNonNull(request, "request");
        ensureOpen();
        positionCommand(request);
        return submitOperation(() -> beginGeneration(request));
    }

    public CompletableFuture<Void> setEnabled(String engineId, boolean enabled) {
        ensureOpen();
        requireKnownEngine(engineId);
        return submitOperation(() -> {
            changeEnabled(engineId, enabled);
            return null;
        });
    }

    public void setVisible(String engineId, boolean visible) {
        ensureOpen();
        EngineState state = requireKnownEngine(engineId);
        synchronized (lock) {
            state.visible = visible;
        }
        notifyChanged();
    }

    public void reorder(List<String> engineIds) {
        ensureOpen();
        Objects.requireNonNull(engineIds, "engineIds");
        List<String> requested = List.copyOf(engineIds);
        synchronized (lock) {
            syncEngines(port.engines());
            if (requested.size() != states.size()
                    || new LinkedHashSet<>(requested).size() != requested.size()
                    || !states.keySet().containsAll(requested)) {
                throw new IllegalArgumentException(
                        "engine order must contain every registered engine exactly once");
            }
            order.clear();
            order.addAll(requested);
        }
        notifyChanged();
    }

    public WorkspaceSnapshot snapshot() {
        List<EngineIdentity> identities = port.engines();
        synchronized (lock) {
            syncEngines(identities);
            List<EngineView> engines = new ArrayList<>(order.size());
            for (String id : order) {
                EngineState state = states.get(id);
                List<PvLine> lines = state.lines.values().stream()
                        .sorted(Comparator.comparingInt(PvLine::multiPv))
                        .toList();
                engines.add(new EngineView(
                        id,
                        state.identity.displayName(),
                        state.visible,
                        state.enabled,
                        state.identity.status(),
                        publicActivity(state.phase),
                        lines,
                        Optional.ofNullable(state.bestMove),
                        Optional.ofNullable(state.lastError)
                ));
            }
            return new WorkspaceSnapshot(generation, engines, consensus(engines));
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        try {
            subscription.close();
        } catch (Exception ignored) {
        }
        CancellationException cancellation =
                new CancellationException("analysis workspace closed");
        for (CompletableFuture<?> operation : pendingOperations) {
            operation.completeExceptionally(cancellation);
        }
        controlExecutor.shutdownNow();
        notificationExecutor.shutdownNow();
    }

    private long beginGeneration(AnalysisRequest request) {
        List<String> targets = new ArrayList<>();
        synchronized (lock) {
            ensureOpenLocked();
            syncEngines(port.engines());
            generation++;
            currentRequest = request;
            for (EngineState state : states.values()) {
                state.lines.clear();
                state.bestMove = null;
                state.lastError = null;
                state.pendingGeneration = generation;
                state.pendingRequest = request;
                if (state.enabled && state.identity.status() == EngineRegistry.Status.RUNNING) {
                    state.phase = Phase.WAITING_READY;
                    targets.add(state.identity.id());
                } else {
                    state.phase = state.enabled ? Phase.IDLE : Phase.PAUSED;
                }
            }
        }
        for (String engineId : targets) {
            requestReadyBoundary(engineId, generation);
        }
        notifyChanged();
        return generation;
    }

    private void changeEnabled(String engineId, boolean enabled) {
        AnalysisRequest request;
        long expectedGeneration;
        EngineRegistry.Status status;
        synchronized (lock) {
            ensureOpenLocked();
            EngineState state = state(engineId);
            state.enabled = enabled;
            state.lastError = null;
            status = state.identity.status();
            request = currentRequest;
            expectedGeneration = generation;
            if (!enabled) {
                state.phase = Phase.PAUSED;
                state.pendingRequest = null;
            } else if (request != null && status == EngineRegistry.Status.RUNNING) {
                state.lines.clear();
                state.bestMove = null;
                state.phase = Phase.WAITING_READY;
                state.pendingGeneration = expectedGeneration;
                state.pendingRequest = request;
            } else {
                state.phase = Phase.IDLE;
            }
        }
        if (!enabled) {
            if (status == EngineRegistry.Status.RUNNING) {
                sendOrFail(engineId, "stop", expectedGeneration);
            }
        } else if (request != null && status == EngineRegistry.Status.RUNNING) {
            requestReadyBoundary(engineId, expectedGeneration);
        }
        notifyChanged();
    }

    private void requestReadyBoundary(String engineId, long expectedGeneration) {
        if (!sendOrFail(engineId, "stop", expectedGeneration)) {
            return;
        }
        sendOrFail(engineId, "isready", expectedGeneration);
    }

    private boolean sendOrFail(String engineId,
                               String command,
                               long expectedGeneration) {
        try {
            port.send(engineId, command);
            return true;
        } catch (IOException | RuntimeException failure) {
            synchronized (lock) {
                EngineState state = states.get(engineId);
                if (state != null && state.pendingGeneration == expectedGeneration) {
                    state.phase = Phase.ERROR;
                    state.lastError = normalize(failure);
                }
            }
            return false;
        }
    }

    private void onLine(String engineId, String line) {
        if (line == null) {
            return;
        }
        if ("readyok".equals(line.trim())) {
            long expectedGeneration;
            synchronized (lock) {
                EngineState state = states.get(engineId);
                if (closed || state == null || state.phase != Phase.WAITING_READY) {
                    return;
                }
                state.phase = Phase.STARTING;
                expectedGeneration = state.pendingGeneration;
            }
            submitControl(() -> startAfterBoundary(engineId, expectedGeneration));
            return;
        }

        long expectedGeneration;
        synchronized (lock) {
            EngineState state = states.get(engineId);
            if (closed || state == null || state.phase != Phase.ANALYZING) {
                return;
            }
            expectedGeneration = state.pendingGeneration;
        }
        if (line.startsWith("info ")) {
            Optional<PvLine> parsed = parseInfo(expectedGeneration, line);
            if (parsed.isPresent()) {
                synchronized (lock) {
                    EngineState state = states.get(engineId);
                    if (state != null && state.phase == Phase.ANALYZING
                            && state.pendingGeneration == expectedGeneration) {
                        state.lines.put(parsed.orElseThrow().multiPv(), parsed.orElseThrow());
                    } else {
                        return;
                    }
                }
                notifyChanged();
            }
        } else if (line.startsWith("bestmove ")) {
            String[] tokens = line.trim().split("\\s+");
            if (tokens.length >= 2 && validMove(tokens[1])) {
                synchronized (lock) {
                    EngineState state = states.get(engineId);
                    if (state != null && state.phase == Phase.ANALYZING
                            && state.pendingGeneration == expectedGeneration) {
                        state.bestMove = tokens[1];
                        state.phase = Phase.IDLE;
                    } else {
                        return;
                    }
                }
                notifyChanged();
            }
        }
    }

    private void startAfterBoundary(String engineId, long expectedGeneration) {
        AnalysisRequest request;
        synchronized (lock) {
            if (closed) {
                return;
            }
            EngineState state = states.get(engineId);
            if (state == null || !state.enabled || state.phase != Phase.STARTING
                    || state.pendingGeneration != expectedGeneration) {
                return;
            }
            request = state.pendingRequest;
            if (request == null) {
                state.phase = Phase.IDLE;
                return;
            }
            state.phase = Phase.ANALYZING;
        }
        if (!sendOrFail(engineId, positionCommand(request), expectedGeneration)) {
            notifyChanged();
            return;
        }
        sendOrFail(engineId, request.limit().command(), expectedGeneration);
        notifyChanged();
    }

    private Optional<PvLine> parseInfo(long expectedGeneration, String line) {
        try {
            String[] tokens = line.trim().split("\\s+");
            int depth = -1;
            int multiPv = 1;
            Integer scoreCp = null;
            Integer mate = null;
            long timeMillis = 0;
            long nodesPerSecond = 0;
            List<String> moves = List.of();
            for (int index = 1; index < tokens.length; index++) {
                switch (tokens[index]) {
                    case "depth" -> depth = parseInt(tokens, ++index);
                    case "multipv" -> multiPv = parseInt(tokens, ++index);
                    case "time" -> timeMillis = parseLong(tokens, ++index);
                    case "nps" -> nodesPerSecond = parseLong(tokens, ++index);
                    case "score" -> {
                        index++;
                        if (index >= tokens.length) {
                            return Optional.empty();
                        }
                        if ("cp".equals(tokens[index])) {
                            scoreCp = parseInt(tokens, ++index);
                        } else if ("mate".equals(tokens[index])) {
                            mate = parseInt(tokens, ++index);
                        } else {
                            scoreCp = Integer.parseInt(tokens[index]);
                        }
                    }
                    case "pv" -> {
                        List<String> parsedMoves = new ArrayList<>();
                        for (int moveIndex = index + 1;
                             moveIndex < tokens.length && parsedMoves.size() < MAX_PV_MOVES;
                             moveIndex++) {
                            if (!validMove(tokens[moveIndex])) {
                                return Optional.empty();
                            }
                            parsedMoves.add(tokens[moveIndex]);
                        }
                        moves = List.copyOf(parsedMoves);
                        index = tokens.length;
                    }
                    default -> {
                    }
                }
            }
            if (depth < 0 || multiPv < 1 || multiPv > 256
                    || timeMillis < 0 || nodesPerSecond < 0 || moves.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new PvLine(expectedGeneration, multiPv, depth,
                    scoreCp, mate, timeMillis, nodesPerSecond, moves));
        } catch (IllegalArgumentException failure) {
            return Optional.empty();
        }
    }

    private int parseInt(String[] tokens, int index) {
        if (index >= tokens.length) {
            throw new IllegalArgumentException("missing integer");
        }
        return Integer.parseInt(tokens[index]);
    }

    private long parseLong(String[] tokens, int index) {
        if (index >= tokens.length) {
            throw new IllegalArgumentException("missing long");
        }
        return Long.parseLong(tokens[index]);
    }

    private String positionCommand(AnalysisRequest request) {
        StringBuilder command = new StringBuilder("position fen ").append(request.fen());
        if (!request.moves().isEmpty()) {
            command.append(" moves");
            for (String move : request.moves()) {
                command.append(' ').append(move);
            }
        }
        if (command.length() > MAX_POSITION_COMMAND_LENGTH) {
            throw new IllegalArgumentException("position command exceeds 4096 characters");
        }
        return command.toString();
    }

    private Consensus consensus(List<EngineView> engines) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> engineMoves = new LinkedHashMap<>();
        for (EngineView engine : engines) {
            if (!engine.visible() || !engine.enabled() || engine.principalVariations().isEmpty()) {
                continue;
            }
            PvLine first = engine.principalVariations().stream()
                    .filter(line -> line.multiPv() == 1)
                    .findFirst().orElse(null);
            if (first == null || first.moves().isEmpty()) {
                continue;
            }
            String move = first.moves().getFirst();
            engineMoves.put(engine.id(), move);
            counts.merge(move, 1, Integer::sum);
        }
        String selected = null;
        int agreeing = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > agreeing) {
                selected = entry.getKey();
                agreeing = entry.getValue();
            }
        }
        List<String> divergent = new ArrayList<>();
        if (selected != null) {
            for (Map.Entry<String, String> entry : engineMoves.entrySet()) {
                if (!selected.equals(entry.getValue())) {
                    divergent.add(entry.getKey());
                }
            }
        }
        return new Consensus(Optional.ofNullable(selected), agreeing, engineMoves.size(),
                engineMoves.size() > 1 && agreeing == engineMoves.size(), divergent);
    }

    private EngineActivity publicActivity(Phase phase) {
        return switch (phase) {
            case WAITING_READY, STARTING -> EngineActivity.WAITING_READY;
            case ANALYZING -> EngineActivity.ANALYZING;
            case PAUSED -> EngineActivity.PAUSED;
            case ERROR -> EngineActivity.ERROR;
            default -> EngineActivity.IDLE;
        };
    }

    private void syncEngines(List<EngineIdentity> identities) {
        for (EngineIdentity identity : identities) {
            EngineState state = states.get(identity.id());
            if (state == null) {
                states.put(identity.id(), new EngineState(identity));
                order.add(identity.id());
            } else {
                state.identity = identity;
            }
        }
    }

    private EngineState requireKnownEngine(String engineId) {
        Objects.requireNonNull(engineId, "engineId");
        synchronized (lock) {
            syncEngines(port.engines());
            return state(engineId);
        }
    }

    private EngineState state(String engineId) {
        EngineState state = states.get(engineId);
        if (state == null) {
            throw new IllegalArgumentException("unknown engine id: " + engineId);
        }
        return state;
    }

    private void ensureOpen() {
        synchronized (lock) {
            ensureOpenLocked();
        }
    }

    private void ensureOpenLocked() {
        if (closed) {
            throw new IllegalStateException("analysis workspace is closed");
        }
    }

    private void submitControl(Runnable command) {
        try {
            controlExecutor.execute(command);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private <T> CompletableFuture<T> submitOperation(Supplier<T> operation) {
        CompletableFuture<T> future = new CompletableFuture<>();
        pendingOperations.add(future);
        try {
            controlExecutor.execute(() -> {
                try {
                    if (closed) {
                        future.completeExceptionally(
                                new CancellationException("analysis workspace closed"));
                    } else {
                        future.complete(operation.get());
                    }
                } catch (Throwable failure) {
                    future.completeExceptionally(failure);
                } finally {
                    pendingOperations.remove(future);
                }
            });
        } catch (RejectedExecutionException rejected) {
            pendingOperations.remove(future);
            future.completeExceptionally(rejected);
        }
        return future;
    }

    private void notifyChanged() {
        if (closed) {
            return;
        }
        latestNotification.set(snapshot());
        if (!notificationPending.compareAndSet(false, true)) {
            return;
        }
        try {
            notificationExecutor.execute(this::drainNotifications);
        } catch (RejectedExecutionException ignored) {
            notificationPending.set(false);
        }
    }

    private void drainNotifications() {
        while (!closed) {
            WorkspaceSnapshot current = latestNotification.getAndSet(null);
            if (current != null) {
                try {
                    changeListener.onChanged(current);
                } catch (RuntimeException ignored) {
                }
            }
            notificationPending.set(false);
            if (latestNotification.get() == null
                    || !notificationPending.compareAndSet(false, true)) {
                return;
            }
        }
    }

    private String normalize(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message.trim();
    }

    private static boolean validMove(String move) {
        return move != null && move.matches("[a-i][0-9][a-i][0-9]");
    }

    public enum SearchMode {
        DEPTH,
        MOVETIME,
        NODES,
        INFINITE
    }

    public enum EngineActivity {
        IDLE,
        WAITING_READY,
        ANALYZING,
        PAUSED,
        ERROR
    }

    public record SearchLimit(SearchMode mode, long value) {
        public SearchLimit {
            Objects.requireNonNull(mode, "mode");
            if (mode == SearchMode.INFINITE && value != 0) {
                throw new IllegalArgumentException("infinite search value must be zero");
            }
            if (mode != SearchMode.INFINITE && value < 1) {
                throw new IllegalArgumentException("search value must be positive");
            }
            if (mode == SearchMode.DEPTH && value > 256) {
                throw new IllegalArgumentException("depth exceeds 256");
            }
            if (mode == SearchMode.MOVETIME && value > Duration.ofHours(1).toMillis()) {
                throw new IllegalArgumentException("movetime exceeds one hour");
            }
            if (mode == SearchMode.NODES && value > 1_000_000_000_000L) {
                throw new IllegalArgumentException("nodes exceeds limit");
            }
        }

        private String command() {
            return switch (mode) {
                case DEPTH -> "go depth " + value;
                case MOVETIME -> "go movetime " + value;
                case NODES -> "go nodes " + value;
                case INFINITE -> "go infinite";
            };
        }
    }

    public record AnalysisRequest(String fen,
                                  List<String> moves,
                                  SearchLimit limit) {
        public AnalysisRequest {
            if (fen == null || fen.isBlank() || fen.length() > 512
                    || fen.indexOf('\r') >= 0 || fen.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("invalid FEN");
            }
            moves = List.copyOf(Objects.requireNonNull(moves, "moves"));
            if (moves.size() > 512 || moves.stream().anyMatch(move -> !validMove(move))) {
                throw new IllegalArgumentException("invalid move list");
            }
            Objects.requireNonNull(limit, "limit");
        }
    }

    public record PvLine(long generation,
                         int multiPv,
                         int depth,
                         Integer scoreCp,
                         Integer mate,
                         long timeMillis,
                         long nodesPerSecond,
                         List<String> moves) {
        public PvLine {
            moves = List.copyOf(Objects.requireNonNull(moves, "moves"));
        }
    }

    public record EngineIdentity(String id,
                                 String displayName,
                                 EngineRegistry.Status status) {
        public EngineIdentity {
            if (id == null || id.isBlank() || displayName == null || displayName.isBlank()) {
                throw new IllegalArgumentException("engine identity is incomplete");
            }
            Objects.requireNonNull(status, "status");
        }
    }

    public record EngineView(String id,
                             String displayName,
                             boolean visible,
                             boolean enabled,
                             EngineRegistry.Status registryStatus,
                             EngineActivity activity,
                             List<PvLine> principalVariations,
                             Optional<String> bestMove,
                             Optional<String> lastError) {
        public EngineView {
            if (id == null || id.isBlank() || displayName == null || displayName.isBlank()) {
                throw new IllegalArgumentException("engine view identity is incomplete");
            }
            Objects.requireNonNull(registryStatus, "registryStatus");
            Objects.requireNonNull(activity, "activity");
            principalVariations = List.copyOf(
                    Objects.requireNonNull(principalVariations, "principalVariations"));
            Objects.requireNonNull(bestMove, "bestMove");
            Objects.requireNonNull(lastError, "lastError");
        }
    }

    public record Consensus(Optional<String> move,
                            int agreeing,
                            int compared,
                            boolean unanimous,
                            List<String> divergentEngineIds) {
        public Consensus {
            Objects.requireNonNull(move, "move");
            if (agreeing < 0 || compared < 0 || agreeing > compared) {
                throw new IllegalArgumentException("invalid consensus counts");
            }
            divergentEngineIds = List.copyOf(
                    Objects.requireNonNull(divergentEngineIds, "divergentEngineIds"));
        }
    }

    public record WorkspaceSnapshot(long generation,
                                    List<EngineView> engines,
                                    Consensus consensus) {
        public WorkspaceSnapshot {
            engines = List.copyOf(Objects.requireNonNull(engines, "engines"));
            Objects.requireNonNull(consensus, "consensus");
        }

        public EngineView engine(String engineId) {
            return engines.stream().filter(engine -> engine.id().equals(engineId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "unknown engine id: " + engineId));
        }
    }

    @FunctionalInterface
    public interface ChangeListener {
        void onChanged(WorkspaceSnapshot snapshot);
    }

    @FunctionalInterface
    public interface LineListener {
        void onLine(String engineId, String line);
    }

    public interface EnginePort {
        List<EngineIdentity> engines();

        void send(String engineId, String command) throws IOException;

        AutoCloseable subscribe(LineListener listener);
    }

    private enum Phase {
        IDLE,
        WAITING_READY,
        STARTING,
        ANALYZING,
        PAUSED,
        ERROR
    }

    private static final class EngineState {
        private EngineIdentity identity;
        private boolean visible = true;
        private boolean enabled = true;
        private Phase phase = Phase.IDLE;
        private long pendingGeneration;
        private AnalysisRequest pendingRequest;
        private final Map<Integer, PvLine> lines = new LinkedHashMap<>();
        private String bestMove;
        private String lastError;

        private EngineState(EngineIdentity identity) {
            this.identity = identity;
        }
    }

    private static final class RegistryPort implements EnginePort {
        private final EngineRegistry registry;

        private RegistryPort(EngineRegistry registry) {
            this.registry = Objects.requireNonNull(registry, "registry");
        }

        @Override
        public List<EngineIdentity> engines() {
            return registry.snapshots().stream()
                    .map(snapshot -> new EngineIdentity(
                            snapshot.id(), snapshot.displayName(), snapshot.status()))
                    .toList();
        }

        @Override
        public void send(String engineId, String command) throws IOException {
            registry.send(engineId, command);
        }

        @Override
        public AutoCloseable subscribe(LineListener listener) {
            return registry.addOutputListener(listener::onLine);
        }
    }
}
