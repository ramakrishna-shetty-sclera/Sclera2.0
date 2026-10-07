package com.sclera.applicationplane.procedure.evaluation;

import com.sclera.applicationplane.procedure.domain.ResultType;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * How severe each of an organization's result types is — the one piece of
 * organization state evaluation reads.
 *
 * <p><b>Every type, not only the active ones.</b> A published version may name
 * a type that has since been deactivated — deactivating is exactly what the
 * delete guard tells an admin to do instead — and that version has to keep
 * evaluating the way it did. Built from the active types only, a deactivated
 * Amber would silently drop out of every rollup and change what a past
 * inspection says it decided.
 *
 * <p><b>Never cache what this decides.</b> An admin can reorder the types, and
 * a reorder changes which of two results is more severe. A parsed version is
 * immutable and can be cached; a verdict cannot.
 */
public final class ResultRanks {

    /** The system type every organization has, and the line between passing and failing. */
    static final String FAIL = "FAIL";

    private final Map<String, Integer> severityByKey;
    private final int failSeverity;

    private ResultRanks(Map<String, Integer> severityByKey) {
        Integer fail = severityByKey.get(FAIL);
        if (fail == null) {
            // Seeded for every organization and neither deletable nor
            // deactivatable, so its absence means the caller built this wrong.
            throw new IllegalArgumentException("The result types must include " + FAIL);
        }
        this.severityByKey = Map.copyOf(severityByKey);
        this.failSeverity = fail;
    }

    /** From the organization's result types — all of them, active or not. */
    public static ResultRanks of(Collection<ResultType> types) {
        Map<String, Integer> ranks = new HashMap<>();
        for (ResultType type : types) {
            ranks.put(type.getKey(), type.getSeverityOrder());
        }
        return new ResultRanks(ranks);
    }

    /** Key to severity order, 1 most severe. */
    public static ResultRanks of(Map<String, Integer> severityByKey) {
        return new ResultRanks(severityByKey);
    }

    /**
     * Whether a result counts as a failure — for a critical question, and for
     * raising a work order.
     *
     * <p><b>The one rule here that no document states; it is inferred.</b> A
     * result type has no "fails" flag, so a result fails when it is at least as
     * severe as {@code FAIL}. Not "the key is FAIL": an organization that
     * defines {@code CRITICAL} above Fail would find a critical question
     * answered Critical did not fail the inspection. If this ever proves too
     * clever, the upgrade is a flag on the result type, which is a migration
     * and a screen change rather than an evaluator change.
     *
     * <p>A key the organization does not have is never a failure. That only
     * happens on a draft; publishing refuses one.
     */
    public boolean isFailure(String key) {
        Integer severity = key == null ? null : severityByKey.get(key);
        return severity != null && severity <= failSeverity;
    }

    /**
     * The more severe of two results — the rule for every rollup. Null means
     * "no result" and loses to any result. On a tie, or when neither is a key
     * the organization has, the first wins, so the answer never depends on
     * map order.
     */
    public String mostSevere(String a, String b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return rank(b) < rank(a) ? b : a;
    }

    /** Unknown keys rank below every real one, so they never outweigh a known result. */
    private int rank(String key) {
        return severityByKey.getOrDefault(key, Integer.MAX_VALUE);
    }
}
