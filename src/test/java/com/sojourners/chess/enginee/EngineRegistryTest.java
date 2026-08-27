package com.sojourners.chess.enginee;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
final class EngineRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void startsFiveProtocolIsolatedEnginesAndKeepsFourAliveAfterOneCrashes() throws Exception {
        Path script = writeScript("healthy-engine.ps1", healthyEngineScript());
        Map<String, List<String>> output = new ConcurrentHashMap<>();
        EngineRegistry registry = new EngineRegistry(
                new EngineRegistry.ResourceBudget(15, 640),
                (engineId, line) -> output
                        .computeIfAbsent(engineId, ignored -> new CopyOnWriteArrayList<>())
                        .add(line));
        List<String> secondaryOutput = new CopyOnWriteArrayList<>();
        AutoCloseable secondarySubscription = registry.addOutputListener(
                (engineId, line) -> secondaryOutput.add(engineId + ":" + line));
        List<EngineRegistry.EngineSpec> specs = new ArrayList<>();
        for (int index = 1; index <= 5; index++) {
            EngineRegistry.Protocol protocol = index % 2 == 0
                    ? EngineRegistry.Protocol.UCCI : EngineRegistry.Protocol.UCI;
            specs.add(spec("engine-" + index, script, protocol,
                    index, index * 32, index, Duration.ofSeconds(4)));
        }

