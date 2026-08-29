package com.sojourners.chess.media;

import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.Variant;
import com.sun.jna.platform.win32.COM.COMLateBindingObject;
import com.sun.jna.platform.win32.COM.COMUtils;

import java.util.Objects;

/** Windows-only local speech backend using the in-process SAPI COM API. */
final class WindowsSapiVoiceBackend implements LocalVoiceService.Backend {

    private final ComApartment apartment;
    private SpeechVoice voice;
    private boolean apartmentInitialized;
    private boolean closed;

    WindowsSapiVoiceBackend() throws Exception {
        this(new JnaComApartment(), JnaSpeechVoice::new);
    }

    WindowsSapiVoiceBackend(ComApartment apartment,
                            SpeechVoiceFactory voiceFactory)
            throws Exception {
        this.apartment = Objects.requireNonNull(apartment, "apartment");
        Objects.requireNonNull(voiceFactory, "voiceFactory");

        apartment.initialize();
        apartmentInitialized = true;
        try {
            voice = Objects.requireNonNull(voiceFactory.open(), "voice");
        } catch (Exception | Error failure) {
            uninitializeApartment();
            throw failure;
        }
    }

    @Override
    public void speak(String text) throws Exception {
        if (closed) {
            throw new IllegalStateException("Voice backend is closed");
        }
        voice.speak(Objects.requireNonNull(text, "text"));
    }

    @Override
    public void close() throws Exception {
        if (closed) {
            return;
        }
        closed = true;
        Exception closeFailure = null;
        try {
            if (voice != null) {
                voice.close();
            }
        } catch (Exception failure) {
            closeFailure = failure;
        } finally {
            voice = null;
            uninitializeApartment();
        }
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    private void uninitializeApartment() {
        if (apartmentInitialized) {
            apartmentInitialized = false;
            apartment.uninitialize();
        }
    }

    interface ComApartment {
        void initialize();

        void uninitialize();
    }

    @FunctionalInterface
    interface SpeechVoiceFactory {
        SpeechVoice open() throws Exception;
    }

    interface SpeechVoice extends AutoCloseable {
        void speak(String text) throws Exception;

        @Override
        void close() throws Exception;
    }

    private static final class JnaComApartment implements ComApartment {
        @Override
        public void initialize() {
            COMUtils.checkRC(Ole32.INSTANCE.CoInitializeEx(
                    null, Ole32.COINIT_MULTITHREADED));
        }

        @Override
        public void uninitialize() {
            Ole32.INSTANCE.CoUninitialize();
        }
    }

    private static final class JnaSpeechVoice extends COMLateBindingObject
            implements SpeechVoice {

        private JnaSpeechVoice() {
            super("SAPI.SpVoice", false);
        }

        @Override
        public void speak(String text) {
            invokeNoReply("Speak", new Variant.VARIANT(text));
        }

        @Override
        public void close() {
            release();
        }
    }
}
