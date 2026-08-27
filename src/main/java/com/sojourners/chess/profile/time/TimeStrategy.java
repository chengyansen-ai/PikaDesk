package com.sojourners.chess.profile.time;

import java.util.List;
import java.util.Objects;

/**
 * Pure outer time-budget policy for one engine search.
 *
 * <p>This class produces a conservative {@code movetime}-style ceiling from explicit inputs;
 * it does not reproduce the engine's clock-mode search heuristics and never reads a clock,
 * random source, application setting, or engine state.</p>
 */
public final class TimeStrategy {

    private static final int BASIS_POINTS = 10_000;
    private static final long FIXED_SAFETY_MILLIS = 250;
    private static final int SAFETY_RATIO_BASIS_POINTS = 300;
    private static final int INCREMENT_USE_BASIS_POINTS = 8_000;
    private static final int HARD_LIMIT_SHARE_BASIS_POINTS = 2_500;

    private TimeStrategy() {
    }

    public static Decision decide(Input input) {
        Objects.requireNonNull(input, "input");

        long percentageReserve = scaleCeiling(
                input.remainingMillis(), SAFETY_RATIO_BASIS_POINTS);
        long safetyReserve = Math.min(input.remainingMillis(),
                Math.max(FIXED_SAFETY_MILLIS, percentageReserve));
        long spendable = input.remainingMillis() - safetyReserve;

        long incrementShare = scaleRounded(
                Math.min(input.incrementMillis(), spendable),
                INCREMENT_USE_BASIS_POINTS, spendable);
        long clockShare = spendable / input.movesToGo();
        long baseShare = cappedAdd(clockShare, incrementShare, spendable);

        long quarterShare = scaleCeiling(spendable, HARD_LIMIT_SHARE_BASIS_POINTS);
        long hardLimit = Math.min(spendable, Math.max(baseShare, quarterShare));

        int phaseFactor = input.phase().basisPoints();
        int scoreFactor = scoreBasisPoints(input.scoreCp());
        int complexityFactor = complexityBasisPoints(input.complexity());
        long combinedFactor = combinedBasisPoints(
                phaseFactor, scoreFactor, complexityFactor);
        long target = scaleRounded(baseShare, combinedFactor, hardLimit);
        if (target == 0 && hardLimit > 0) {
            target = 1;
        }

        List<String> explanations = List.of(
                "基础份额：可支配 " + spendable + " ms ÷ " + input.movesToGo()
                        + " 步 + 80% 增益 " + incrementShare + " ms = " + baseShare + " ms",
                "阶段：" + input.phase().label() + "，系数 " + factor(phaseFactor),
                "比分：" + input.scoreCp() + " cp，" + scoreBand(input.scoreCp())
                        + "，系数 " + factor(scoreFactor),
                "复杂度：" + input.complexity() + "/100，"
                        + complexityBand(input.complexity()) + "，系数 "
                        + factor(complexityFactor),
                "安全余量：" + safetyReserve + " ms；单步硬上限：" + hardLimit + " ms");

        return new Decision(target, hardLimit, safetyReserve, spendable, baseShare,
                phaseFactor, scoreFactor, complexityFactor, explanations);
    }

    private static int scoreBasisPoints(int scoreCp) {
        long absoluteScore = Math.abs((long) scoreCp);
        if (absoluteScore <= 75) return 12_000;
        if (absoluteScore <= 250) return 11_000;
        if (absoluteScore <= 700) return 10_000;
        return 8_500;
    }

    private static String scoreBand(int scoreCp) {
        long absoluteScore = Math.abs((long) scoreCp);
        if (absoluteScore <= 75) return "接近均势";
        if (absoluteScore <= 250) return "轻度失衡";
        if (absoluteScore <= 700) return "明显失衡";
        return "大幅失衡";
    }

    private static int complexityBasisPoints(int complexity) {
        if (complexity < 25) return 8_000;
        if (complexity < 50) return 9_500;
        if (complexity < 75) return 11_000;
        return 13_000;
    }

    private static String complexityBand(int complexity) {
        if (complexity < 25) return "低";
        if (complexity < 50) return "中低";
        if (complexity < 75) return "中高";
        return "高";
    }

