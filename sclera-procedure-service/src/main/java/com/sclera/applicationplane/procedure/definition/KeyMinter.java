package com.sclera.applicationplane.procedure.definition;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.controlplane.common.exception.ValidationException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gives every section, question and option a stable key. Keys are the whole
 * reason a question can be "the same question" in v1 and v7: rules, reporting
 * and stored answers all refer to it by key.
 *
 * A key is a prefix plus a number from the template's {@code key_seq}:
 * {@code s<n>} for sections, {@code q<n>} for questions, {@code o<n>} for
 * options. One counter serves all three, so a number is never used twice within
 * a template and a key's prefix alone says what kind of thing it is.
 *
 * Options are keyed because a follow-up's {@code when} points at one. A renamed
 * option keeps its key, so "show this when they answer No" survives the author
 * rewording "No" to "No — blocked".
 *
 * On every save the client sends back the keys it was given and omits the key on
 * anything new. A supplied key must be one this template actually minted (its
 * number is at most {@code highestMinted}), must carry the right prefix, and must
 * appear only once. New items are minted fresh; nothing is ever re-minted.
 */
public final class KeyMinter {

    public static final String SECTION_PREFIX = "s";
    public static final String QUESTION_PREFIX = "q";
    public static final String OPTION_PREFIX = "o";

    private static final Pattern KEY = Pattern.compile("^([sqo])([1-9][0-9]{0,8})$");

    private KeyMinter() {
    }

    /**
     * @param highestMinted the template's key_seq before this save
     * @param next          hands out the next unused number (advances key_seq)
     * @return the document with every key filled in
     * @throws ValidationException listing every bad key, before anything is minted
     */
    public static DefinitionDocument assignKeys(DefinitionDocument document, int highestMinted, IntSupplier next) {
        validateSuppliedKeys(document, highestMinted);
        return new DefinitionDocument(
                document.schema(), assignToAll(document.items(), next), document.thresholds(),
                   document.targetTypes());
    }

    private static List<Item> assignToAll(List<Item> items, IntSupplier next) {
        List<Item> out = new ArrayList<>();
        for (Item item : items) {
            out.add(assignTo(item, next));
        }
        return out;
    }

    private static Item assignTo(Item item, IntSupplier next) {
        String prefix = item.type() == QuestionType.SECTION ? SECTION_PREFIX : QUESTION_PREFIX;
        Item keyed = item.withKey(isBlank(item.key()) ? prefix + next.getAsInt() : item.key().strip());

        List<Option> options = new ArrayList<>();
        for (Option option : keyed.options()) {
            options.add(isBlank(option.key())
                    ? option.withKey(OPTION_PREFIX + next.getAsInt())
                    : option.withKey(option.key().strip()));
        }

        return keyed.withOptions(options).withFollow(assignToAll(keyed.follow(), next));
    }

    private static void validateSuppliedKeys(DefinitionDocument document, int highestMinted) {
        Set<String> errors = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        checkAll(document.items(), highestMinted, seen, errors);
        if (!errors.isEmpty()) {
            throw new ValidationException(String.join("; ", errors));
        }
    }

    private static void checkAll(List<Item> items, int highestMinted, Set<String> seen, Set<String> errors) {
        for (Item item : items) {
            boolean section = item.type() == QuestionType.SECTION;
            check(item.key(), section ? SECTION_PREFIX : QUESTION_PREFIX,
                    section ? "a section" : "a question", highestMinted, seen, errors);
            for (Option option : item.options()) {
                check(option.key(), OPTION_PREFIX, "an answer", highestMinted, seen, errors);
            }
            checkAll(item.follow(), highestMinted, seen, errors);
        }
    }

    private static void check(String rawKey, String prefix, String kind, int highestMinted,
                              Set<String> seen, Set<String> errors) {
        if (isBlank(rawKey)) {
            return; // new item, minted later
        }
        String key = rawKey.strip();
        Matcher m = KEY.matcher(key);
        if (!m.matches()) {
            errors.add("Key '" + key + "' is not a valid key; omit the key on new items");
            return;
        }
        if (!m.group(1).equals(prefix)) {
            errors.add("Key '" + key + "' cannot be used on " + kind);
            return;
        }
        if (Integer.parseInt(m.group(2)) > highestMinted) {
            errors.add("Key '" + key + "' was never issued for this template; omit the key on new items");
            return;
        }
        if (!seen.add(key)) {
            errors.add("Key '" + key + "' appears more than once");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
