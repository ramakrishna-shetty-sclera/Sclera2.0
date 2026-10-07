package com.sclera.applicationplane.procedure.evaluation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What the inspector answered, by the document's own stable question keys.
 *
 * <p>A value is whatever JSON carried: an option key for a single-choice
 * question, a list of option keys for {@code CHECKBOX}, a whole number for
 * {@code INTEGER}, and anything at all for text and media, which decide
 * nothing. Shaped so the inspection service can one day send its stored
 * answers as they are.
 *
 * <p><b>Blank is unanswered.</b> A missing key, a null, an empty or blank
 * string and an empty list all mean the question has not been answered yet —
 * which is the normal state of most of a half-finished inspection, not an
 * error.
 *
 * <p>The readers here only coerce. Whether a value fits its question — an
 * option that belongs to it, a number where a number is asked — is the
 * evaluator's call, because only it knows the question.
 */
public final class Answers {

    private final Map<String, Object> values;

    private Answers(Map<String, ?> values) {
        Map<String, Object> answered = new HashMap<>();
        if (values != null) {
            values.forEach((key, value) -> {
                if (key != null && !isBlank(value)) {
                    answered.put(key, value);
                }
            });
        }
        this.values = Map.copyOf(answered);
    }

    public static Answers of(Map<String, ?> values) {
        return new Answers(values);
    }

    public static Answers none() {
        return new Answers(Map.of());
    }

    /** The keys that carry an answer. */
    public Set<String> keys() {
        return values.keySet();
    }

    public boolean isAnswered(String key) {
        return values.containsKey(key);
    }

    /** One option key; empty when unanswered or not a single string. */
    Optional<String> optionKey(String key) {
        return values.get(key) instanceof String s ? Optional.of(s.strip()) : Optional.empty();
    }

    /**
     * Option keys for a multi-select. A lone string is accepted as one
     * selection; empty when unanswered or when any entry is not a string.
     */
    Optional<List<String>> optionKeys(String key) {
        Object value = values.get(key);
        if (value instanceof String s) {
            return Optional.of(List.of(s.strip()));
        }
        if (!(value instanceof Collection<?> list)) {
            return Optional.empty();
        }
        List<String> keys = new ArrayList<>();
        for (Object entry : list) {
            if (!(entry instanceof String s)) {
                return Optional.empty();
            }
            keys.add(s.strip());
        }
        return Optional.of(List.copyOf(keys));
    }

    /**
     * A whole-number reading. Accepts a JSON number or a numeric string, so
     * {@code 140}, {@code 140.0} and {@code "140"} all read as 140; empty when
     * unanswered, not a number, or not whole.
     */
    Optional<Long> wholeNumber(String key) {
        Object value = values.get(key);
        BigDecimal number;
        try {
            if (value instanceof Number n) {
                number = new BigDecimal(n.toString());
            } else if (value instanceof String s) {
                number = new BigDecimal(s.strip());
            } else {
                return Optional.empty();
            }
            return Optional.of(number.longValueExact());
        } catch (NumberFormatException | ArithmeticException notWhole) {
            return Optional.empty();
        }
    }

    private static boolean isBlank(Object value) {
        return value == null
                || (value instanceof String s && s.isBlank())
                || (value instanceof Collection<?> c && c.isEmpty());
    }
}
