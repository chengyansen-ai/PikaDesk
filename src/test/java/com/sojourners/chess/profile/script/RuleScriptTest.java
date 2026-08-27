package com.sojourners.chess.profile.script;

import com.sojourners.chess.automation.AutomationSafetyKernel;
import com.sojourners.chess.automation.AutomationState;
import com.sojourners.chess.profile.time.TimeStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuleScriptTest {

    private static final String TACTICAL_SCRIPT = """
            PDSCRIPT 1
            RULE tactical
            WHEN ENGINE_RESULT
            IF SCORE_CP LE -200
            IF COMPLEXITY GE 75
            DO SET_TIME_SCALE 130
            DO SHOW_NOTICE TACTICAL_POSITION
            END
            """;

    @Test
    void parsesAndCanonicallyRoundTripsAWhitelistedRule() {
        RuleScript script = RuleScript.parse(TACTICAL_SCRIPT);

        assertEquals(1, script.schemaVersion());
        assertEquals(1, script.rules().size());
        assertEquals("tactical", script.rules().getFirst().id());
        assertEquals(RuleScript.Event.ENGINE_RESULT, script.rules().getFirst().event());
        assertEquals(2, script.rules().getFirst().conditions().size());
        assertEquals(2, script.rules().getFirst().actions().size());
        assertEquals(script, RuleScript.parse(script.serialize()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SHELL cmd.exe /c calc.exe",
            "EXEC powershell -Command whoami",
            "OPEN_URL https://example.com",
            "READ_FILE C:\\Users\\Public\\secret.txt",
            "WRITE_FILE ..\\outside.txt",
            "REFLECT java.lang.Runtime",
            "CLASS_FOR_NAME java.lang.ProcessBuilder",
            "START_ANALYSIS ; calc.exe"
    })
    void rejectsCommandPathNetworkAndReflectionInjection(String attemptedAction) {
        String source = "PDSCRIPT 1\nRULE hostile\nWHEN ENGINE_RESULT\nDO "
                + attemptedAction + "\nEND\n";

        assertThrows(IllegalArgumentException.class, () -> RuleScript.parse(source));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "PDSCRIPT 2\n",
            "PDSCRIPT 1\nUNKNOWN value\n",
            "PDSCRIPT 1\nRULE x\nWHEN NETWORK_RESPONSE\nDO START_ANALYSIS\nEND\n",
            "PDSCRIPT 1\nRULE x\nDO START_ANALYSIS\nWHEN ENGINE_RESULT\nEND\n",
            "PDSCRIPT 1\nRULE x\nWHEN ENGINE_RESULT\nEND\n",
            "PDSCRIPT 1\nRULE x\nWHEN ENGINE_RESULT\nDO START_ANALYSIS\n",
            "PDSCRIPT 1\nRULE same\nWHEN ENGINE_RESULT\nDO START_ANALYSIS\nEND\n"
                    + "RULE same\nWHEN ENGINE_RESULT\nDO STOP_ANALYSIS\nEND\n"
    })
    void rejectsUnknownVersionsFieldsAndMalformedGrammar(String source) {
        assertThrows(IllegalArgumentException.class, () -> RuleScript.parse(source));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DO SET_TIME_SCALE 24",
            "DO SET_TIME_SCALE 201",
            "IF SCORE_CP LE -100001",
            "IF SCORE_CP GE 100001",
            "IF COMPLEXITY GE 101",
            "IF REMAINING_MILLIS LE -1",
            "IF PHASE EQ UNKNOWN",
            "IF SIDE EQ BOTH",
            "IF AUTOMATION_STATE LE READY"
    })
    void rejectsEveryBoundedOperandOutsideItsContract(String statement) {
        String source = "PDSCRIPT 1\nRULE invalid\nWHEN ENGINE_RESULT\n"
                + statement + "\nDO START_ANALYSIS\nEND\n";

        assertThrows(IllegalArgumentException.class, () -> RuleScript.parse(source));
    }

    @Test
    void dryRunIsDeterministicAndNeverDispatchesAnAction() {
        RuleScript script = RuleScript.parse(TACTICAL_SCRIPT);
        AtomicInteger dispatched = new AtomicInteger();
        RuleEngine engine = new RuleEngine();
        RuleEngine.ExecutionOptions options = RuleEngine.ExecutionOptions.defaults();

        RuleEngine.ExecutionResult first = engine.execute(
                script, RuleScript.Event.ENGINE_RESULT, matchingContext(),
                RuleEngine.Mode.DRY_RUN, new AutomationSafetyKernel(),
                action -> dispatched.incrementAndGet(), options);
        RuleEngine.ExecutionResult second = engine.execute(
                script, RuleScript.Event.ENGINE_RESULT, matchingContext(),
                RuleEngine.Mode.DRY_RUN, new AutomationSafetyKernel(),
                action -> dispatched.incrementAndGet(), options);

        assertEquals(first, second);
        assertEquals(RuleEngine.ExecutionStatus.COMPLETED, first.status());
        assertEquals(0, dispatched.get());
        assertTrue(first.audit().stream().anyMatch(entry ->
                entry.kind() == RuleEngine.AuditKind.ACTION_PREVIEW));
    }

    @Test
    void liveRunDispatchesOnlyActionsWhoseWhitelistedConditionsMatch() {
        RuleScript script = RuleScript.parse("""
                PDSCRIPT 1
                RULE all-conditions
                WHEN ENGINE_RESULT
                IF SCORE_CP LE -200
                IF COMPLEXITY GE 75
                IF REMAINING_MILLIS LE 5000
                IF TIME_TARGET_MILLIS GE 500
                IF PHASE EQ MIDDLEGAME
                IF SIDE EQ RED
                IF AUTOMATION_STATE EQ READY
                DO START_ANALYSIS
                END
                """);
        AutomationSafetyKernel kernel = readyKernel();
        List<RuleScript.Action> dispatched = new ArrayList<>();

        RuleEngine.ExecutionResult result = new RuleEngine().execute(
                script, RuleScript.Event.ENGINE_RESULT, matchingContext(),
                RuleEngine.Mode.LIVE, kernel, dispatched::add,
                RuleEngine.ExecutionOptions.defaults());

        assertEquals(RuleEngine.ExecutionStatus.COMPLETED, result.status());
        assertEquals(List.of(RuleScript.Action.startAnalysis()), dispatched);
    }

    @Test
    void automationRequestCannotBypassTheRealSafetyKernel() {
        RuleScript script = RuleScript.parse("""
                PDSCRIPT 1
                RULE request
                WHEN POSITION_STABLE
                DO REQUEST_AUTHORIZED_MOVE
                END
                """);
        List<RuleScript.Action> disabledDispatches = new ArrayList<>();
        AutomationSafetyKernel disabled = new AutomationSafetyKernel();

        RuleEngine.ExecutionResult blocked = new RuleEngine().execute(
                script, RuleScript.Event.POSITION_STABLE, matchingContext(),
                RuleEngine.Mode.LIVE, disabled, disabledDispatches::add,
                RuleEngine.ExecutionOptions.defaults());

        assertEquals(RuleEngine.ExecutionStatus.BLOCKED, blocked.status());
        assertTrue(disabledDispatches.isEmpty());
        assertEquals(AutomationState.DISABLED, disabled.state());

        AutomationSafetyKernel ready = readyKernel();
        List<RuleScript.Action> readyDispatches = new ArrayList<>();
        RuleEngine.ExecutionResult allowed = new RuleEngine().execute(
                script, RuleScript.Event.POSITION_STABLE, matchingContext(),
                RuleEngine.Mode.LIVE, ready, readyDispatches::add,
                RuleEngine.ExecutionOptions.defaults());

        assertEquals(RuleEngine.ExecutionStatus.COMPLETED, allowed.status());
        assertEquals(List.of(RuleScript.Action.requestAuthorizedMove()), readyDispatches);
        assertEquals(AutomationState.READY, ready.state());
    }

    @Test
    void everyNonReadyAutomationStateBlocksMoveRequests() {
        RuleScript script = RuleScript.parse("""
                PDSCRIPT 1
                RULE request
                WHEN POSITION_STABLE
                DO REQUEST_AUTHORIZED_MOVE
                END
                """);
        List<AutomationSafetyKernel> kernels = new ArrayList<>();
        kernels.add(new AutomationSafetyKernel());
        kernels.add(kernelAfterTransitions(0));
        kernels.add(kernelAfterTransitions(1));
        kernels.add(kernelAfterTransitions(2));
        kernels.add(kernelAfterTransitions(3));
        AutomationSafetyKernel confirming = readyKernel();
        assertTrue(confirming.executeOne(permit -> permit.send(() -> { })));
        kernels.add(confirming);
        AutomationSafetyKernel paused = readyKernel();
        paused.validationFailed("test pause");
        kernels.add(paused);

        for (AutomationSafetyKernel kernel : kernels) {
            AtomicInteger dispatches = new AtomicInteger();
            RuleEngine.ExecutionResult result = new RuleEngine().execute(
                    script, RuleScript.Event.POSITION_STABLE, matchingContext(),
                    RuleEngine.Mode.LIVE, kernel,
                    action -> dispatches.incrementAndGet(),
                    RuleEngine.ExecutionOptions.defaults());
            assertEquals(RuleEngine.ExecutionStatus.BLOCKED, result.status());
            assertEquals(0, dispatches.get());
            assertTrue(result.audit().stream().anyMatch(entry ->
                    entry.kind() == RuleEngine.AuditKind.ACTION_BLOCKED));
        }
    }

    @Test
    void stepLimitStopsBeforeDispatchingAnotherAction() {
        RuleScript script = RuleScript.parse("""
                PDSCRIPT 1
                RULE first
                WHEN ENGINE_RESULT
                DO START_ANALYSIS
                END
                RULE second
                WHEN ENGINE_RESULT
                DO STOP_ANALYSIS
                END
                """);
        List<RuleScript.Action> dispatched = new ArrayList<>();

        RuleEngine.ExecutionResult result = new RuleEngine().execute(
                script, RuleScript.Event.ENGINE_RESULT, matchingContext(),
                RuleEngine.Mode.LIVE, new AutomationSafetyKernel(), dispatched::add,
                new RuleEngine.ExecutionOptions(2, 1_000, () -> false));

        assertEquals(RuleEngine.ExecutionStatus.STEP_LIMIT, result.status());
        assertEquals(2, result.steps());
        assertEquals(List.of(RuleScript.Action.startAnalysis()), dispatched);
    }

    @Test
    void cancellationAndTimeoutStopCooperatively() {
        RuleScript script = RuleScript.parse(TACTICAL_SCRIPT);
        AtomicInteger dispatched = new AtomicInteger();

        RuleEngine.ExecutionResult cancelled = new RuleEngine().execute(
                script, RuleScript.Event.ENGINE_RESULT, matchingContext(),
                RuleEngine.Mode.LIVE, new AutomationSafetyKernel(),
                action -> dispatched.incrementAndGet(),
                new RuleEngine.ExecutionOptions(100, 1_000, () -> true));

        AtomicLong nanoTime = new AtomicLong();
        RuleEngine timedEngine = new RuleEngine(
                () -> nanoTime.getAndAdd(2_000_000));
        RuleEngine.ExecutionResult timedOut = timedEngine.execute(
                script, RuleScript.Event.ENGINE_RESULT, matchingContext(),
                RuleEngine.Mode.LIVE, new AutomationSafetyKernel(),
                action -> dispatched.incrementAndGet(),
                new RuleEngine.ExecutionOptions(100, 1, () -> false));

        assertEquals(RuleEngine.ExecutionStatus.CANCELLED, cancelled.status());
        assertEquals(RuleEngine.ExecutionStatus.TIMED_OUT, timedOut.status());
        assertEquals(0, dispatched.get());
    }

    @Test
    void actionFailureIsBoundedAndDoesNotCopyItsSensitiveMessageIntoAudit() {
        RuleScript script = RuleScript.parse("""
                PDSCRIPT 1
                RULE fail
                WHEN ENGINE_RESULT
                DO START_ANALYSIS
                END
                """);

        RuleEngine.ExecutionResult result = new RuleEngine().execute(
                script, RuleScript.Event.ENGINE_RESULT, matchingContext(),
                RuleEngine.Mode.LIVE, new AutomationSafetyKernel(),
                action -> { throw new IllegalStateException("C:\\Private\\secret.txt"); },
                RuleEngine.ExecutionOptions.defaults());

        assertEquals(RuleEngine.ExecutionStatus.FAILED, result.status());
        assertTrue(result.audit().stream().anyMatch(entry ->
                entry.kind() == RuleEngine.AuditKind.ACTION_FAILED));
        assertTrue(result.audit().stream().noneMatch(entry ->
                entry.detail().contains("Private") || entry.detail().contains("secret")));
    }

    @Test
    void timeoutIsRecheckedAfterTheLastBoundedActionReturns() {
        RuleScript script = RuleScript.parse("""
                PDSCRIPT 1
                RULE slow
                WHEN ENGINE_RESULT
                DO START_ANALYSIS
                END
                """);
        long[] ticks = {0, 0, 0, 2_000_000};
        AtomicInteger tick = new AtomicInteger();
        RuleEngine engine = new RuleEngine(
                () -> ticks[Math.min(tick.getAndIncrement(), ticks.length - 1)]);
        AtomicInteger dispatched = new AtomicInteger();

        RuleEngine.ExecutionResult result = engine.execute(
                script, RuleScript.Event.ENGINE_RESULT, matchingContext(),
                RuleEngine.Mode.LIVE, new AutomationSafetyKernel(),
                action -> dispatched.incrementAndGet(),
                new RuleEngine.ExecutionOptions(100, 1, () -> false));

        assertEquals(1, dispatched.get());
        assertEquals(RuleEngine.ExecutionStatus.TIMED_OUT, result.status());
    }

    @Test
    void parserEnforcesSourceLineRuleConditionAndActionLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> RuleScript.parse("x".repeat(RuleScript.MAX_SOURCE_BYTES + 1)));
        assertThrows(IllegalArgumentException.class, () -> RuleScript.parse(
                "PDSCRIPT 1\n" + "X".repeat(RuleScript.MAX_LINE_CHARS + 1)));

        StringBuilder tooManyRules = new StringBuilder("PDSCRIPT 1\n");
        for (int i = 0; i <= RuleScript.MAX_RULES; i++) {
            tooManyRules.append("RULE r").append(i)
                    .append("\nWHEN ENGINE_RESULT\nDO START_ANALYSIS\nEND\n");
        }
        assertThrows(IllegalArgumentException.class,
                () -> RuleScript.parse(tooManyRules.toString()));

        String tooManyConditions = "PDSCRIPT 1\nRULE c\nWHEN ENGINE_RESULT\n"
                + "IF SCORE_CP LE 0\n".repeat(RuleScript.MAX_CONDITIONS_PER_RULE + 1)
                + "DO START_ANALYSIS\nEND\n";
        assertThrows(IllegalArgumentException.class,
                () -> RuleScript.parse(tooManyConditions));

        String tooManyActions = "PDSCRIPT 1\nRULE a\nWHEN ENGINE_RESULT\n"
                + "DO START_ANALYSIS\n".repeat(RuleScript.MAX_ACTIONS_PER_RULE + 1)
                + "END\n";
        assertThrows(IllegalArgumentException.class,
                () -> RuleScript.parse(tooManyActions));
    }

    @Test
    void boundedAsciiFuzzNeverEscapesTheParserContract() {
        Random random = new Random(0x50444c53L);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 "
                + "_-/\\:;.$()[]{}\n\t";

        for (int sample = 0; sample < 2_000; sample++) {
            int length = random.nextInt(400);
            StringBuilder source = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                source.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            try {
                RuleScript parsed = RuleScript.parse(source.toString());
                assertTrue(parsed.rules().size() <= RuleScript.MAX_RULES);
                assertEquals(parsed, RuleScript.parse(parsed.serialize()));
            } catch (IllegalArgumentException expectedRejection) {
                assertFalse(expectedRejection.getMessage().isBlank());
            }
        }
    }

    private static RuleEngine.Context matchingContext() {
        return new RuleEngine.Context(-200, 75, 5_000, 500,
                TimeStrategy.Phase.MIDDLEGAME, RuleScript.Side.RED);
    }

    private static AutomationSafetyKernel readyKernel() {
        AutomationSafetyKernel kernel = new AutomationSafetyKernel();
        assertTrue(kernel.arm(new AutomationSafetyKernel.Authorization("local-test-board", 1)));
        assertTrue(kernel.beginObservation());
        assertTrue(kernel.recognitionAccepted());
        assertTrue(kernel.beginThinking());
        assertTrue(kernel.readyToExecute());
        return kernel;
    }

    private static AutomationSafetyKernel kernelAfterTransitions(int transitionsAfterArm) {
        AutomationSafetyKernel kernel = new AutomationSafetyKernel();
        assertTrue(kernel.arm(new AutomationSafetyKernel.Authorization("local-test-board", 1)));
        if (transitionsAfterArm >= 1) assertTrue(kernel.beginObservation());
        if (transitionsAfterArm >= 2) assertTrue(kernel.recognitionAccepted());
        if (transitionsAfterArm >= 3) assertTrue(kernel.beginThinking());
        return kernel;
    }
}
