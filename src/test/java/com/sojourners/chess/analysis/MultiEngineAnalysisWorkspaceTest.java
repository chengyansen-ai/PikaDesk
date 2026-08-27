package com.sojourners.chess.analysis;

import com.sojourners.chess.enginee.EngineRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MultiEngineAnalysisWorkspaceTest {

    @Test
    void waitsForReadyBoundaryAndNeverMixesThePreviousPosition() throws Exception {
        FakePort port = new FakePort("alpha", "beta");
        try (MultiEngineAnalysisWorkspace workspace = workspace(port)) {
            long firstGeneration = workspace.analyze(request("w", 12)).get(2, TimeUnit.SECONDS);
            awaitCommands(port, "alpha", 2);
            assertEquals(List.of("stop", "isready"), port.commands("alpha"));

            port.emit("alpha", "info depth 8 score cp 10 pv a0a1");
            assertTrue(workspace.snapshot().engine("alpha").principalVariations().isEmpty());

            port.emit("alpha", "readyok");
            awaitCommands(port, "alpha", 4);
            assertEquals("position fen w", port.commands("alpha").get(2));
            assertEquals("go depth 12", port.commands("alpha").get(3));
            port.emit("alpha", "info depth 11 multipv 2 score cp 18 pv b0c2");
            port.emit("alpha", "info depth 12 multipv 1 score cp 34 time 50 nps 9000 pv a0a1 a9a8");
            awaitPv(workspace, "alpha", 2);
            assertAll(
                    () -> assertEquals(List.of(1, 2), workspace.snapshot().engine("alpha")
                            .principalVariations().stream()
                            .map(MultiEngineAnalysisWorkspace.PvLine::multiPv).toList()),
                    () -> assertTrue(workspace.snapshot().engine("alpha")
                            .principalVariations().stream()
                            .allMatch(line -> line.generation() == firstGeneration))
            );

            long secondGeneration = workspace.analyze(request("b", 16)).get(2, TimeUnit.SECONDS);
            assertTrue(secondGeneration > firstGeneration);
            assertTrue(workspace.snapshot().engine("alpha").principalVariations().isEmpty());

            port.emit("alpha", "info depth 99 score cp 999 pv i9i8");
            assertTrue(workspace.snapshot().engine("alpha").principalVariations().isEmpty());
            port.emit("alpha", "readyok");
            awaitCommands(port, "alpha", 8);
            port.emit("alpha", "info depth 16 score mate 3 pv b0c2");
            awaitPv(workspace, "alpha", 1);

            MultiEngineAnalysisWorkspace.PvLine line = workspace.snapshot()
                    .engine("alpha").principalVariations().getFirst();
            assertAll(
                    () -> assertEquals(secondGeneration, line.generation()),
                    () -> assertEquals(16, line.depth()),
                    () -> assertEquals(3, line.mate()),
                    () -> assertEquals(List.of("b0c2"), line.moves())
            );
        }
    }

    @Test
    void aggregatesConsensusWhileRespectingOrderAndVisibility() throws Exception {
        FakePort port = new FakePort("alpha", "beta", "gamma");
        try (MultiEngineAnalysisWorkspace workspace = workspace(port)) {
            workspace.analyze(request("w", 18)).get(2, TimeUnit.SECONDS);
            readyAll(port);
            port.emit("alpha", "info depth 18 score cp 25 pv a0a1");
            port.emit("beta", "info depth 17 score cp 19 pv a0a1");
            port.emit("gamma", "info depth 20 score cp 31 pv b0c2");
            awaitPv(workspace, "gamma", 1);

            MultiEngineAnalysisWorkspace.Consensus consensus = workspace.snapshot().consensus();
            assertAll(
                    () -> assertEquals("a0a1", consensus.move().orElseThrow()),
                    () -> assertEquals(2, consensus.agreeing()),
                    () -> assertEquals(3, consensus.compared()),
                    () -> assertEquals(List.of("gamma"), consensus.divergentEngineIds())
            );

            workspace.reorder(List.of("gamma", "alpha", "beta"));
            workspace.setVisible("beta", false);
            MultiEngineAnalysisWorkspace.WorkspaceSnapshot arranged = workspace.snapshot();

            assertAll(
                    () -> assertEquals(List.of("gamma", "alpha", "beta"), arranged.engines()
                            .stream().map(MultiEngineAnalysisWorkspace.EngineView::id).toList()),
                    () -> assertFalse(arranged.engine("beta").visible()),
                    () -> assertEquals(2, arranged.consensus().compared())
            );
        }
    }

    @Test
    void oneEngineCanPauseAndResumeWithoutChangingItsPeers() throws Exception {
        FakePort port = new FakePort("alpha", "beta");
        try (MultiEngineAnalysisWorkspace workspace = workspace(port)) {
            workspace.analyze(request("w", 10)).get(2, TimeUnit.SECONDS);
            readyAll(port);
            int alphaCommands = port.commands("alpha").size();
            int betaCommands = port.commands("beta").size();

            workspace.setEnabled("beta", false).get(2, TimeUnit.SECONDS);
            assertAll(
                    () -> assertFalse(workspace.snapshot().engine("beta").enabled()),
                    () -> assertEquals(alphaCommands, port.commands("alpha").size()),
                    () -> assertEquals(betaCommands + 1, port.commands("beta").size()),
                    () -> assertEquals("stop", port.commands("beta").getLast())
            );

            workspace.analyze(request("b", 14)).get(2, TimeUnit.SECONDS);
            assertEquals(alphaCommands + 2, port.commands("alpha").size());
            assertEquals(betaCommands + 1, port.commands("beta").size());

            workspace.setEnabled("beta", true).get(2, TimeUnit.SECONDS);
            assertTrue(workspace.snapshot().engine("beta").enabled());
            assertEquals(List.of("stop", "isready"),
                    port.commands("beta").subList(betaCommands + 1, betaCommands + 3));
            port.emit("beta", "readyok");
            awaitCommands(port, "beta", betaCommands + 5);
            assertEquals("go depth 14", port.commands("beta").getLast());
        }
    }

    @Test
    void analyzeReturnsImmediatelyWhenAnEnginePipeIsSlow() throws Exception {
        FakePort port = new FakePort("slow");
        CountDownLatch sendEntered = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        port.blockSends(sendEntered, releaseSend);
        try (MultiEngineAnalysisWorkspace workspace = workspace(port)) {
            long started = System.nanoTime();
            CompletableFuture<Long> future = workspace.analyze(request("w", 20));
            long callMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

            assertAll(
                    () -> assertTrue(callMillis < 100, "analyze call took " + callMillis + " ms"),
                    () -> assertTrue(sendEntered.await(2, TimeUnit.SECONDS)),
                    () -> assertFalse(future.isDone())
            );
            releaseSend.countDown();
            assertTrue(future.get(2, TimeUnit.SECONDS) > 0);
        }
    }

    @Test
    void slowUiListenerReceivesTheLatestCoalescedSnapshot() throws Exception {
        FakePort port = new FakePort("alpha");
        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        AtomicBoolean blockFirst = new AtomicBoolean(true);
        List<MultiEngineAnalysisWorkspace.WorkspaceSnapshot> delivered =
                new CopyOnWriteArrayList<>();
        try (MultiEngineAnalysisWorkspace workspace = new MultiEngineAnalysisWorkspace(
                port, snapshot -> {
                    if (blockFirst.compareAndSet(true, false)) {
                        listenerEntered.countDown();
                        try {
                            releaseListener.await(3, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    delivered.add(snapshot);
                })) {
            workspace.analyze(request("w", 20)).get(2, TimeUnit.SECONDS);
            assertTrue(listenerEntered.await(2, TimeUnit.SECONDS));
            port.emit("alpha", "readyok");
            awaitCommands(port, "alpha", 4);
            for (int depth = 1; depth <= 100; depth++) {
                port.emit("alpha", "info depth " + depth + " score cp " + depth
                        + " pv a0a1");
            }
            releaseListener.countDown();

            await(() -> !delivered.isEmpty()
                            && !delivered.getLast().engine("alpha").principalVariations().isEmpty()
                            && delivered.getLast().engine("alpha").principalVariations()
                            .getFirst().depth() == 100,
                    "latest coalesced snapshot was not delivered");
            assertTrue(delivered.size() <= 3,
                    "expected coalescing, delivered " + delivered.size() + " snapshots");
        }
    }

    @Test
    void closeCompletesAnOperationBlockedOnAnEnginePipe() throws Exception {
        FakePort port = new FakePort("slow");
        CountDownLatch sendEntered = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        port.blockSends(sendEntered, neverReleased);
        MultiEngineAnalysisWorkspace workspace = workspace(port);
        CompletableFuture<Long> pending = workspace.analyze(request("w", 20));
        assertTrue(sendEntered.await(2, TimeUnit.SECONDS));

        workspace.close();

        assertAll(
                () -> assertTrue(pending.isCompletedExceptionally()),
                () -> org.junit.jupiter.api.Assertions.assertThrows(
                        java.util.concurrent.CancellationException.class, pending::join)
        );
    }

    private MultiEngineAnalysisWorkspace workspace(FakePort port) {
        return new MultiEngineAnalysisWorkspace(port, snapshot -> { });
    }

    private MultiEngineAnalysisWorkspace.AnalysisRequest request(String side, int depth) {
        return new MultiEngineAnalysisWorkspace.AnalysisRequest(
                side,
                List.of(),
                new MultiEngineAnalysisWorkspace.SearchLimit(
                        MultiEngineAnalysisWorkspace.SearchMode.DEPTH, depth));
    }

    private void readyAll(FakePort port) throws Exception {
        for (String id : port.ids()) {
            awaitCommands(port, id, 2);
            port.emit(id, "readyok");
            awaitCommands(port, id, 4);
        }
    }

    private void awaitCommands(FakePort port, String id, int count) throws Exception {
        await(() -> port.commands(id).size() >= count,
                "expected " + count + " commands for " + id + ", got " + port.commands(id));
    }

    private void awaitPv(MultiEngineAnalysisWorkspace workspace,
                         String id,
                         int count) throws Exception {
        await(() -> workspace.snapshot().engine(id).principalVariations().size() >= count,
                "expected principal variation for " + id);
    }

    private void await(Check check, String detail) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (check.ok()) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue(check.ok(), detail);
    }

    @FunctionalInterface
    private interface Check {
        boolean ok();
    }

    private static final class FakePort implements MultiEngineAnalysisWorkspace.EnginePort {
        private final List<MultiEngineAnalysisWorkspace.EngineIdentity> engines;
        private final Map<String, CopyOnWriteArrayList<String>> commands =
                new java.util.concurrent.ConcurrentHashMap<>();
        private volatile MultiEngineAnalysisWorkspace.LineListener listener = (id, line) -> { };
        private volatile CountDownLatch sendEntered;
        private volatile CountDownLatch releaseSend;

        private FakePort(String... ids) {
            engines = java.util.Arrays.stream(ids)
                    .map(id -> new MultiEngineAnalysisWorkspace.EngineIdentity(
                            id, "Fake " + id, EngineRegistry.Status.RUNNING))
                    .toList();
            for (String id : ids) {
                commands.put(id, new CopyOnWriteArrayList<>());
            }
        }

        @Override
        public List<MultiEngineAnalysisWorkspace.EngineIdentity> engines() {
            return engines;
        }

        @Override
        public void send(String engineId, String command) throws IOException {
            CountDownLatch entered = sendEntered;
            CountDownLatch release = releaseSend;
            if (entered != null && release != null) {
                entered.countDown();
                try {
                    if (!release.await(3, TimeUnit.SECONDS)) {
                        throw new IOException("test send remained blocked");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("test send interrupted", interrupted);
                }
            }
            commands.get(engineId).add(command);
        }

        @Override
        public AutoCloseable subscribe(MultiEngineAnalysisWorkspace.LineListener listener) {
            this.listener = listener;
            return () -> this.listener = (id, line) -> { };
        }

        private void emit(String engineId, String line) {
            listener.onLine(engineId, line);
        }

        private List<String> commands(String engineId) {
            return new ArrayList<>(commands.get(engineId));
        }

        private List<String> ids() {
            return engines.stream().map(MultiEngineAnalysisWorkspace.EngineIdentity::id).toList();
        }

        private void blockSends(CountDownLatch entered, CountDownLatch release) {
            this.sendEntered = entered;
            this.releaseSend = release;
        }
    }
}
