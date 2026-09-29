package com.sclera.applicationplane.procedure.definition;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Category;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Question;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefinitionCanonicalizerTest {

    private final DefinitionCanonicalizer canonicalizer = new DefinitionCanonicalizer();

    private static DefinitionDocument doc(Question... questions) {
        return new DefinitionDocument(1, List.of(new Category("c1", "Fire safety", List.of(questions))));
    }

    @Test
    void writesOneLineWithSortedKeysAndNoEmptyValues() {
        var canonical = canonicalizer.canonicalize(
                doc(new Question("q2", "Exit clear?", null, QuestionType.BOOLEAN, true)));

        assertThat(canonical.json()).isEqualTo(
                "{\"categories\":[{\"key\":\"c1\",\"name\":\"Fire safety\",\"questions\":"
                + "[{\"key\":\"q2\",\"required\":true,\"text\":\"Exit clear?\",\"type\":\"BOOLEAN\"}]}],"
                + "\"schema\":1}");
        assertThat(canonical.hash()).matches("^[0-9a-f]{64}$")
                .isEqualTo(DefinitionCanonicalizer.sha256Hex(canonical.json()));
    }

    @Test
    void sameContentGivesSameBytesDespiteWhitespaceAndDefaults() {
        var tidy = canonicalizer.canonicalize(
                doc(new Question("q2", "Exit clear?", null, QuestionType.BOOLEAN, false)));
        var messy = canonicalizer.canonicalize(
                doc(new Question("q2", "  Exit clear?  ", "   ", QuestionType.BOOLEAN, false)));

        assertThat(messy.json()).isEqualTo(tidy.json()).doesNotContain("required").doesNotContain("helpText");
        assertThat(messy.hash()).isEqualTo(tidy.hash());
    }

    @Test
    void ignoresTheSchemaNumberTheClientSent() {
        var sent = new DefinitionDocument(99, List.of());

        assertThat(canonicalizer.canonicalize(sent).json()).isEqualTo("{\"schema\":1}");
    }

    @Test
    void reCanonicalisingStoredBytesIsStable() {
        var first = canonicalizer.canonicalize(doc(
                new Question("q2", "Exit clear?", "Look behind the door", QuestionType.BOOLEAN, true),
                new Question("q3", "Extinguisher date", null, QuestionType.DATE, false)));

        var again = canonicalizer.canonicalize(canonicalizer.parse(first.json()));

        assertThat(again.json()).isEqualTo(first.json());
        assertThat(again.hash()).isEqualTo(first.hash());
    }

    @Test
    void questionOrderIsPartOfTheContent() {
        Question a = new Question("q2", "A", null, QuestionType.TEXT, false);
        Question b = new Question("q3", "B", null, QuestionType.TEXT, false);

        assertThat(canonicalizer.canonicalize(doc(a, b)).hash())
                .isNotEqualTo(canonicalizer.canonicalize(doc(b, a)).hash());
    }

    @Test
    void rejectsStoredBytesWithUnknownFields() {
        assertThatThrownBy(() -> canonicalizer.parse("{\"schema\":1,\"surprise\":true}"))
                .isInstanceOf(IllegalStateException.class);
    }
}
