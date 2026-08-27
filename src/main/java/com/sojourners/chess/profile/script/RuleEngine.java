package com.sojourners.chess.profile.script;

import com.sojourners.chess.automation.AutomationSafetyKernel;
import com.sojourners.chess.automation.AutomationState;
import com.sojourners.chess.profile.time.TimeStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Executes only {@link RuleScript.ActionType} values through an application-owned sink. */
public final class RuleEngine {

    private final LongSupplier nanoTime;

    public RuleEngine() {
        this(System::nanoTime);
    }

    RuleEngine(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public ExecutionResult execute(RuleScript script,
                                   RuleScript.Event event,
                                   Context context,
                                   Mode mode,
                                   AutomationSafetyKernel automationKernel,
                                   ActionSink actionSink,
                                   ExecutionOptions options) {
        Objects.requireNonNull(script, "script");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(automationKernel, "automationKernel");
        Objects.requireNonNull(actionSink, "actionSink");
        Objects.requireNonNull(options, "options");

        RunState state = new RunState(options.maxSteps());
        long startNanos = mode == Mode.LIVE ? nanoTime.getAsLong() : 0;
        for (RuleScript.Rule rule : script.rules()) {
            ExecutionStatus stopped = stopStatus(mode, options, startNanos);
            if (stopped != null) return state.finish(stopped, "execution stopped");
            if (!state.takeStep()) return state.finish(
                    ExecutionStatus.STEP_LIMIT, "step limit reached");

            if (rule.event() != event) {
                state.audit(rule.id(), AuditKind.RULE_SKIPPED,
                        "event mismatch: expected " + rule.event());
                continue;
            }

            boolean matches = true;
            for (RuleScript.Condition condition : rule.conditions()) {
                stopped = stopStatus(mode, options, startNanos);
                if (stopped != null) return state.finish(stopped, "execution stopped");
                if (!state.takeStep()) return state.finish(
                        ExecutionStatus.STEP_LIMIT, "step limit reached");
                if (!matches(condition, context, automationKernel.state())) {
                    state.audit(rule.id(), AuditKind.CONDITION_FALSE,
                            condition.field() + " " + condition.operator()
                                    + " " + condition.operand());
                    matches = false;
                    break;
                }
            }
            if (!matches) continue;

            for (RuleScript.Action action : rule.actions()) {
                stopped = stopStatus(mode, options, startNanos);
                if (stopped != null) return state.finish(stopped, "execution stopped");
                if (!state.takeStep()) return state.finish(
                        ExecutionStatus.STEP_LIMIT, "step limit reached");

                if (action.type() == RuleScript.ActionType.REQUEST_AUTHORIZED_MOVE
                        && !canRequestMove(automationKernel)) {
                    state.audit(rule.id(), AuditKind.ACTION_BLOCKED,
                            "automation kernel is not READY with an authorization");
                    return state.finish(ExecutionStatus.BLOCKED,
                            "authorized move request blocked");
                }
                if (mode == Mode.DRY_RUN) {
                    state.audit(rule.id(), AuditKind.ACTION_PREVIEW, actionSummary(action));
                    continue;
                }
                try {
                    actionSink.dispatch(action);
                    state.audit(rule.id(), AuditKind.ACTION_DISPATCHED, actionSummary(action));
                } catch (Exception failure) {
                    state.audit(rule.id(), AuditKind.ACTION_FAILED,
                            action.type() + " failed: " + failure.getClass().getSimpleName());
                    return state.finish(ExecutionStatus.FAILED, "action sink failed");
                }
                stopped = stopStatus(mode, options, startNanos);
                if (stopped != null) return state.finish(stopped, "execution stopped");
            }
        }
        return state.finish(ExecutionStatus.COMPLETED, "execution completed");
    }

    private ExecutionStatus stopStatus(Mode mode,
                                       ExecutionOptions options,
                                       long startNanos) {
        try {
            if (options.cancelled().getAsBoolean()) return ExecutionStatus.CANCELLED;
        } catch (RuntimeException failure) {
            return ExecutionStatus.FAILED;
        }
        if (mode == Mode.LIVE
                && nanoTime.getAsLong() - startNanos >= options.timeoutMillis() * 1_000_000L) {
            return ExecutionStatus.TIMED_OUT;
        }
        return null;
    }

    private static boolean canRequestMove(AutomationSafetyKernel kernel) {
        return kernel.state() == AutomationState.READY && kernel.authorization().isPresent();
    }

    private static boolean matches(RuleScript.Condition condition,
                                   Context context,
                                   AutomationState automationState) {
        return switch (condition.field()) {
            case SCORE_CP -> compare(context.scoreCp(), condition);
            case COMPLEXITY -> compare(context.complexity(), condition);
            case REMAINING_MILLIS -> compare(context.remainingMillis(), condition);
            case TIME_TARGET_MILLIS -> compare(context.timeTargetMillis(), condition);
            case PHASE -> context.phase().name().equals(condition.operand());
            case SIDE -> context.side().name().equals(condition.operand());
            case AUTOMATION_STATE -> automationState.name().equals(condition.operand());
        };
    }

    private static boolean compare(long actual, RuleScript.Condition condition) {
        long expected = condition.numericValue();
        return switch (condition.operator()) {
            case LE -> actual <= expected;
            case GE -> actual >= expected;
            case EQ -> throw new IllegalStateException("numeric EQ passed validation");
        };
    }

    private static String actionSummary(RuleScript.Action action) {
        return switch (action.type()) {
            case SET_TIME_SCALE -> action.type() + " " + action.value();
            case SHOW_NOTICE -> action.type() + " " + action.notice();
            default -> action.type().toString();
        };
    }

    public enum Mode {
        DRY_RUN,
        LIVE
    }

    public enum ExecutionStatus {
        COMPLETED,
        BLOCKED,
        CANCELLED,
        TIMED_OUT,
        STEP_LIMIT,
        FAILED
    }

    public enum AuditKind {
        RULE_SKIPPED,
        CONDITION_FALSE,
        ACTION_PREVIEW,
        ACTION_DISPATCHED,
        ACTION_BLOCKED,
        ACTION_FAILED,
        TERMINAL
    }

    public record Context(int scoreCp,
                          int complexity,
                          long remainingMillis,
                          long timeTargetMillis,
                          TimeStrategy.Phase phase,
                          RuleScript.Side side) {

        public Context {
            if (scoreCp < -100_000 || scoreCp > 100_000) {
                throw new IllegalArgumentException("scoreCp must be between -100000 and 100000");
            }
            if (complexity < 0 || complexity > 100) {
                throw new IllegalArgumentException("complexity must be between 0 and 100");
            }
            if (remainingMillis < 0) {
                throw new IllegalArgumentException("remainingMillis must not be negative");
            }
            if (timeTargetMillis < 0 || timeTargetMillis > remainingMillis) {
                throw new IllegalArgumentException(
                        "timeTargetMillis must be between zero and remainingMillis");
            }
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(side, "side");
        }
    }

    public record ExecutionOptions(int maxSteps,
                                   long timeoutMillis,
                                   BooleanSupplier cancelled) {

        public ExecutionOptions {
            if (maxSteps < 1 || maxSteps > 2_048) {
                throw new IllegalArgumentException("maxSteps must be between 1 and 2048");
            }
            if (timeoutMillis < 1 || timeoutMillis > 5_000) {
                throw new IllegalArgumentException("timeoutMillis must be between 1 and 5000");
            }
            Objects.requireNonNull(cancelled, "cancelled");
        }

        public static ExecutionOptions defaults() {
            return new ExecutionOptions(2_048, 250, () -> false);
        }
    }

    public record AuditEntry(int sequence,
                             String ruleId,
                             AuditKind kind,
                             String detail) {

        public AuditEntry {
            if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
            ruleId = Objects.requireNonNull(ruleId, "ruleId");
            Objects.requireNonNull(kind, "kind");
            detail = Objects.requireNonNull(detail, "detail");
            if (detail.length() > 160 || detail.indexOf('\n') >= 0 || detail.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("audit detail is outside its limits");
            }
        }
    }

    public record ExecutionResult(ExecutionStatus status,
                                  int steps,
                                  List<AuditEntry> audit) {

        public ExecutionResult {
            Objects.requireNonNull(status, "status");
            if (steps < 0 || steps > 2_048) {
                throw new IllegalArgumentException("invalid step count");
            }
            audit = List.copyOf(Objects.requireNonNull(audit, "audit"));
            if (audit.size() > steps + 1) {
                throw new IllegalArgumentException("audit exceeds step bound");
            }
        }
    }

    @FunctionalInterface
    public interface ActionSink {
        /** Dispatches one high-level action; external input must still use AutomationSafetyKernel. */
        void dispatch(RuleScript.Action action) throws Exception;
    }

    private static final class RunState {
        private final int maxSteps;
        private final List<AuditEntry> audit = new ArrayList<>();
        private int steps;

        private RunState(int maxSteps) {
            this.maxSteps = maxSteps;
        }

        private boolean takeStep() {
            if (steps >= maxSteps) return false;
            steps++;
            return true;
        }

        private void audit(String ruleId, AuditKind kind, String detail) {
            audit.add(new AuditEntry(audit.size() + 1, ruleId, kind, detail));
        }

        private ExecutionResult finish(ExecutionStatus status, String detail) {
            audit("-", AuditKind.TERMINAL, detail);
            return new ExecutionResult(status, steps, audit);
        }
    }
}
