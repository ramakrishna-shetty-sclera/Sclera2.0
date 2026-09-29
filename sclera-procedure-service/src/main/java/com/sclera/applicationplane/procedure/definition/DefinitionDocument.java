package com.sclera.applicationplane.procedure.definition;

import com.sclera.applicationplane.procedure.domain.QuestionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * The whole form of one procedure version — what {@code definition_json}
 * holds. Stored in canonical form (see {@link DefinitionCanonicalizer}), so the
 * same content always produces the same bytes and the same hash.
 *
 * Keys ({@code c<n>} for categories, {@code q<n>} for questions) are minted by
 * {@link KeyMinter} and never change, so a key means the same item in every
 * version. Sibling order is the array index; there is no order field.
 *
 * This is the skeleton the content model grows on: later features add
 * options, nested sub-questions, display conditions, scoring and so on as new
 * fields, without reshaping what is here. The constraints below are the
 * request-level basics; deeper structural rules belong to the validator.
 */
public record DefinitionDocument(int schema, List<@Valid Category> categories) {

    public static final int CURRENT_SCHEMA = 1;

    public DefinitionDocument {
        categories = categories == null ? List.of() : List.copyOf(categories);
    }

    public static DefinitionDocument empty() {
        return new DefinitionDocument(CURRENT_SCHEMA, List.of());
    }

    public int questionCount() {
        return categories.stream().mapToInt(c -> c.questions().size()).sum();
    }

    public record Category(
            @Size(max = 20) String key,
            @NotBlank @Size(max = 200) String name,
            List<@Valid Question> questions) {

        public Category {
            questions = questions == null ? List.of() : List.copyOf(questions);
        }

        public Category withKey(String newKey) {
            return new Category(newKey, name, questions);
        }

        public Category withQuestions(List<Question> newQuestions) {
            return new Category(key, name, newQuestions);
        }
    }

    public record Question(
            @Size(max = 20) String key,
            @NotBlank @Size(max = 1000) String text,
            @Size(max = 1000) String helpText,
            @NotNull QuestionType type,
            boolean required) {

        public Question withKey(String newKey) {
            return new Question(newKey, text, helpText, type, required);
        }
    }
}
