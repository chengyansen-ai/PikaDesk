package com.sojourners.chess.media;

import com.sun.jna.Platform;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bounded, non-blocking hand-off from application events to a local speech
 * backend. Backend creation and speech always happen on one daemon thread.
 */
public final class LocalVoiceService implements AutoCloseable {

    public static final int QUEUE_CAPACITY = 8;

    private final BackendFactory backendFactory;
    private final ArrayBlockingQueue<VoiceAnnouncement> queue =
            new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicReference<VoicePreferences> preferences =
            new AtomicReference<>(VoicePreferences.disabled());
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean backendUnavailable = new AtomicBoolean();
    private final AtomicReference<String> failureType =
            new AtomicReference<>("");
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final Thread worker;

    public static LocalVoiceService createForCurrentPlatform() {
        BackendFactory factory = Platform.isWindows()
                ? WindowsSapiVoiceBackend::new
                : () -> {
                    throw new UnsupportedOperationException(
                            "Local speech is only available on Windows");
                };
        return new LocalVoiceService(factory);
    }

    LocalVoiceService(BackendFactory backendFactory) {
        this.backendFactory = Objects.requireNonNull(
                backendFactory, "backendFactory");
        worker = Thread.ofPlatform()
                .name("pikadesk-local-voice")
                .daemon(true)
                .start(this::runWorker);
    }

    public void configure(VoicePreferences nextPreferences) {
        Objects.requireNonNull(nextPreferences, "nextPreferences");
        VoicePreferences previous = preferences.getAndSet(nextPreferences);
        if (!nextPreferences.enabled()) {
            queue.clear();
            worker.interrupt();
            return;
        }
        if (!previous.enabled()) {
            backendUnavailable.set(false);
            failureType.set("");
            worker.interrupt();
        }
    }

    public Submission announce(VoiceAnnouncement announcement) {
        Objects.requireNonNull(announcement, "announcement");
        if (closed.get()) {
            return Submission.CLOSED;
        }
        VoicePreferences current = preferences.get();
        if (!current.enabled()) {
            return Submission.DISABLED;
        }
        if (!current.allows(announcement.category())) {
            return Submission.FILTERED;
        }
        if (backendUnavailable.get()) {
            return Submission.BACKEND_UNAVAILABLE;
        }
        if (queue.offer(announcement)) {
            return Submission.ACCEPTED;
        }
        queue.poll();
        return queue.offer(announcement)
                ? Submission.REPLACED_OLDEST
                : Submission.BACKEND_UNAVAILABLE;
    }

    public Diagnostics diagnostics() {
        return new Diagnostics(preferences.get().enabled(),
                backendUnavailable.get(), queue.size(), failureType.get());
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            queue.clear();
            worker.interrupt();
        }
    }

    public boolean awaitStopped(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        return stopped.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    private void runWorker() {
        Backend backend = null;
        try {
            while (!closed.get()) {
                if (!preferences.get().enabled()) {
                    backend = closeQuietly(backend);
                }

                VoiceAnnouncement announcement;
                try {
                    announcement = queue.poll(250, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    if (closed.get()) {
                        break;
                    }
                    continue;
                }
                if (announcement == null
                        || !preferences.get().allows(
                                announcement.category())) {
                    continue;
                }

                try {
                    if (backend == null) {
                        backend = backendFactory.open();
                    }
                    backend.speak(announcement.text());
                } catch (Exception failure) {
                    if (failure instanceof InterruptedException
                            && (closed.get()
                            || !preferences.get().enabled())) {
                        backend = closeQuietly(backend);
                        if (closed.get()) {
                            break;
                        }
                        continue;
                    }
                    backendUnavailable.set(true);
                    failureType.set(safeFailureType(failure));
                    queue.clear();
                    backend = closeQuietly(backend);
                }
            }
        } finally {
            closeQuietly(backend);
            stopped.countDown();
        }
    }

    private String safeFailureType(Exception failure) {
        String simpleName = failure.getClass().getSimpleName();
        return simpleName.isBlank() ? "BackendFailure" : simpleName;
    }

    private Backend closeQuietly(Backend backend) {
        if (backend != null) {
            try {
                backend.close();
            } catch (Exception ignored) {
                // Voice shutdown must never affect application shutdown.
            }
        }
        return null;
    }

    @FunctionalInterface
    interface BackendFactory {
        Backend open() throws Exception;
    }

    @FunctionalInterface
    interface Backend extends AutoCloseable {
        void speak(String text) throws Exception;

        @Override
        default void close() throws Exception {
        }
    }

    public enum Submission {
        DISABLED,
        FILTERED,
        ACCEPTED,
        REPLACED_OLDEST,
        CLOSED,
        BACKEND_UNAVAILABLE
    }

    public record Diagnostics(boolean enabled,
                              boolean backendUnavailable,
                              int queued,
                              String failureType) {
    }
}
