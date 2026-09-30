package com.sclera.applicationplane.procedure.definition;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Category;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Question;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.controlplane.common.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyMinterTest {

    private static Question q(String key, String text) {
        return new Question(key, text, null, QuestionType.TEXT, false);
    }

    private static DefinitionDocument doc(Category... categories) {
        return new DefinitionDocument(1, List.of(categories));
    }

    @Test
    void mintsKeysForANewDocumentCategoryFirst() {
        AtomicInteger seq = new AtomicInteger(0);

        DefinitionDocument result = KeyMinter.assignKeys(
                doc(new Category(null, "Fire safety", List.of(q(null, "Exit clear?"), q("", "Alarm tested?")))),
                0, seq::incrementAndGet);

        Category c = result.categories().get(0);
        assertThat(c.key()).isEqualTo("c1");
        assertThat(c.questions()).extracting(Question::key).containsExactly("q2", "q3");
        assertThat(seq.get()).isEqualTo(3);
    }

    @Test
    void keepsExistingKeysAndMintsOnlyForNewItems() {
        AtomicInteger seq = new AtomicInteger(3);

        DefinitionDocument result = KeyMinter.assignKeys(
                doc(new Category("c1", "Fire safety", List.of(q(null, "New one"), q("q2", "Exit clear?")))),
                3, seq::incrementAndGet);

        assertThat(result.categories().get(0).questions())
                .extracting(Question::key).containsExactly("q4", "q2");
    }

    @Test
    void rejectsKeysTheTemplateNeverIssued() {
        assertThatThrownBy(() -> KeyMinter.assignKeys(
                doc(new Category("c1", "X", List.of(q("q9", "Invented")))), 3, () -> 4))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("'q9' was never issued");
    }

    @Test
    void rejectsACategoryKeyOnAQuestionAndDuplicates() {
        assertThatThrownBy(() -> KeyMinter.assignKeys(
                doc(new Category("c1", "X", List.of(q("c1", "Wrong prefix"), q("q2", "A"), q("q2", "B")))),
                5, () -> 6))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("'c1' cannot be used on a question")
                .hasMessageContaining("'q2' appears more than once");
    }

    @Test
    void mintsNothingWhenAnySuppliedKeyIsBad() {
        AtomicInteger seq = new AtomicInteger(1);

        assertThatThrownBy(() -> KeyMinter.assignKeys(
                doc(new Category(null, "New", List.of(q("zz", "Bad")))), 1, seq::incrementAndGet))
                .isInstanceOf(ValidationException.class);
        assertThat(seq.get()).isEqualTo(1);
    }
}
