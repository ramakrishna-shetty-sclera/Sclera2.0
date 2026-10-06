package com.sclera.applicationplane.procedure.domain;

/**
 * How a question's follow-ups contribute to its result and its score.
 *
 * The Part 1 brief asks authors to define "how sub-questions contribute", and
 * this is that choice. It matters because a follow-up is conditional: it is
 * only ever asked when the parent was answered a particular way, so counting it
 * like an ordinary question would make a procedure score differently depending
 * on answers nobody controls.
 *
 * Null means {@link #INDEPENDENT} — the canonical form omits empty values, so
 * a procedure that has not thought about this costs no bytes and no hash.
 */
public enum Rollup {

    /**
     * The follow-ups score on their own, alongside their parent. The default,
     * and what "no scoring configured; questions have default equal weight"
     * means for a follow-up.
     */
    INDEPENDENT,

    /**
     * The most severe result among the parent and its follow-ups becomes the
     * parent's. One failed follow-up fails the question it hangs off.
     */
    WORST,

    /** The parent and its follow-ups average into one result for the parent. */
    AVERAGE
}
