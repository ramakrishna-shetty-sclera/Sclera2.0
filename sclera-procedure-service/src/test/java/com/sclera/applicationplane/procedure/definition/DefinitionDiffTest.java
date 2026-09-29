package com.sclera.applicationplane.procedure.definition;

import com.sclera.applicationplane.procedure.definition.DefinitionDiff.Kind;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Category;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Question;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class DefinitionDiffTest {

    private static Question q(String key, String text) {
        return new Question(key, text, null, QuestionType.TEXT, false);
    }

    private static DefinitionDocument doc(Category... categories) {
        return new DefinitionDocument(1, List.of(categories));
    }

    @Test
    void identicalDocumentsHaveNoChanges() {
        DefinitionDocument d = doc(new Category("c1", "Fire", List.of(q("q2", "Exit clear?"))));

        DefinitionDiff diff = DefinitionDiff.between(d, d);

        assertThat(diff.identical()).isTrue();
        assertThat(diff.categories()).isEmpty();
        assertThat(diff.questions()).isEmpty();
    }

    @Test
    void reportsAddedRemovedAndModifiedQuestionsByKey() {
        DefinitionDocument v1 = doc(new Category("c1", "Fire", List.of(q("q2", "Exit clear?"), q("q3", "Old"))));
        DefinitionDocument v2 = doc(new Category("c1", "Fire", List.of(
                new Question("q2", "Is the exit clear?", null, QuestionType.BOOLEAN, true),
                q("q4", "Brand new"))));

        DefinitionDiff diff = DefinitionDiff.between(v1, v2);

        assertThat(diff.identical()).isFalse();
        assertThat(diff.questions())
                .extracting(DefinitionDiff.QuestionChange::key, DefinitionDiff.QuestionChange::kind)
                .containsExactly(
                        tuple("q2", Kind.MODIFIED),
                        tuple("q4", Kind.ADDED),
                        tuple("q3", Kind.REMOVED));
        assertThat(diff.questions().get(0).changedFields()).containsExactly("text", "type", "required");
    }

    @Test
    void insertingAtTheTopIsNotAReorder() {
        DefinitionDocument v1 = doc(new Category("c1", "Fire", List.of(q("q2", "A"), q("q3", "B"))));
        DefinitionDocument v2 = doc(new Category("c1", "Fire", List.of(q("q4", "New"), q("q2", "A"), q("q3", "B"))));

        DefinitionDiff diff = DefinitionDiff.between(v1, v2);

        assertThat(diff.categories()).isEmpty();
        assertThat(diff.questions()).singleElement()
                .extracting(DefinitionDiff.QuestionChange::kind).isEqualTo(Kind.ADDED);
    }

    @Test
    void swappingQuestionsIsAReorderOfTheirCategory() {
        DefinitionDocument v1 = doc(new Category("c1", "Fire", List.of(q("q2", "A"), q("q3", "B"))));
        DefinitionDocument v2 = doc(new Category("c1", "Fire", List.of(q("q3", "B"), q("q2", "A"))));

        DefinitionDiff diff = DefinitionDiff.between(v1, v2);

        assertThat(diff.questions()).isEmpty();
        assertThat(diff.categories()).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo(Kind.MODIFIED);
            assertThat(c.changedFields()).containsExactly("questionOrder");
        });
    }

    @Test
    void movingAQuestionBetweenCategoriesIsReportedOnTheQuestion() {
        DefinitionDocument v1 = doc(
                new Category("c1", "Fire", List.of(q("q3", "A"))),
                new Category("c2", "Electrical", List.of()));
        DefinitionDocument v2 = doc(
                new Category("c1", "Fire", List.of()),
                new Category("c2", "Electrical", List.of(q("q3", "A"))));

        DefinitionDiff diff = DefinitionDiff.between(v1, v2);

        assertThat(diff.questions()).singleElement().satisfies(q -> {
            assertThat(q.kind()).isEqualTo(Kind.MODIFIED);
            assertThat(q.changedFields()).containsExactly("category");
            assertThat(q.categoryKey()).isEqualTo("c2");
        });
        assertThat(diff.categoryOrderChanged()).isFalse();
    }
}
