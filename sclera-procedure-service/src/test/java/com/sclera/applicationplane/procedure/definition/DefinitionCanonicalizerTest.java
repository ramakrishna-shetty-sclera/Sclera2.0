package com.sclera.applicationplane.procedure.definition;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefinitionCanonicalizerTest {

    private final DefinitionCanonicalizer canonicalizer = new DefinitionCanonicalizer();

    private static DefinitionDocument doc(Item... items) {
        return new DefinitionDocument(1, List.of(items));
    }

    private static Item question(String key, String text, QuestionType type, boolean required) {
        return new Item(key, text, null, type, required, List.of(), null, null, null,
                false, null, null, null, null, List.of());
    }

    @Test
    void writesOneLineWithSortedKeysAndNoEmptyValues() {
        var canonical = canonicalizer.canonicalize(
                doc(question("q2", "Exit clear?", QuestionType.YES_NO, true)));

        assertThat(canonical.json()).isEqualTo(
                "{\"items\":[{\"key\":\"q2\",\"required\":true,\"text\":\"Exit clear?\",\"type\":\"YES_NO\"}],"
                + "\"schema\":2}");
        assertThat(canonical.hash()).matches("^[0-9a-f]{64}$")
                .isEqualTo(DefinitionCanonicalizer.sha256Hex(canonical.json()));
    }

    @Test
    void sameContentGivesSameBytesDespiteWhitespaceAndDefaults() {
        var tidy = canonicalizer.canonicalize(
                doc(question("q2", "Exit clear?", QuestionType.YES_NO, false)));
        var messy = canonicalizer.canonicalize(doc(
                new Item("q2", "  Exit clear?  ", "   ", QuestionType.YES_NO, false, List.of(),
                        "  ", null, null, false, null, null, "  ", null, List.of())));

        assertThat(messy.json()).isEqualTo(tidy.json())
                .doesNotContain("required").doesNotContain("help").doesNotContain("unit");
        assertThat(messy.hash()).isEqualTo(tidy.hash());
    }

    @Test
    void ignoresTheSchemaNumberTheClientSent() {
        var sent = new DefinitionDocument(99, List.of());

        assertThat(canonicalizer.canonicalize(sent).json()).isEqualTo("{\"schema\":2}");
    }

    @Test
    void reCanonicalisingStoredBytesIsStable() {
        var first = canonicalizer.canonicalize(doc(
                new Item("s1", "Condition", null, QuestionType.SECTION, false, List.of(), null, null, null,
                        false, null, null, null, null, List.of()),
                new Item("q2", "Gauge in the green?", "Tap it first", QuestionType.YES_NO_NA, true,
                        List.of(new Option("o3", "Yes", "PASS"),
                                new Option("o4", "No", "FAIL"),
                                new Option("o5", "N/A", null)),
                        null, null, null, true, "ap-std", null, "NFPA 10", null,
                        List.of(new Item("q6", "Record the reading", null, QuestionType.INTEGER, false,
                                List.of(), "psi", 0, 300, false, null, null, null, "o4", List.of())))));

        var again = canonicalizer.canonicalize(canonicalizer.parse(first.json()));

        assertThat(again.json()).isEqualTo(first.json());
        assertThat(again.hash()).isEqualTo(first.hash());
    }

    @Test
    void aFollowUpIsPartOfTheContent() {
        Item parent = new Item("q2", "Exit clear?", null, QuestionType.YES_NO, false,
                List.of(new Option("o3", "Yes", "PASS"), new Option("o4", "No", "FAIL")),
                null, null, null, false, null, null, null, null, List.of());
        Item withFollow = parent.withFollow(List.of(
                question("q5", "Describe the obstruction", QuestionType.TEXT, true)));

        assertThat(canonicalizer.canonicalize(doc(parent)).hash())
                .isNotEqualTo(canonicalizer.canonicalize(doc(withFollow)).hash());
    }

    @Test
    void itemOrderIsPartOfTheContent() {
        Item a = question("q2", "A", QuestionType.TEXT, false);
        Item b = question("q3", "B", QuestionType.TEXT, false);

        assertThat(canonicalizer.canonicalize(doc(a, b)).hash())
                .isNotEqualTo(canonicalizer.canonicalize(doc(b, a)).hash());
    }

    @Test
    void zeroIsKeptEvenThoughEmptyValuesAreDropped() {
        // min=0 is a real bound, not an absent one. The canonical form drops
        // nulls, blank strings and false — numbers are never dropped, and this
        // is the one that would be silently lost if that ever changed.
        var canonical = canonicalizer.canonicalize(doc(
                new Item("q2", "Reading", null, QuestionType.INTEGER, false, List.of(),
                        "psi", 0, 300, false, null, null, null, null, List.of())));

        assertThat(canonical.json()).contains("\"min\":0");
    }

    @Test
    void rejectsStoredBytesWithUnknownFields() {
        assertThatThrownBy(() -> canonicalizer.parse("{\"schema\":2,\"surprise\":true}"))
                .isInstanceOf(IllegalStateException.class);
    }
}