    private static long combinedBasisPoints(int first, int second, int third) {
        long product = (long) first * second * third;
        return (product + 50_000_000L) / 100_000_000L;
    }

    private static long scaleCeiling(long value, int factorBasisPoints) {
        long quotient = value / BASIS_POINTS;
        long remainder = value % BASIS_POINTS;
        long whole = quotient * factorBasisPoints;
        long fraction = (remainder * factorBasisPoints + BASIS_POINTS - 1)
                / BASIS_POINTS;
        return whole + fraction;
    }

    private static long scaleRounded(long value, long factorBasisPoints, long cap) {
        if (value == 0 || factorBasisPoints == 0 || cap == 0) return 0;
        long quotient = value / BASIS_POINTS;
        long remainder = value % BASIS_POINTS;
        if (quotient > cap / factorBasisPoints) return cap;
        long whole = quotient * factorBasisPoints;
        long fraction = (remainder * factorBasisPoints + BASIS_POINTS / 2)
                / BASIS_POINTS;
        if (whole >= cap || fraction > cap - whole) return cap;
        return whole + fraction;
    }

    private static long cappedAdd(long first, long second, long cap) {
        if (first >= cap || second >= cap - first) return cap;
        return first + second;
    }

    private static String factor(int basisPoints) {
        return String.format(java.util.Locale.ROOT, "%.2f× (%d/10000)",
                basisPoints / (double) BASIS_POINTS, basisPoints);
    }

    public enum Phase {
        OPENING("开局", 8_500),
        MIDDLEGAME("中局", 11_500),
        ENDGAME("残局", 9_500);

        private final String label;
        private final int basisPoints;

        Phase(String label, int basisPoints) {
            this.label = label;
            this.basisPoints = basisPoints;
        }

        public String label() {
            return label;
        }

        public int basisPoints() {
            return basisPoints;
        }
    }

    /**
     * @param remainingMillis current side's clock before this move
     * @param incrementMillis increment expected after a completed move
     * @param movesToGo estimated moves this clock must cover, from 1 through 200
     * @param phase caller-classified Xiangqi game phase
     * @param scoreCp engine score from the side-to-move perspective, in centipawns
     * @param complexity caller-normalized position complexity from 0 through 100
     */
    public record Input(long remainingMillis,
                        long incrementMillis,
                        int movesToGo,
                        Phase phase,
                        int scoreCp,
                        int complexity) {

        public Input {
            if (remainingMillis < 0) {
                throw new IllegalArgumentException("remainingMillis must not be negative");
            }
            if (incrementMillis < 0) {
                throw new IllegalArgumentException("incrementMillis must not be negative");
            }
            if (movesToGo < 1 || movesToGo > 200) {
                throw new IllegalArgumentException("movesToGo must be between 1 and 200");
            }
            Objects.requireNonNull(phase, "phase");
            if (scoreCp < -100_000 || scoreCp > 100_000) {
                throw new IllegalArgumentException("scoreCp must be between -100000 and 100000");
            }
            if (complexity < 0 || complexity > 100) {
                throw new IllegalArgumentException("complexity must be between 0 and 100");
            }
        }
    }

    public record Decision(long targetMillis,
                           long hardLimitMillis,
                           long safetyReserveMillis,
                           long spendableMillis,
                           long baseShareMillis,
                           int phaseBasisPoints,
                           int scoreBasisPoints,
                           int complexityBasisPoints,
                           List<String> explanations) {

        public Decision {
            if (targetMillis < 0 || targetMillis > hardLimitMillis
                    || hardLimitMillis > spendableMillis
                    || baseShareMillis > hardLimitMillis) {
                throw new IllegalArgumentException("invalid time budget ordering");
            }
            if (safetyReserveMillis < 0 || spendableMillis < 0 || baseShareMillis < 0) {
                throw new IllegalArgumentException("time budget values must not be negative");
            }
            explanations = List.copyOf(Objects.requireNonNull(explanations, "explanations"));
            if (explanations.isEmpty()) {
                throw new IllegalArgumentException("explanations must not be empty");
            }
        }
    }
}
