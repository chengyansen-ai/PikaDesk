package com.sojourners.chess.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
@EnabledIfSystemProperty(named = "pikadesk.voice.acceptance",
        matches = "true")
final class WindowsSapiVoiceAcceptanceTest {

    @Test
    void speaksAFixedChinesePhraseWithoutStartingAChildProcess() {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            long processId = ProcessHandle.current().pid();
            System.out.println("VOICE_ACCEPTANCE_PID=" + processId);
            try (WindowsSapiVoiceBackend backend =
                         new WindowsSapiVoiceBackend()) {
                backend.speak("PikaDesk 本地语音验收通过");
            }
            assertTrue(ProcessHandle.current().descendants().findAny()
                    .isEmpty(), "SAPI must not start a child process");
            Thread.sleep(Duration.ofSeconds(5));
        });
    }
}
