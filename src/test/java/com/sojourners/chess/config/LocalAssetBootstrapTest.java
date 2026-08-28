package com.sojourners.chess.config;

import com.sojourners.chess.enginee.Engine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LocalAssetBootstrapTest {

    @TempDir
    Path temp;

    @Test
    void packagedProfileLoadsVerifiedEngineAndBookOnFirstStart() throws Exception {
        Path assets = Files.createDirectories(temp.resolve("local-assets"));
        Path engine = Files.createDirectories(assets.resolve("engines"))
                .resolve("pikafish-avxvnni.exe");
        Files.write(engine, new byte[]{1});
        Path network = assets.resolve("engines").resolve("pikafish.nnue");
        Files.write(network, new byte[]{2});
        Path book = Files.createDirectories(assets.resolve("books"))
                .resolve("PikaDesk-Community.xqb");
        Files.write(book, new byte[]{3});
        Files.writeString(assets.resolve("profile.properties"), """
                engine.displayName=Pikafish 2026-01-02 AVX-VNNI
                engine.executable=engines/pikafish-avxvnni.exe
                engine.network=engines/pikafish.nnue
                engine.threads=12
                engine.hashMiB=1024
                engine.moveTimeMs=1500
                book.files=books/PikaDesk-Community.xqb
                book.enabled=true
                cloudBook.enabled=true
                cloudBook.timeoutMs=900
                book.offManualSteps=20
                """);

        Properties defaults = Properties.createDefault();
        LocalAssetBootstrap.Result result = LocalAssetBootstrap.apply(defaults, temp);

        assertTrue(result.changed());
        assertEquals("Pikafish 2026-01-02 AVX-VNNI", defaults.getEngineName());
        assertEquals(engine.toAbsolutePath().normalize().toString(),
                defaults.getEngineConfigList().getFirst().getPath());
        assertEquals(network.getFileName().toString(),
                defaults.getEngineConfigList().getFirst().getOptions().get("EvalFile"));
        assertEquals(12, defaults.getThreadNum());
        assertEquals(1024, defaults.getHashSize());
        assertEquals(Engine.AnalysisModel.FIXED_TIME, defaults.getAnalysisModel());
        assertEquals(1500, defaults.getAnalysisValue());
        assertEquals(book.toAbsolutePath().normalize().toString(),
                defaults.getOpenBookList().getFirst());
        assertTrue(defaults.getBookSwitch());
        assertTrue(defaults.getUseCloudBook());
        assertEquals(900, defaults.getCloudBookTimeout());
        assertEquals(20, defaults.getOffManualSteps());
    }

    @Test
    void absentProfileKeepsSafeOfflineDefaults() {
        Properties defaults = Properties.createDefault();

        LocalAssetBootstrap.Result result = LocalAssetBootstrap.apply(defaults, temp);

        assertFalse(result.changed());
        assertTrue(defaults.getEngineConfigList().isEmpty());
        assertFalse(defaults.getBookSwitch());
        assertFalse(defaults.getUseCloudBook());
    }
}
