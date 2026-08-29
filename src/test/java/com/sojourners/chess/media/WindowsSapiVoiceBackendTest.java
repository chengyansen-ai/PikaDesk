package com.sojourners.chess.media;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WindowsSapiVoiceBackendTest {

    @Test
    void initializesBeforeOpeningVoiceAndClosesInReverseOrder()
            throws Exception {
        AtomicBoolean initialized = new AtomicBoolean();
        AtomicInteger uninitializes = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();
        AtomicReference<String> spoken = new AtomicReference<>();

        WindowsSapiVoiceBackend.ComApartment apartment =
                new WindowsSapiVoiceBackend.ComApartment() {
                    @Override
                    public void initialize() {
                        initialized.set(true);
                    }

                    @Override
                    public void uninitialize() {
                        assertEquals(1, releases.get());
                        uninitializes.incrementAndGet();
                    }
                };

        WindowsSapiVoiceBackend backend = new WindowsSapiVoiceBackend(
                apartment,
                () -> {
                    assertTrue(initialized.get());
                    return new WindowsSapiVoiceBackend.SpeechVoice() {
                        @Override
                        public void speak(String text) {
                            spoken.set(text);
                        }

                        @Override
                        public void close() {
                            releases.incrementAndGet();
                        }
                    };
                });

        backend.speak("走法，炮二平五");
        backend.close();
        backend.close();

        assertEquals("走法，炮二平五", spoken.get());
        assertEquals(1, releases.get());
        assertEquals(1, uninitializes.get());
    }

    @Test
    void openingFailureStillBalancesTheComApartment() {
        AtomicInteger uninitializes = new AtomicInteger();
        WindowsSapiVoiceBackend.ComApartment apartment =
                new WindowsSapiVoiceBackend.ComApartment() {
                    @Override
                    public void initialize() {
                    }

                    @Override
                    public void uninitialize() {
                        uninitializes.incrementAndGet();
                    }
                };

        assertThrows(IOException.class, () ->
                new WindowsSapiVoiceBackend(apartment, () -> {
                    throw new IOException("voice unavailable");
                }));
        assertEquals(1, uninitializes.get());
    }

    @Test
    void speakingAfterCloseIsRejected() throws Exception {
        WindowsSapiVoiceBackend backend = new WindowsSapiVoiceBackend(
                new NoOpApartment(),
                () -> new WindowsSapiVoiceBackend.SpeechVoice() {
                    @Override
                    public void speak(String text) {
                    }

                    @Override
                    public void close() {
                    }
                });

        backend.close();

        assertThrows(IllegalStateException.class,
                () -> backend.speak("走法，炮二平五"));
    }

    private static final class NoOpApartment
            implements WindowsSapiVoiceBackend.ComApartment {
        @Override
        public void initialize() {
        }

        @Override
        public void uninitialize() {
        }
    }
}
