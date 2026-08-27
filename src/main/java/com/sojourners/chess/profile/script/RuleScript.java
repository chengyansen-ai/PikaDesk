package com.sojourners.chess.profile.script;

import com.sojourners.chess.automation.AutomationState;
import com.sojourners.chess.profile.time.TimeStrategy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Versioned, non-executable event-condition-action document. */
public record RuleScript(int schemaVersion, List<Rule> rules) {

    public static final int CURRENT_VERSION = 1;
    public static final int MAX_SOURCE_BYTES = 65_536;
    public static final int MAX_LINE_CHARS = 512;
    public static final int MAX_RULES = 128;
    public static final int MAX_CONDITIONS_PER_RULE = 16;
    public static final int MAX_ACTIONS_PER_RULE = 8;
    public static final int MAX_TOTAL_INSTRUCTIONS = 1_024;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,31}");

    public RuleScript {
        if (schemaVersion != CURRENT_VERSION) {
            throw new IllegalArgumentException("unsupported script version: " + schemaVersion);
        }
        rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        if (rules.isEmpty() || rules.size() > MAX_RULES) {
            throw new IllegalArgumentException("script must contain between 1 and "
                    + MAX_RULES + " rules");
        }
        Set<String> ids = new HashSet<>();
        int instructions = 0;
        for (Rule rule : rules) {
            Objects.requireNonNull(rule, "rule");
            if (!ids.add(rule.id())) {
                throw new IllegalArgumentException("duplicate rule id: " + rule.id());
            }
            instructions += rule.conditions().size() + rule.actions().size();
            if (instructions > MAX_TOTAL_INSTRUCTIONS) {
                throw new IllegalArgumentException("script instruction limit exceeded");
            }
        }
    }

    public static RuleScript parse(String source) {
        Objects.requireNonNull(source, "source");
        if (source.length() > MAX_SOURCE_BYTES
                || source.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("script exceeds " + MAX_SOURCE_BYTES + " bytes");
        }

        String[] lines = source.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            if (lines[index].length() > MAX_LINE_CHARS) {
                throw parseError(index + 1,
                        "line exceeds " + MAX_LINE_CHARS + " characters");
            }
        }
        if (lines.length == 0 || !lines[0].strip().equals("PDSCRIPT 1")) {
            throw parseError(1, "expected PDSCRIPT 1 header");
        }

        List<Rule> rules = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        RuleBuilder current = null;
        for (int index = 1; index < lines.length; index++) {
            int lineNumber = index + 1;
            String rawLine = lines[index];
            String line = rawLine.strip();
            if (line.isEmpty()) continue;
            String[] tokens = line.split("[ \\t]+");

            if (current == null) {
                if (tokens.length != 2 || !tokens[0].equals("RULE")) {
                    throw parseError(lineNumber, "expected RULE <id>");
                }
                if (rules.size() >= MAX_RULES) {
                    throw parseError(lineNumber, "rule limit exceeded");
                }
                if (!IDENTIFIER.matcher(tokens[1]).matches()) {
                    throw parseError(lineNumber, "invalid rule id");
                }
                if (!ids.add(tokens[1])) {
                    throw parseError(lineNumber, "duplicate rule id: " + tokens[1]);
                }
                current = new RuleBuilder(tokens[1]);
                continue;
            }

            switch (tokens[0]) {
                case "WHEN" -> {
                    if (tokens.length != 2 || current.event != null
                            || !current.conditions.isEmpty() || !current.actions.isEmpty()) {
                        throw parseError(lineNumber, "WHEN must appear once immediately after RULE");
                    }
                    current.event = enumValue(Event.class, tokens[1], lineNumber, "event");
                }
                case "IF" -> {
                    if (current.event == null || !current.actions.isEmpty()) {
                        throw parseError(lineNumber, "IF must appear after WHEN and before DO");
                    }
                    if (current.conditions.size() >= MAX_CONDITIONS_PER_RULE) {
                        throw parseError(lineNumber, "condition limit exceeded");
                    }
                    if (tokens.length != 4) {
                        throw parseError(lineNumber, "IF requires field, operator, and operand");
                    }
                    current.conditions.add(new Condition(
                            enumValue(Field.class, tokens[1], lineNumber, "condition field"),
                            enumValue(Operator.class, tokens[2], lineNumber, "condition operator"),
                            tokens[3]));
                }
                case "DO" -> {
                    if (current.event == null) {
                        throw parseError(lineNumber, "DO must appear after WHEN");
                    }
                    if (current.actions.size() >= MAX_ACTIONS_PER_RULE) {
                        throw parseError(lineNumber, "action limit exceeded");
                    }
                    current.actions.add(parseAction(tokens, lineNumber));
                }
                case "END" -> {
                    if (tokens.length != 1 || current.event == null
                            || current.actions.isEmpty()) {
                        throw parseError(lineNumber, "END requires one WHEN and at least one DO");
                    }
                    rules.add(current.build());
                    current = null;
                }
                default -> throw parseError(lineNumber, "unknown statement: " + tokens[0]);
            }
        }
        if (current != null) {
            throw parseError(lines.length, "unterminated rule: " + current.id);
        }
        try {
            return new RuleScript(CURRENT_VERSION, rules);
        } catch (IllegalArgumentException invalid) {
            throw parseError(lines.length, invalid.getMessage());
        }
    }

    public String serialize() {
        StringBuilder output = new StringBuilder("PDSCRIPT ")
                .append(CURRENT_VERSION).append('\n');
        for (Rule rule : rules) {
            output.append("RULE ").append(rule.id()).append('\n');
            output.append("WHEN ").append(rule.event()).append('\n');
            for (Condition condition : rule.conditions()) {
                output.append("IF ").append(condition.field()).append(' ')
                        .append(condition.operator()).append(' ')
                        .append(condition.operand()).append('\n');
            }
            for (Action action : rule.actions()) {
                output.append("DO ").append(action.serialize()).append('\n');
            }
            output.append("END\n");
        }
        String serialized = output.toString();
        if (serialized.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) {
            throw new IllegalStateException("serialized script exceeds size limit");
        }
        return serialized;
    }

    private static Action parseAction(String[] tokens, int lineNumber) {
        ActionType type = enumValue(ActionType.class,
                tokens.length > 1 ? tokens[1] : "", lineNumber, "action");
        try {
            return switch (type) {
                case START_ANALYSIS -> exactAction(tokens, Action.startAnalysis());
                case STOP_ANALYSIS -> exactAction(tokens, Action.stopAnalysis());
                case PAUSE_AUTOMATION -> exactAction(tokens, Action.pauseAutomation());
                case REQUEST_AUTHORIZED_MOVE -> exactAction(tokens, Action.requestAuthorizedMove());
                case SET_TIME_SCALE -> {
                    if (tokens.length != 3) throw new IllegalArgumentException("requires one value");
                    yield Action.setTimeScale(Integer.parseInt(tokens[2]));
                }
                case SHOW_NOTICE -> {
                    if (tokens.length != 3) throw new IllegalArgumentException("requires one notice");
                    yield Action.showNotice(Notice.valueOf(tokens[2]));
                }
            };
        } catch (IllegalArgumentException invalid) {
            throw parseError(lineNumber, "invalid " + type + " action: "
                    + safeMessage(invalid));
        }
    }

    private static Action exactAction(String[] tokens, Action action) {
        if (tokens.length != 2) throw new IllegalArgumentException("does not accept operands");
        return action;
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type,
                                                    String value,
                                                    int lineNumber,
                                                    String label) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException invalid) {
            throw parseError(lineNumber, "unknown " + label + ": " + value);
        }
    }

    private static IllegalArgumentException parseError(int line, String message) {
        return new IllegalArgumentException("line " + line + ": " + message);
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }

    public enum Event {
        POSITION_STABLE,
        ENGINE_RESULT,
        MOVE_CONFIRMED,
        AUTOMATION_PAUSED,
        MANUAL_ANALYSIS
    }

    public enum Field {
        SCORE_CP,
        COMPLEXITY,
        REMAINING_MILLIS,
        TIME_TARGET_MILLIS,
        PHASE,
        SIDE,
        AUTOMATION_STATE
    }

    public enum Operator {
        LE,
        GE,
        EQ
    }

    public enum Side {
        RED,
        BLACK
    }

    public enum ActionType {
        START_ANALYSIS,
        STOP_ANALYSIS,
        SET_TIME_SCALE,
        SHOW_NOTICE,
        PAUSE_AUTOMATION,
        REQUEST_AUTHORIZED_MOVE
    }

    public enum Notice {
        NONE,
        TIME_PRESSURE,
        TACTICAL_POSITION,
        RULE_TRIGGERED
    }

    public record Condition(Field field, Operator operator, String operand) {

        public Condition {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(operator, "operator");
            operand = Objects.requireNonNull(operand, "operand").trim();
            if (operand.isEmpty() || operand.length() > 32) {
                throw new IllegalArgumentException("invalid condition operand");
            }
            switch (field) {
                case SCORE_CP -> operand = numericOperand(
                        operand, operator, -100_000, 100_000, field);
                case COMPLEXITY -> operand = numericOperand(
                        operand, operator, 0, 100, field);
                case REMAINING_MILLIS, TIME_TARGET_MILLIS -> operand = numericOperand(
                        operand, operator, 0, Long.MAX_VALUE, field);
                case PHASE -> operand = symbolicOperand(
                        operand, operator, TimeStrategy.Phase.class, field);
                case SIDE -> operand = symbolicOperand(
                        operand, operator, Side.class, field);
                case AUTOMATION_STATE -> operand = symbolicOperand(
                        operand, operator, AutomationState.class, field);
            }
        }

        public long numericValue() {
            return Long.parseLong(operand);
        }

        private static String numericOperand(String operand,
                                             Operator operator,
                                             long minimum,
                                             long maximum,
                                             Field field) {
            if (operator == Operator.EQ) {
                throw new IllegalArgumentException(field + " supports only LE or GE");
            }
            try {
                long value = Long.parseLong(operand);
                if (value < minimum || value > maximum) {
                    throw new IllegalArgumentException(field + " operand is outside its range");
                }
                return Long.toString(value);
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException(field + " requires an integer", invalid);
            }
        }

        private static <T extends Enum<T>> String symbolicOperand(String operand,
                                                                   Operator operator,
                                                                   Class<T> type,
                                                                   Field field) {
            if (operator != Operator.EQ) {
                throw new IllegalArgumentException(field + " supports only EQ");
            }
            try {
                return Enum.valueOf(type, operand).name();
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException(field + " has an unknown operand", invalid);
            }
        }
    }

    public record Action(ActionType type, int value, Notice notice) {

        public Action {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(notice, "notice");
            if (type == ActionType.SET_TIME_SCALE) {
                if (value < 25 || value > 200 || notice != Notice.NONE) {
                    throw new IllegalArgumentException("time scale must be between 25 and 200");
                }
            } else if (type == ActionType.SHOW_NOTICE) {
                if (value != 0 || notice == Notice.NONE) {
                    throw new IllegalArgumentException("SHOW_NOTICE requires a notice code");
                }
            } else if (value != 0 || notice != Notice.NONE) {
                throw new IllegalArgumentException(type + " does not accept operands");
            }
        }

        public static Action startAnalysis() {
            return new Action(ActionType.START_ANALYSIS, 0, Notice.NONE);
        }

        public static Action stopAnalysis() {
            return new Action(ActionType.STOP_ANALYSIS, 0, Notice.NONE);
        }

        public static Action setTimeScale(int percent) {
            return new Action(ActionType.SET_TIME_SCALE, percent, Notice.NONE);
        }

        public static Action showNotice(Notice notice) {
            return new Action(ActionType.SHOW_NOTICE, 0, notice);
        }

        public static Action pauseAutomation() {
            return new Action(ActionType.PAUSE_AUTOMATION, 0, Notice.NONE);
        }

        public static Action requestAuthorizedMove() {
            return new Action(ActionType.REQUEST_AUTHORIZED_MOVE, 0, Notice.NONE);
        }

        private String serialize() {
            return switch (type) {
                case SET_TIME_SCALE -> type + " " + value;
                case SHOW_NOTICE -> type + " " + notice;
                default -> type.toString();
            };
        }
    }

    public record Rule(String id,
                       Event event,
                       List<Condition> conditions,
                       List<Action> actions) {

        public Rule {
            id = Objects.requireNonNull(id, "id").trim();
            if (!IDENTIFIER.matcher(id).matches()) {
                throw new IllegalArgumentException("invalid rule id");
            }
            Objects.requireNonNull(event, "event");
            conditions = List.copyOf(Objects.requireNonNull(conditions, "conditions"));
            actions = List.copyOf(Objects.requireNonNull(actions, "actions"));
            if (conditions.size() > MAX_CONDITIONS_PER_RULE) {
                throw new IllegalArgumentException("condition limit exceeded");
            }
            if (actions.isEmpty() || actions.size() > MAX_ACTIONS_PER_RULE) {
                throw new IllegalArgumentException("action count is outside its limits");
            }
            conditions.forEach(condition -> Objects.requireNonNull(condition, "condition"));
            actions.forEach(action -> Objects.requireNonNull(action, "action"));
        }
    }

    private static final class RuleBuilder {
        private final String id;
        private Event event;
        private final List<Condition> conditions = new ArrayList<>();
        private final List<Action> actions = new ArrayList<>();

        private RuleBuilder(String id) {
            this.id = id;
        }

        private Rule build() {
            return new Rule(id, event, conditions, actions);
        }
    }
}
