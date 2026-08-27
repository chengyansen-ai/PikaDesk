package com.sojourners.chess;

import com.sojourners.chess.enginee.Engine;
import com.sojourners.chess.enginee.EngineCallBack;
import com.sojourners.chess.model.BookData;
import com.sojourners.chess.model.EngineConfig;
import com.sojourners.chess.model.ThinkData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
final class EngineProtocolCharacterizationTest {

    @TempDir
    Path tempDir;

    @Test
    void detectsUciAndCollectsAdvertisedOptionsFromAFakeEngine() throws Exception {
        Path script = writePowerShellScript("uci-engine.ps1", """
                while (($line = [Console]::In.ReadLine()) -ne $null) {
                    if ($line -eq 'uci') {
                        [Console]::Out.WriteLine('id name PikaDesk Fake Engine')
                        [Console]::Out.WriteLine('option name Skill Level type spin default 10 min 0 max 20')
                        [Console]::Out.WriteLine('uciok')
                        [Console]::Out.Flush()
                    }
                }
                """);
        LinkedHashMap<String, String> options = new LinkedHashMap<>();

        String protocol = assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> Engine.test(powerShellCommand(script), options)
        );

        assertAll(
                () -> assertEquals("uci", protocol),
                () -> assertEquals("10", options.get("Skill Level"))
        );
    }

    @Test
    void returnsNullWhenTheFakeEngineNeverCompletesAHandshake() throws Exception {
        Path script = writePowerShellScript("silent-engine.ps1", "Start-Sleep -Seconds 10\n");

        String protocol = assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> Engine.test(powerShellCommand(script), new LinkedHashMap<>())
        );

        assertNull(protocol);
    }

    @Test
    void toleratesAnEngineThatExitsDuringHandshake() throws Exception {
        Path script = writePowerShellScript("exiting-engine.ps1", "exit 7\n");
        PrintStream originalError = System.err;
        ByteArrayOutputStream capturedError = new ByteArrayOutputStream();
        String protocol;
        try (PrintStream errorStream = new PrintStream(capturedError, true, StandardCharsets.UTF_8)) {
            System.setErr(errorStream);
            protocol = assertTimeoutPreemptively(
                    Duration.ofSeconds(5),
                    () -> Engine.test(powerShellCommand(script), new LinkedHashMap<>())
            );
        } finally {
            System.setErr(originalError);
        }

        assertAll(
                () -> assertNull(protocol),
                () -> assertTrue(capturedError.toString(StandardCharsets.UTF_8).contains("java.io.IOException"),
                        "现有实现会把断开管道作为堆栈打印；后续错误恢复应改为结构化结果")
        );
    }

    @Test
    void drainsAVeryLongIdentificationLineBeforeUciOk() throws Exception {
        Path script = writePowerShellScript("long-output-engine.ps1", """
                while (($line = [Console]::In.ReadLine()) -ne $null) {
                    if ($line -eq 'uci') {
                        [Console]::Out.WriteLine(('x' * 250000))
                        [Console]::Out.WriteLine('uciok')
                        [Console]::Out.Flush()
                    }
                }
                """);

        String protocol = assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> Engine.test(powerShellCommand(script), new LinkedHashMap<>())
        );

        assertEquals("uci", protocol);
    }

    @Test
    void parsesNormalPvAndBestMoveCallbacksAndRejectsMalformedMoves() throws Exception {
        RecordingCallback callback = new RecordingCallback();
        String sortExecutable = Path.of(System.getenv("SystemRoot"), "System32", "sort.exe").toString();
        EngineConfig config = new EngineConfig("parser-host", sortExecutable, "uci", new LinkedHashMap<>());
        Engine engine = new Engine(config, callback);

        try {
            invokePrivate(engine, "thinkDetail", "info depth 12 multipv 2 score cp 34 time 55 nps 120000 pv b2e2 b9c7");
            invokePrivate(engine, "bestMove", "bestmove b2e2 ponder b9c7");
            invokePrivate(engine, "bestMove", "bestmove z9z8");

            ThinkData thinkData = callback.thinkData.getFirst();
            assertAll(
                    () -> assertEquals(12, thinkData.getDepth()),
                    () -> assertEquals(2, thinkData.getPv()),
                    () -> assertEquals(34, thinkData.getScore()),
                    () -> assertEquals(55L, thinkData.getTime()),
                    () -> assertEquals(120000L, thinkData.getNps()),
                    () -> assertEquals(List.of("b2e2", "b9c7"), thinkData.getDetail()),
                    () -> assertEquals(List.of("b2e2"), callback.bestMoves),
                    () -> assertEquals(List.of("b9c7"), callback.ponderMoves)
            );
        } finally {
            engine.close();
        }
    }

    private Path writePowerShellScript(String fileName, String content) throws Exception {
        Path script = tempDir.resolve(fileName);
        Files.writeString(script, content, StandardCharsets.US_ASCII);
        return script;
    }

    private String powerShellCommand(Path script) {
        return "powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"" + script + "\"";
    }

    private void invokePrivate(Engine engine, String methodName, String line) throws Exception {
        Method method = Engine.class.getDeclaredMethod(methodName, String.class);
        method.setAccessible(true);
        method.invoke(engine, line);
    }

    private static final class RecordingCallback implements EngineCallBack {
        private final List<String> bestMoves = new ArrayList<>();
        private final List<String> ponderMoves = new ArrayList<>();
        private final List<ThinkData> thinkData = new ArrayList<>();

        @Override
        public void bestMove(String first, String second) {
            bestMoves.add(first);
            ponderMoves.add(second);
        }

        @Override
        public void thinkDetail(ThinkData td) {
            thinkData.add(td);
        }

        @Override
        public void showBookResults(List<BookData> list) {
        }
    }
}
