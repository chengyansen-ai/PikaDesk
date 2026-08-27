package com.sojourners.chess.automation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AutomationSafetyKernelTest {

    @Test
    void startsDisabledAndCannotSendAnExternalEvent() {
        AutomationSafetyKernel kernel = new AutomationSafetyKernel();
        AtomicBoolean sequenceStarted = new AtomicBoolean();
        AtomicInteger events = new AtomicInteger();

        boolean sent = kernel.executeOne(permit -> {
            sequenceStarted.set(true);
            permit.send(events::incrementAndGet);
        });

        assertAll(
                () -> assertFalse(sent),
                () -> assertFalse(sequenceStarted.get()),
                () -> assertEquals(0, events.get()),
                () -> assertEquals(AutomationState.DISABLED, kernel.state()),
                () -> assertTrue(kernel.authorization().isEmpty())
        );
    }

    @Test
    void followsTheAuthorizedSingleMoveAndConfirmationFlow() {
        AutomationSafetyKernel kernel = readyKernel();
        AtomicInteger events = new AtomicInteger();

        boolean sent = kernel.executeOne(permit -> {
            assertEquals(AutomationState.EXECUTING, kernel.state());
            assertTrue(permit.send(events::incrementAndGet));
        });

        assertAll(
                () -> assertTrue(sent),
                () -> assertEquals(1, events.get()),
                () -> assertEquals(AutomationState.CONFIRMING, kernel.state()),
                () -> assertTrue(kernel.confirmationAccepted()),
                () -> assertEquals(AutomationState.OBSERVING, kernel.state())
        );
    }

    @Test
    void everyActiveStageFailsClosedWhenValidationFails() {
        List<AutomationSafetyKernel> activeKernels = List.of(
                kernelAt(AutomationState.ARMED),
                kernelAt(AutomationState.OBSERVING),
                kernelAt(AutomationState.RECOGNIZED),
                kernelAt(AutomationState.THINKING),
                kernelAt(AutomationState.READY),
                confirmingKernel()
        );

        for (AutomationSafetyKernel kernel : activeKernels) {
            kernel.validationFailed("target validation failed");
            assertAll(
                    () -> assertEquals(AutomationState.PAUSED, kernel.state()),
                    () -> assertEquals("target validation failed", kernel.lastReason().orElseThrow()),
                    () -> assertFalse(kernel.executeOne(permit -> {
                        throw new AssertionError("paused automation sent an event");
                    }))
            );
        }
    }

    @Test
    void anInvalidTransitionPausesInsteadOfSkippingAValidationStage() {
        AutomationSafetyKernel kernel = kernelAt(AutomationState.ARMED);

        assertAll(
                () -> assertFalse(kernel.beginThinking()),
                () -> assertEquals(AutomationState.PAUSED, kernel.state()),
                () -> assertTrue(kernel.lastReason().orElseThrow().contains("ARMED"))
        );
    }

    @Test
    void aSenderFailurePausesAndPreventsAnotherEvent() {
        AutomationSafetyKernel kernel = readyKernel();
        AtomicInteger attempts = new AtomicInteger();

        boolean sent = kernel.executeOne(permit -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("synthetic mouse failure");
        });

        assertAll(
                () -> assertFalse(sent),
                () -> assertEquals(1, attempts.get()),
                () -> assertEquals(AutomationState.PAUSED, kernel.state()),
                () -> assertTrue(kernel.lastReason().orElseThrow().contains("synthetic mouse failure")),
                () -> assertFalse(kernel.executeOne(permit -> permit.send(attempts::incrementAndGet))),
                () -> assertEquals(1, attempts.get())
        );
    }

    @Test
    void aSequenceWithoutAnyPermittedEventCannotReachConfirmation() {
        AutomationSafetyKernel kernel = readyKernel();

        boolean sent = kernel.executeOne(permit -> { });

        assertAll(
                () -> assertFalse(sent),
                () -> assertEquals(AutomationState.PAUSED, kernel.state()),
                () -> assertTrue(kernel.lastReason().orElseThrow().contains("without an event"))
        );
    }

    @Test
    void emergencyStopClearsAuthorizationAndRejectsAllLaterEvents() {
        AutomationSafetyKernel kernel = readyKernel();
        AtomicInteger events = new AtomicInteger();

        kernel.emergencyStop("user emergency stop");

        assertAll(
                () -> assertEquals(AutomationState.DISABLED, kernel.state()),
                () -> assertTrue(kernel.authorization().isEmpty()),
                () -> assertEquals("user emergency stop", kernel.lastReason().orElseThrow()),
                () -> assertFalse(kernel.executeOne(permit -> permit.send(events::incrementAndGet))),
                () -> assertEquals(0, events.get())
        );
    }

    @Test
    void emergencyStopInvalidatesAnInFlightSequenceBeforeItsNextEvent() throws Exception {
        AutomationSafetyKernel kernel = readyKernel();
        CountDownLatch sequenceStarted = new CountDownLatch(1);
        CountDownLatch releaseSequence = new CountDownLatch(1);
        AtomicBoolean firstPermitAccepted = new AtomicBoolean();
        AtomicBoolean permitAccepted = new AtomicBoolean(true);
        AtomicInteger events = new AtomicInteger();

        Thread eventThread = Thread.ofPlatform().start(() -> kernel.executeOne(permit -> {
            firstPermitAccepted.set(permit.send(events::incrementAndGet));
            sequenceStarted.countDown();
            await(releaseSequence);
            permitAccepted.set(permit.send(events::incrementAndGet));
        }));
        assertTrue(sequenceStarted.await(1, TimeUnit.SECONDS));

        kernel.emergencyStop("concurrent emergency stop");

        releaseSequence.countDown();
        eventThread.join(1_000);

        assertAll(
                () -> assertFalse(eventThread.isAlive()),
                () -> assertTrue(firstPermitAccepted.get()),
                () -> assertFalse(permitAccepted.get()),
                () -> assertEquals(1, events.get()),
                () -> assertEquals(AutomationState.DISABLED, kernel.state()),
                () -> assertFalse(kernel.executeOne(permit -> permit.send(events::incrementAndGet))),
                () -> assertEquals(1, events.get())
        );
    }

    private AutomationSafetyKernel confirmingKernel() {
        AutomationSafetyKernel kernel = readyKernel();
        assertTrue(kernel.executeOne(permit -> assertTrue(permit.send(() -> { }))));
        return kernel;
    }

    private AutomationSafetyKernel readyKernel() {
        return kernelAt(AutomationState.READY);
    }

    private AutomationSafetyKernel kernelAt(AutomationState target) {
        AutomationSafetyKernel kernel = new AutomationSafetyKernel();
        if (target == AutomationState.DISABLED) {
            return kernel;
        }
        assertTrue(kernel.arm(new AutomationSafetyKernel.Authorization("local-test-board", 1)));
        if (target == AutomationState.ARMED) {
            return kernel;
        }
        assertTrue(kernel.beginObservation());
        if (target == AutomationState.OBSERVING) {
            return kernel;
        }
        assertTrue(kernel.recognitionAccepted());
        if (target == AutomationState.RECOGNIZED) {
            return kernel;
        }
        assertTrue(kernel.beginThinking());
        if (target == AutomationState.THINKING) {
            return kernel;
        }
        assertTrue(kernel.readyToExecute());
        if (target == AutomationState.READY) {
            return kernel;
        }
        throw new IllegalArgumentException("unsupported target state: " + target);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for test latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test thread interrupted", e);
        }
    }
}
