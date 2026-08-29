package com.sojourners.chess.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "pikadesk.voice.performance",
        matches = "true")
final class LocalVoicePerformanceAcceptanceTest {

    private static final int SAMPLES = 20_000;
    private static final long P99_BUDGET_NANOS =
            TimeUnit.MILLISECONDS.toNanos(5);

    @Test
    void callerSideSubmissionStaysBelowTheP99Budget() throws Exception {
        BlockingBackend backend = new BlockingBackend();
        try (LocalVoiceService service = new LocalVoiceService(() -> backend)) {
            service.configure(VoicePreferences.allEnabled());
            service.announce(VoiceAnnouncement.move("占用后端"));
            assertTrue(backend.started.await(1, TimeUnit.SECONDS));

            for (int index = 0; index < 2_000; index++) {
                service.announce(VoiceAnnouncement.move("预热"));
            }

            long[] durations = new long[SAMPLES];
            for (int index = 0; index < durations.length; index++) {
                long start = System.nanoTime();
                service.announce(VoiceAnnouncement.move("基准"));
                durations[index] = System.nanoTime() - start;
            }
            Arrays.sort(durations);
            long p50 = percentile(durations, 50);
            long p95 = percentile(durations, 95);
            long p99 = percentile(durations, 99);
            long maximum = durations[durations.length - 1];
            System.out.printf(
                    "VOICE_SUBMIT_NS p50=%d p95=%d p99=%d max=%d samples=%d%n",
                    p50, p95, p99, maximum, durations.length);
            assertTrue(p99 < P99_BUDGET_NANOS,
                    () -> "voice submission p99 exceeded 5 ms: " + p99);
        } finally {
            backend.release.countDown();
        }
    }

    private long percentile(long[] values, int percentile) {
        int index = (int) Math.ceil(values.length * percentile / 100.0) - 1;
        return values[Math.max(0, index)];
    }

    private static final class BlockingBackend
            implements LocalVoiceService.Backend {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void speak(String text) throws InterruptedException {
            started.countDown();
            release.await();
        }
    }
}
