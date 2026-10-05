package com.sclera.applicationplane.procedure.domain;

/**
 * Where a question came from.
 *
 * Kept because it is cheap now and impossible to backfill later: once a
 * procedure has been edited a few times, nobody can tell which questions a
 * person wrote and which came out of a standard. It also gives the document
 * pipeline somewhere to record itself when it arrives.
 *
 * Null means {@link #MANUAL} — the canonical form omits empty values, so the
 * common case costs no bytes and no hash.
 */
public enum ItemSource {
    /** Typed by an author. */
    MANUAL,
    /** Generated from an uploaded standard or SOP. */
    DOCUMENT,
    /** Copied in from a Sclera template. */
    TEMPLATE,
    /** Taken from a suggestion. */
    SUGGESTED
}
