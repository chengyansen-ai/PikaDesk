package com.sojourners.chess.config;

import com.sojourners.chess.enginee.Engine;
import com.sojourners.chess.model.EngineConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

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

        assertTrue(result.changed(), result.diagnostics().toString());
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

    @Test
    void packagedProfileCanExplicitlyOptIntoAFilteredStandardObk() throws Exception {
        Path assets = Files.createDirectories(temp.resolve("local-assets"));
        Path engines = Files.createDirectories(assets.resolve("engines"));
        Files.write(engines.resolve("pikafish.exe"), new byte[]{1});
        Files.write(engines.resolve("pikafish.nnue"), new byte[]{2});
        Path book = Files.createDirectories(assets.resolve("books"))
                .resolve("PikaDesk-精选高可信.obk");
        Files.write(book, "SQLite format 3\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        Files.writeString(assets.resolve("profile.properties"), """
                engine.displayName=Pikafish stable
                engine.executable=engines/pikafish.exe
                engine.network=engines/pikafish.nnue
                engine.threads=12
                engine.hashMiB=1024
                engine.moveTimeMs=1500
                book.files=books/PikaDesk-精选高可信.obk
                book.allowLegacyObk=true
                book.enabled=true
                cloudBook.enabled=true
                cloudBook.timeoutMs=1800
                book.offManualSteps=20
                """);

        Properties defaults = Properties.createDefault();
        LocalAssetBootstrap.Result result = LocalAssetBootstrap.apply(defaults, temp);

        assertTrue(result.changed(), result.diagnostics().toString());
        assertEquals(book.toAbsolutePath().normalize().toString(),
                defaults.getOpenBookList().getFirst());
    }

    @Test
    void packagedObkRemainsRejectedWithoutExplicitOptIn() throws Exception {
        Path assets = Files.createDirectories(temp.resolve("local-assets"));
        Path engines = Files.createDirectories(assets.resolve("engines"));
        Files.write(engines.resolve("pikafish.exe"), new byte[]{1});
        Files.write(engines.resolve("pikafish.nnue"), new byte[]{2});
        Path book = Files.createDirectories(assets.resolve("books")).resolve("unknown.obk");
        Files.write(book, "SQLite format 3\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        Files.writeString(assets.resolve("profile.properties"), """
                engine.displayName=Pikafish stable
                engine.executable=engines/pikafish.exe
                engine.network=engines/pikafish.nnue
                engine.threads=12
                engine.hashMiB=1024
                engine.moveTimeMs=1500
                book.files=books/unknown.obk
                book.enabled=true
                cloudBook.enabled=false
                cloudBook.timeoutMs=1800
                book.offManualSteps=20
                """);

        Properties defaults = Properties.createDefault();
        LocalAssetBootstrap.Result result = LocalAssetBootstrap.apply(defaults, temp);

        assertFalse(result.changed());
        assertTrue(result.diagnostics().stream().anyMatch(message ->
                message.contains("explicit opt-in")));
        assertTrue(defaults.getOpenBookList().isEmpty());
    }

    @Test
    void newPackagedBookIsAddedWithoutReplacingAnExistingUsableEngine() throws Exception {
        Path assets = Files.createDirectories(temp.resolve("local-assets"));
        Path engines = Files.createDirectories(assets.resolve("engines"));
        Files.write(engines.resolve("packaged.exe"), new byte[]{1});
        Files.write(engines.resolve("pikafish.nnue"), new byte[]{2});
        Path book = Files.createDirectories(assets.resolve("books")).resolve("selected.xqb");
        Files.write(book, new byte[]{3});
        Files.writeString(assets.resolve("profile.properties"), """
                engine.displayName=Packaged engine
                engine.executable=engines/packaged.exe
                engine.network=engines/pikafish.nnue
                engine.threads=12
                engine.hashMiB=1024
                engine.moveTimeMs=1500
                book.files=books/selected.xqb
                book.enabled=true
                cloudBook.enabled=true
                cloudBook.timeoutMs=1800
                book.offManualSteps=20
                """);
        Path userEngine = temp.resolve("my-engine.exe");
        Files.write(userEngine, new byte[]{9});
        Properties current = Properties.createDefault();
        current.getEngineConfigList().add(new EngineConfig(
                "My engine", userEngine.toString(), "uci", new LinkedHashMap<>()));
        current.setEngineName("My engine");

        LocalAssetBootstrap.Result first = LocalAssetBootstrap.apply(current, temp);
        LocalAssetBootstrap.Result second = LocalAssetBootstrap.apply(current, temp);

        assertTrue(first.changed(), first.diagnostics().toString());
        assertFalse(second.changed());
        assertEquals(1, current.getEngineConfigList().size());
        assertEquals("My engine", current.getEngineName());
        assertEquals(book.toAbsolutePath().normalize().toString(),
                current.getOpenBookList().getFirst());
        assertTrue(current.getBookSwitch());
        assertTrue(current.getUseCloudBook());
    }

    @Test
    void optionalDevelopmentCandidateIsAddedButStableRemainsTheDefault() throws Exception {
        Path assets = Files.createDirectories(temp.resolve("local-assets"));
        Path stable = Files.createDirectories(assets.resolve("engines/stable"));
        Files.write(stable.resolve("pikafish.exe"), new byte[]{1});
        Files.write(stable.resolve("pikafish.nnue"), new byte[]{2});
        Path candidate = Files.createDirectories(assets.resolve("engines/master"));
        Files.write(candidate.resolve("pikafish-master.exe"), new byte[]{3});
        Files.write(candidate.resolve("pikafish.nnue"), new byte[]{4});
        Files.writeString(assets.resolve("profile.properties"), """
                engine.displayName=Pikafish stable
                engine.executable=engines/stable/pikafish.exe
                engine.network=engines/stable/pikafish.nnue
                engine.threads=12
                engine.hashMiB=1024
                engine.moveTimeMs=1500
                engine.candidate.enabled=true
                engine.candidate.displayName=Pikafish master candidate
                engine.candidate.executable=engines/master/pikafish-master.exe
                engine.candidate.network=engines/master/pikafish.nnue
                book.enabled=false
                cloudBook.enabled=false
                cloudBook.timeoutMs=1800
                book.offManualSteps=20
                """);
        Properties defaults = Properties.createDefault();

        LocalAssetBootstrap.Result result = LocalAssetBootstrap.apply(defaults, temp);

        assertTrue(result.changed(), result.diagnostics().toString());
        assertEquals(2, defaults.getEngineConfigList().size());
        assertEquals("Pikafish stable", defaults.getEngineName());
        assertEquals("Pikafish master candidate",
                defaults.getEngineConfigList().get(1).getName());
        assertEquals("pikafish.nnue", defaults.getEngineConfigList().get(1)
                .getOptions().get("EvalFile"));
    }
}
