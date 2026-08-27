package com.sojourners.chess.profile.time;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TimeStrategyTest {

    @ParameterizedTest
    @CsvSource({
            "OPENING,8500",
            "MIDDLEGAME,11500",
            "ENDGAME,9500"
    })
    void exposesEveryPhaseBoundary(TimeStrategy.Phase phase, int expectedBasisPoints) {
        TimeStrategy.Decision decision = TimeStrategy.decide(input(0, phase, 50));

        assertEquals(expectedBasisPoints, decision.phaseBasisPoints());
    }

    @ParameterizedTest
    @CsvSource({
            "-100000,8500", "-701,8500", "-700,10000", "-251,10000",
            "-250,11000", "-76,11000", "-75,12000", "0,12000",
            "75,12000", "76,11000", "250,11000", "251,10000",
            "700,10000", "701,8500", "100000,8500"
    })
    void exposesEveryScoreThreshold(int scoreCp, int expectedBasisPoints) {
        TimeStrategy.Decision decision = TimeStrategy.decide(
                input(scoreCp, TimeStrategy.Phase.MIDDLEGAME, 50));

        assertEquals(expectedBasisPoints, decision.scoreBasisPoints());
    }

    @ParameterizedTest
    @CsvSource({
            "0,8000", "24,8000", "25,9500", "49,9500",
            "50,11000", "74,11000", "75,13000", "100,13000"
    })
    void exposesEveryComplexityThreshold(int complexity, int expectedBasisPoints) {
        TimeStrategy.Decision decision = TimeStrategy.decide(
                input(0, TimeStrategy.Phase.MIDDLEGAME, complexity));

        assertEquals(expectedBasisPoints, decision.complexityBasisPoints());
    }

    @ParameterizedTest
    @CsvSource({
            "0,0,0",
            "249,249,0",
            "250,250,0",
            "251,250,1",
            "8333,250,8083",
            "8334,251,8083"
    })
    void reserveBoundaryNeverConsumesProtectedTime(long remaining,
                                                    long expectedReserve,
                                                    long expectedSpendable) {
        TimeStrategy.Decision decision = TimeStrategy.decide(
                new TimeStrategy.Input(remaining, 0, 20,
                        TimeStrategy.Phase.MIDDLEGAME, 0, 50));

        assertEquals(expectedReserve, decision.safetyReserveMillis());
        assertEquals(expectedSpendable, decision.spendableMillis());
        assertTrue(decision.targetMillis() <= expectedSpendable);
        assertTrue(decision.hardLimitMillis() <= expectedSpendable);
    }

    @Test
    void combinesAllInputsIntoAnExplainableDeterministicBudget() {
        TimeStrategy.Input input = new TimeStrategy.Input(
                100_000, 1_000, 20,
                TimeStrategy.Phase.MIDDLEGAME, 100, 50);

        TimeStrategy.Decision first = TimeStrategy.decide(input);
        TimeStrategy.Decision second = TimeStrategy.decide(input);

        assertEquals(first, second);
        assertEquals(3_000, first.safetyReserveMillis());
        assertEquals(97_000, first.spendableMillis());
        assertEquals(5_650, first.baseShareMillis());
        assertEquals(24_250, first.hardLimitMillis());
        assertEquals(7_862, first.targetMillis());
        assertTrue(first.explanations().stream().anyMatch(text -> text.contains("20")));
        assertTrue(first.explanations().stream().anyMatch(text -> text.contains("中局")));
        assertTrue(first.explanations().stream().anyMatch(text -> text.contains("100")));
        assertTrue(first.explanations().stream().anyMatch(text -> text.contains("50")));
        assertTrue(first.explanations().stream().anyMatch(text -> text.contains("安全余量")));
    }

    @ParameterizedTest
    @MethodSource("validBoundaryInputs")
    void targetAndHardLimitAlwaysStayInsideRemainingTime(TimeStrategy.Input input) {
        TimeStrategy.Decision decision = TimeStrategy.decide(input);

        assertTrue(decision.targetMillis() >= 0);
        assertTrue(decision.targetMillis() <= decision.hardLimitMillis());
        assertTrue(decision.hardLimitMillis() <= decision.spendableMillis());
        assertTrue(decision.spendableMillis() + decision.safetyReserveMillis()
                <= input.remainingMillis());
        assertFalse(decision.explanations().isEmpty());
    }

    @Test
    void moreComplexPositionsNeverReceiveLessTimeBeforeTheCap() {
        long previous = -1;
        for (int complexity = 0; complexity <= 100; complexity++) {
            long current = TimeStrategy.decide(
                    input(0, TimeStrategy.Phase.MIDDLEGAME, complexity)).targetMillis();
            assertTrue(current >= previous);
            previous = current;
        }
    }

    @Test
    void incrementCanIncreaseTargetButNeverInvadesReserve() {
        TimeStrategy.Input withoutIncrement = new TimeStrategy.Input(
                20_000, 0, 30, TimeStrategy.Phase.OPENING, 500, 24);
        TimeStrategy.Input withIncrement = new TimeStrategy.Input(
                20_000, Long.MAX_VALUE, 30, TimeStrategy.Phase.OPENING, 500, 24);

        TimeStrategy.Decision lower = TimeStrategy.decide(withoutIncrement);
        TimeStrategy.Decision higher = TimeStrategy.decide(withIncrement);

        assertTrue(higher.targetMillis() >= lower.targetMillis());
        assertTrue(higher.targetMillis() <= 20_000 - higher.safetyReserveMillis());
    }

    @Test
    void everyStrategicInputAffectsTheAllocatedTarget() {
        TimeStrategy.Input baseline = new TimeStrategy.Input(
                100_000, 0, 20, TimeStrategy.Phase.MIDDLEGAME, 0, 50);
        long target = TimeStrategy.decide(baseline).targetMillis();

        assertTrue(TimeStrategy.decide(new TimeStrategy.Input(
                100_000, 0, 20, TimeStrategy.Phase.OPENING, 0, 50))
                .targetMillis() < target);
        assertTrue(TimeStrategy.decide(new TimeStrategy.Input(
                100_000, 0, 20, TimeStrategy.Phase.MIDDLEGAME, 701, 50))
                .targetMillis() < target);
        assertTrue(TimeStrategy.decide(new TimeStrategy.Input(
                100_000, 0, 20, TimeStrategy.Phase.MIDDLEGAME, 0, 24))
                .targetMillis() < target);
        assertTrue(TimeStrategy.decide(new TimeStrategy.Input(
                100_000, 0, 40, TimeStrategy.Phase.MIDDLEGAME, 0, 50))
                .targetMillis() < target);
        assertTrue(TimeStrategy.decide(new TimeStrategy.Input(
                200_000, 0, 20, TimeStrategy.Phase.MIDDLEGAME, 0, 50))
                .targetMillis() > target);
        assertTrue(TimeStrategy.decide(new TimeStrategy.Input(
                100_000, 1_000, 20, TimeStrategy.Phase.MIDDLEGAME, 0, 50))
                .targetMillis() > target);
    }

    @ParameterizedTest
    @CsvSource({
            "-1,0,20,0,50",
            "1000,-1,20,0,50",
            "1000,0,0,0,50",
            "1000,0,201,0,50",
            "1000,0,20,-100001,50",
            "1000,0,20,100001,50",
            "1000,0,20,0,-1",
            "1000,0,20,0,101"
    })
    void rejectsInvalidInputs(long remainingMillis,
                              long incrementMillis,
                              int movesToGo,
                              int scoreCp,
                              int complexity) {
        assertThrows(IllegalArgumentException.class, () -> new TimeStrategy.Input(
                remainingMillis, incrementMillis, movesToGo,
                TimeStrategy.Phase.OPENING, scoreCp, complexity));
    }

    @Test
    void rejectsNullInputAndPhase() {
        assertThrows(NullPointerException.class, () -> TimeStrategy.decide(null));
        assertThrows(NullPointerException.class, () -> new TimeStrategy.Input(
                1_000, 0, 20, null, 0, 50));
    }

    @Test
    void rejectsAResultWhoseBaseShareExceedsItsHardLimit() {
        assertThrows(IllegalArgumentException.class, () -> new TimeStrategy.Decision(
                1, 2, 0, 2, 3,
                10_000, 10_000, 10_000, java.util.List.of("invalid")));
    }

    private static TimeStrategy.Input input(int scoreCp,
                                            TimeStrategy.Phase phase,
                                            int complexity) {
        return new TimeStrategy.Input(100_000, 0, 20, phase, scoreCp, complexity);
    }

    private static Stream<TimeStrategy.Input> validBoundaryInputs() {
        return Stream.of(
                new TimeStrategy.Input(0, 0, 1,
                        TimeStrategy.Phase.OPENING, -100_000, 0),
                new TimeStrategy.Input(1, Long.MAX_VALUE, 1,
                        TimeStrategy.Phase.ENDGAME, 100_000, 100),
                new TimeStrategy.Input(250, 0, 200,
                        TimeStrategy.Phase.MIDDLEGAME, 0, 50),
                new TimeStrategy.Input(Long.MAX_VALUE, Long.MAX_VALUE, 200,
                        TimeStrategy.Phase.MIDDLEGAME, 0, 100));
    }

}
