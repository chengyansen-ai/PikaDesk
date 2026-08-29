package com.sojourners.chess.media;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LocalVoiceServiceTest {

    @Test
    void disabledServiceNeverInitializesTheBackend() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        try (LocalVoiceService service = new LocalVoiceService(() -> {
            opens.incrementAndGet();
            return text -> { };
        })) {
            assertEquals(LocalVoiceService.Submission.DISABLED,
                    service.announce(VoiceAnnouncement.move("炮二平五")));
            Thread.sleep(50);
            assertEquals(0, opens.get());
        }
    }

    @Test
    void filtersCategoriesBeforeOpeningTheBackend() throws Exception {
        RecordingBackend backend = new RecordingBackend(1);
        try (LocalVoiceService service = new LocalVoiceService(() -> backend)) {
            service.configure(new VoicePreferences(true, false, true, false));

            assertEquals(LocalVoiceService.Submission.FILTERED,
                    service.announce(VoiceAnnouncement.move("炮二平五")));
            assertEquals(LocalVoiceService.Submission.FILTERED,
                    service.announce(VoiceAnnouncement.result("红方胜")));
            assertEquals(LocalVoiceService.Submission.ACCEPTED,
                    service.announce(VoiceAnnouncement.warning("目标窗口已失焦")));

            assertTrue(backend.spoken.await(1, TimeUnit.SECONDS));
            assertEquals(List.of("警告，目标窗口已失焦"), backend.texts);
        }
    }

    @Test
    void blockedBackendCannotBlockCallersAndQueueStaysBounded() throws Exception {
        BlockingBackend backend = new BlockingBackend();
        try (LocalVoiceService service = new LocalVoiceService(() -> backend)) {
            service.configure(VoicePreferences.allEnabled());

            assertTimeoutPreemptively(Duration.ofMillis(250), () ->
                    assertEquals(LocalVoiceService.Submission.ACCEPTED,
                            service.announce(VoiceAnnouncement.move("第一步"))));
            assertTrue(backend.started.await(1, TimeUnit.SECONDS));

            LocalVoiceService.Submission last = null;
            for (int index = 0; index < 10; index++) {
                int move = index;
                last = assertTimeoutPreemptively(Duration.ofMillis(250), () ->
                        service.announce(VoiceAnnouncement.move("走法 " + move)));
            }

            assertEquals(LocalVoiceService.Submission.REPLACED_OLDEST, last);
            assertEquals(LocalVoiceService.QUEUE_CAPACITY,
                    service.diagnostics().queued());
            backend.release.countDown();
        }
    }

    @Test
    void disablingClearsPendingSpeechAndClosingRejectsNewMessages()
            throws Exception {
        BlockingBackend backend = new BlockingBackend();
        LocalVoiceService service = new LocalVoiceService(() -> backend);
        service.configure(VoicePreferences.allEnabled());
        service.announce(VoiceAnnouncement.move("第一步"));
        assertTrue(backend.started.await(1, TimeUnit.SECONDS));
        for (int index = 0; index < 5; index++) {
            service.announce(VoiceAnnouncement.move("排队 " + index));
        }

        service.configure(VoicePreferences.disabled());
        assertEquals(0, service.diagnostics().queued());
        assertEquals(LocalVoiceService.Submission.DISABLED,
                service.announce(VoiceAnnouncement.warning("已关闭")));

        backend.release.countDown();
        service.close();
        assertTrue(service.awaitStopped(Duration.ofSeconds(1)));
        assertEquals(LocalVoiceService.Submission.CLOSED,
                service.announce(VoiceAnnouncement.result("红方胜")));
    }

    @Test
    void backendFailureTripsACircuitWithoutLeakingText() throws Exception {
        try (LocalVoiceService service = new LocalVoiceService(() -> {
            throw new IOException("sensitive test text must not be retained");
        })) {
            service.configure(VoicePreferences.allEnabled());
            assertEquals(LocalVoiceService.Submission.ACCEPTED,
                    service.announce(VoiceAnnouncement.warning("目标变化")));

            waitUntil(() -> service.diagnostics().backendUnavailable());
            LocalVoiceService.Diagnostics diagnostics = service.diagnostics();
            assertEquals(0, diagnostics.queued());
            assertFalse(diagnostics.failureType().isBlank());
            assertFalse(diagnostics.failureType().contains("sensitive"));
            assertEquals(LocalVoiceService.Submission.BACKEND_UNAVAILABLE,
                    service.announce(VoiceAnnouncement.warning("再试一次")));
        }
    }

    private void waitUntil(CheckedBoolean condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!condition.value() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.value());
    }

    @FunctionalInterface
    private interface CheckedBoolean {
        boolean value() throws Exception;
    }

    private static final class RecordingBackend implements LocalVoiceService.Backend {
        private final List<String> texts = new CopyOnWriteArrayList<>();
        private final CountDownLatch spoken;

        private RecordingBackend(int expected) {
            spoken = new CountDownLatch(expected);
        }

        @Override
        public void speak(String text) {
            texts.add(text);
            spoken.countDown();
        }
    }

    private static final class BlockingBackend implements LocalVoiceService.Backend {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void speak(String text) throws InterruptedException {
            started.countDown();
            release.await();
        }
    }
}
