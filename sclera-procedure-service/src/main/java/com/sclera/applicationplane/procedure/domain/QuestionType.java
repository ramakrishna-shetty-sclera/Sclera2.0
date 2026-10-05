package com.sclera.applicationplane.procedure.domain;

/**
 * What an item in a procedure document is.
 *
 * {@code SECTION} sits in the same list as the questions rather than wrapping
 * them — a section is an item that happens to be a heading, which is how the v3
 * prototype models it and what lets a document be one flat list.
 *
 * The rest is the prototype's answer-type set, in its three families:
 *
 * <ul>
 *   <li><b>Choice</b> — the answer is one or more of a fixed list of options,
 *       and each option carries the result it produces.</li>
 *   <li><b>Input</b> — the inspector types something.</li>
 *   <li><b>Media</b> — the inspector captures or attaches something.</li>
 * </ul>
 *
 * The enum keeps its name even though it now also names a section: renaming it
 * would churn every call site for a word, and "the type of a question-list item"
 * is close enough to read correctly.
 *
 * No DATE and no SIGNATURE. Signature is still R&amp;D (1 Oct) and date was
 * dropped by the prototype; neither is dropped permanently, both simply are not
 * in the set the product has committed to.
 */
public enum QuestionType {

    /** A heading. Carries text and nothing else — no answer, no options. */
    SECTION,

    // --- choice: options carry the result ---
    /** Two options, seeded Yes/No. */
    YES_NO,
    /** Three options, seeded Yes/No/NA, where N/A produces no result. */
    YES_NO_NA,
    /** One of several, author-defined. */
    RADIO,
    /** Several of several. The worst selected result wins. */
    CHECKBOX,
    /** One of several, shown as a list. */
    DROPDOWN,

    // --- input ---
    TEXT,
    /** A number, optionally bounded by min/max and labelled with a unit. */
    INTEGER,

    // --- media ---
    IMAGE,
    MULTI_IMAGE,
    AUDIO,
    VIDEO,
    DOCUMENT;

    /** Choice types are the ones whose options decide a result. */
    public boolean isChoice() {
        return this == YES_NO || this == YES_NO_NA || this == RADIO
                || this == CHECKBOX || this == DROPDOWN;
    }

    /** Yes/No and Yes/No/NA have their options seeded; the author cannot add or remove them. */
    public boolean hasFixedOptions() {
        return this == YES_NO || this == YES_NO_NA;
    }
}
