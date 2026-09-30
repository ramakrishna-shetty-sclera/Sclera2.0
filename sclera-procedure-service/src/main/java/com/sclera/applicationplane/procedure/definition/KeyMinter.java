package com.sclera.applicationplane.procedure.definition;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Category;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Question;
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
 * Gives every category and question a stable key. Keys are the whole reason a
 * question can be "the same question" in v1 and v7: rules, reporting and
 * answers all refer to it by key.
 *
 * A key is a prefix plus a number from the template's {@code key_seq}:
 * {@code c<n>} for categories, {@code q<n>} for questions. One counter serves
 * both, so a number is never used twice within a template.
 *
 * On every save the client sends back the keys it was given and omits the key
 * on anything new. A supplied key must be one this template actually minted
 * (its number is at most {@code highestMinted}), must carry the right prefix,
 * and must appear only once. New items are minted fresh; nothing is re-minted.
 */
public final class KeyMinter {

    public static final String CATEGORY_PREFIX = "c";
    public static final String QUESTION_PREFIX = "q";

    private static final Pattern KEY = Pattern.compile("^([cq])([1-9][0-9]{0,8})$");

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

        List<Category> categories = new ArrayList<>();
        for (Category category : document.categories()) {
            String categoryKey = isBlank(category.key())
                    ? CATEGORY_PREFIX + next.getAsInt()
                    : category.key().strip();
            List<Question> questions = new ArrayList<>();
            for (Question question : category.questions()) {
                questions.add(isBlank(question.key())
                        ? question.withKey(QUESTION_PREFIX + next.getAsInt())
                        : question.withKey(question.key().strip()));
            }
            categories.add(category.withKey(categoryKey).withQuestions(questions));
        }
        return new DefinitionDocument(document.schema(), categories);
    }

    private static void validateSuppliedKeys(DefinitionDocument document, int highestMinted) {
        Set<String> errors = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        for (Category category : document.categories()) {
            check(category.key(), CATEGORY_PREFIX, "category", highestMinted, seen, errors);
            for (Question question : category.questions()) {
                check(question.key(), QUESTION_PREFIX, "question", highestMinted, seen, errors);
            }
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(String.join("; ", errors));
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
            errors.add("Key '" + key + "' cannot be used on a " + kind);
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