        try (registry) {
            registry.registerAll(specs);
            List<EngineRegistry.EngineSnapshot> started = assertTimeoutPreemptively(
                    Duration.ofSeconds(10), registry::startAll);

            assertAll(
                    () -> assertEquals(5, started.size()),
                    () -> assertTrue(started.stream().allMatch(snapshot ->
                            snapshot.status() == EngineRegistry.Status.RUNNING)),
                    () -> assertEquals(15, registry.allocatedThreads()),
                    () -> assertEquals(480, registry.allocatedHashMiB()),
                    () -> assertTrue(started.stream().allMatch(snapshot ->
                            snapshot.executableSha256().matches("[0-9a-f]{64}")))
            );

            assertTrue(output.get("engine-1").contains(
                    "seen:engine-1:setoption name Threads value 1"));
            assertTrue(output.get("engine-2").contains(
                    "seen:engine-2:setoption Threads 2"));
            assertTrue(output.get("engine-5").contains(
                    "seen:engine-5:setoption name MultiPV value 5"));
            assertTrue(secondaryOutput.stream().anyMatch(line ->
                    line.equals("engine-1:readyok")));
            assertThrows(IllegalArgumentException.class,
                    () -> registry.send("engine-1", "go infinite\nquit"));

            int secondaryLines = secondaryOutput.size();
            secondarySubscription.close();
            registry.send("engine-1", "listener-check");
            awaitOutput(output, "engine-1", "seen:engine-1:listener-check");
            assertEquals(secondaryLines, secondaryOutput.size());

            registry.send("engine-3", "crash");
            awaitStatus(registry, "engine-3", EngineRegistry.Status.FAILED);

            assertAll(
                    () -> assertTrue(registry.snapshot("engine-3")
                            .lastFailure().orElseThrow().contains("exit code 17")),
                    () -> assertTrue(List.of("engine-1", "engine-2", "engine-4", "engine-5")
                            .stream().allMatch(id -> registry.snapshot(id).status()
                                    == EngineRegistry.Status.RUNNING)),
                    () -> assertFalse(registry.snapshot("engine-3").alive())
            );

            registry.close();
            assertTrue(registry.snapshots().stream().allMatch(snapshot ->
                    snapshot.status() == EngineRegistry.Status.STOPPED && !snapshot.alive()));
        }
    }

    @Test
    void aSilentEngineTimesOutWithoutStoppingAHealthyPeer() throws Exception {
        Path healthy = writeScript("healthy-peer.ps1", healthyEngineScript());
        Path silent = writeScript("silent-engine.ps1", "Start-Sleep -Seconds 30\n");
        EngineRegistry registry = new EngineRegistry(
                new EngineRegistry.ResourceBudget(4, 256), (id, line) -> { });

        try (registry) {
            registry.register(spec("healthy", healthy, EngineRegistry.Protocol.UCI,
                    1, 64, 1, Duration.ofSeconds(3)));
            registry.register(spec("silent", silent, EngineRegistry.Protocol.UCCI,
                    1, 64, 1, Duration.ofMillis(300)));

            assertTimeoutPreemptively(Duration.ofSeconds(6), registry::startAll);

            assertAll(
                    () -> assertEquals(EngineRegistry.Status.RUNNING,
                            registry.snapshot("healthy").status()),
                    () -> assertEquals(EngineRegistry.Status.FAILED,
                            registry.snapshot("silent").status()),
                    () -> assertTrue(registry.snapshot("silent").lastFailure()
                            .orElseThrow().contains("startup timeout")),
                    () -> assertFalse(registry.snapshot("silent").alive())
            );
        }
    }

    @Test
    void rejectsDuplicateSixthAndOverBudgetRegistrationsBeforeStartingProcesses() throws Exception {
        Path script = writeScript("unused-engine.ps1", healthyEngineScript());
        EngineRegistry registry = new EngineRegistry(
                new EngineRegistry.ResourceBudget(5, 320), (id, line) -> { });

        try (registry) {
            for (int index = 1; index <= 5; index++) {
                registry.register(spec("slot-" + index, script, EngineRegistry.Protocol.UCI,
                        1, 64, 1, Duration.ofSeconds(1)));
            }

            assertAll(
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> registry.register(spec("slot-1", script,
                                    EngineRegistry.Protocol.UCI, 1, 64, 1,
                                    Duration.ofSeconds(1)))),
                    () -> assertThrows(IllegalStateException.class,
                            () -> registry.register(spec("slot-6", script,
                                    EngineRegistry.Protocol.UCI, 1, 64, 1,
                                    Duration.ofSeconds(1)))),
                    () -> assertEquals(5, registry.snapshots().size()),
                    () -> assertTrue(registry.snapshots().stream()
                            .noneMatch(EngineRegistry.EngineSnapshot::alive))
            );
        }

        EngineRegistry budgeted = new EngineRegistry(
                new EngineRegistry.ResourceBudget(2, 128), (id, line) -> { });
        try (budgeted) {
            budgeted.register(spec("first", script, EngineRegistry.Protocol.UCI,
                    1, 64, 1, Duration.ofSeconds(1)));
            assertThrows(IllegalArgumentException.class,
                    () -> budgeted.register(spec("too-large", script,
                            EngineRegistry.Protocol.UCI, 2, 96, 1,
                            Duration.ofSeconds(1))));
            assertAll(
                    () -> assertEquals(1, budgeted.snapshots().size()),
                    () -> assertEquals(1, budgeted.allocatedThreads()),
                    () -> assertEquals(64, budgeted.allocatedHashMiB())
            );
        }
    }

    @Test
    void refusesToLaunchAnExecutableChangedAfterRegistration() throws Exception {
        Path executable = writeScript("mutable-engine.exe", "original\n");
        EngineRegistry registry = new EngineRegistry(
                new EngineRegistry.ResourceBudget(1, 64), (id, line) -> { });
        EngineRegistry.EngineSpec spec = new EngineRegistry.EngineSpec(
                "mutable", "Mutable fake", executable, List.of(),
                EngineRegistry.Protocol.UCI, 1, 64, 1, Duration.ofSeconds(1));

        try (registry) {
            String registeredHash = registry.register(spec).executableSha256();
            Files.writeString(executable, "changed\n", StandardCharsets.US_ASCII);

            registry.startAll();
            EngineRegistry.EngineSnapshot snapshot = registry.snapshot("mutable");

            assertAll(
                    () -> assertTrue(registeredHash.matches("[0-9a-f]{64}")),
                    () -> assertEquals(EngineRegistry.Status.FAILED, snapshot.status()),
                    () -> assertFalse(snapshot.alive()),
                    () -> assertTrue(snapshot.lastFailure().orElseThrow()
                            .contains("changed after registration"))
            );
        }
    }

    @Test
    void concurrentCloseDuringStartupCannotLeaveAProcessRunning() throws Exception {
        Path silent = writeScript("closing-engine.ps1", "Start-Sleep -Seconds 30\n");
        EngineRegistry registry = new EngineRegistry(
                new EngineRegistry.ResourceBudget(1, 64), (id, line) -> { });
        registry.register(spec("closing", silent, EngineRegistry.Protocol.UCI,
                1, 64, 1, Duration.ofSeconds(10)));

        Thread starter = Thread.ofVirtual().start(() -> {
            try {
                registry.startAll();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        awaitStatus(registry, "closing", EngineRegistry.Status.STARTING);
        registry.close();
        starter.join(Duration.ofSeconds(3));

        assertAll(
                () -> assertFalse(starter.isAlive()),
                () -> assertEquals(EngineRegistry.Status.STOPPED,
                        registry.snapshot("closing").status()),
                () -> assertFalse(registry.snapshot("closing").alive())
        );
    }

    @Test
    void oversizedEngineOutputFailsOnlyThatSlot() throws Exception {
        Path noisy = writeScript("noisy-engine.ps1", """
                [Console]::Out.WriteLine((('x' * 20000) -join ''))
                [Console]::Out.Flush()
                Start-Sleep -Seconds 30
                """);
        EngineRegistry registry = new EngineRegistry(
                new EngineRegistry.ResourceBudget(1, 64), (id, line) -> { });

        try (registry) {
            registry.register(spec("noisy", noisy, EngineRegistry.Protocol.UCI,
                    1, 64, 1, Duration.ofSeconds(3)));
            registry.startAll();

            EngineRegistry.EngineSnapshot snapshot = registry.snapshot("noisy");
            assertAll(
                    () -> assertEquals(EngineRegistry.Status.FAILED, snapshot.status()),
                    () -> assertFalse(snapshot.alive()),
                    () -> assertTrue(snapshot.lastFailure().orElseThrow()
                            .contains("output line exceeds 16384"),
                            snapshot.lastFailure().orElse("missing failure"))
            );
        }
    }

    private EngineRegistry.EngineSpec spec(String id,
                                           Path script,
                                           EngineRegistry.Protocol protocol,
                                           int threads,
                                           int hashMiB,
                                           int multiPv,
                                           Duration timeout) {
        return new EngineRegistry.EngineSpec(
                id,
                "Fake " + id,
                powershellExecutable(),
                List.of("-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                        "-File", script.toString(), id, protocol.command()),
                protocol,
                threads,
                hashMiB,
                multiPv,
                timeout
        );
    }

    private Path powershellExecutable() {
        return Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell",
                "v1.0", "powershell.exe");
    }

    private Path writeScript(String name, String body) throws Exception {
        Path script = tempDir.resolve(name);
        Files.writeString(script, body, StandardCharsets.US_ASCII);
        return script;
    }

    private String healthyEngineScript() {
        return """
                param($EngineId, $Protocol)
                while (($line = [Console]::In.ReadLine()) -ne $null) {
                    [Console]::Out.WriteLine(('seen:{0}:{1}' -f $EngineId, $line))
                    if ($line -eq $Protocol) {
                        [Console]::Out.WriteLine($Protocol + 'ok')
                    } elseif ($line -eq 'isready') {
                        [Console]::Out.WriteLine('readyok')
                    } elseif ($line -eq 'crash') {
                        [Console]::Out.Flush()
                        exit 17
                    } elseif ($line -eq 'quit') {
                        [Console]::Out.Flush()
                        exit 0
                    }
                    [Console]::Out.Flush()
                }
                """;
    }

    private void awaitStatus(EngineRegistry registry,
                             String id,
                             EngineRegistry.Status expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (registry.snapshot(id).status() == expected) {
                return;
            }
            Thread.sleep(20);
        }
        assertEquals(expected, registry.snapshot(id).status());
    }

    private void awaitOutput(Map<String, List<String>> output,
                             String engineId,
                             String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (output.getOrDefault(engineId, List.of()).contains(expected)) {
                return;
            }
            Thread.sleep(20);
        }
        assertTrue(output.getOrDefault(engineId, List.of()).contains(expected));
    }
}
