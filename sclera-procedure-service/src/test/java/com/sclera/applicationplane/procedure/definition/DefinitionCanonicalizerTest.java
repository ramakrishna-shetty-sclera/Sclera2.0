package com.sclera.applicationplane.procedure.definition;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Threshold;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.applicationplane.procedure.domain.Rollup;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefinitionCanonicalizerTest {

    private final DefinitionCanonicalizer canonicalizer = new DefinitionCanonicalizer();

    private static DefinitionDocument doc(Item... items) {
        return new DefinitionDocument(1, List.of(items), List.of());
    }

    private static Item question(String key, String text, QuestionType type, boolean required) {
        return new Item(key, text, null, type, required, false, List.of(), null, null, null,
                false, null, null, null, null, null, null, List.of());
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
                new Item("q2", "  Exit clear?  ", "   ", QuestionType.YES_NO, false, false, List.of(),
                        "  ", null, null, false, null, null, null, "  ", null, null, List.of())));

        assertThat(messy.json()).isEqualTo(tidy.json())
                .doesNotContain("required").doesNotContain("help").doesNotContain("unit");
        assertThat(messy.hash()).isEqualTo(tidy.hash());
    }

    @Test
    void ignoresTheSchemaNumberTheClientSent() {
        var sent = new DefinitionDocument(99, List.of(), List.of());

        assertThat(canonicalizer.canonicalize(sent).json()).isEqualTo("{\"schema\":2}");
    }

    @Test
    void reCanonicalisingStoredBytesIsStable() {
        var first = canonicalizer.canonicalize(doc(
                new Item("s1", "Condition", null, QuestionType.SECTION, false, false, List.of(), null, null, null,
                        false, null, null, null, null, null, null, List.of()),
                new Item("q2", "Gauge in the green?", "Tap it first", QuestionType.YES_NO_NA, true, false,
                        List.of(new Option("o3", "Yes", "PASS", null, false),
                                new Option("o4", "No", "FAIL", null, false),
                                new Option("o5", "N/A", null, null, false)),
                        null, null, null, true, "ap-std", null, null, "NFPA 10", null,
                        null,
                        List.of(new Item("q6", "Record the reading", null, QuestionType.INTEGER, false, false,
                                List.of(), "psi", 0, 300, false, null, null, null, null, "o4", null,
                                List.of())))));

        var again = canonicalizer.canonicalize(canonicalizer.parse(first.json()));

        assertThat(again.json()).isEqualTo(first.json());
        assertThat(again.hash()).isEqualTo(first.hash());
    }

    @Test
    void aFollowUpIsPartOfTheContent() {
        Item parent = new Item("q2", "Exit clear?", null, QuestionType.YES_NO, false, false,
                List.of(new Option("o3", "Yes", "PASS", null, false), new Option("o4", "No", "FAIL", null, false)),
                null, null, null, false, null, null, null, null, null, null, List.of());
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
                new Item("q2", "Reading", null, QuestionType.INTEGER, false, false, List.of(),
                        "psi", 0, 300, false, null, null, null, null, null, null, List.of())));

        assertThat(canonical.json()).contains("\"min\":0");
    }

    @Test
    void aZeroScoreIsKeptForTheSameReason() {
        // Zero points is the normal way to spell "this is the wrong answer",
        // and it is not the same as an answer that scores nothing at all. The
        // drop-empties rule would eat it if it were ever written as "falsy".
        var canonical = canonicalizer.canonicalize(doc(
                question("q2", "Exit clear?", QuestionType.YES_NO, false).withOptions(List.of(
                        new Option("o3", "Yes", "PASS", 10, false),
                        new Option("o4", "No", "FAIL", 0, false)))));

        assertThat(canonical.json()).contains("\"score\":0");
    }

    @Test
    void anUnscoredDocumentWritesNoScoringFieldsAtAll() {
        // What keeps CURRENT_SCHEMA at 2: a document authored before scoring
        // existed canonicalises byte for byte as it did then, so its stored
        // hash still matches.
        var canonical = canonicalizer.canonicalize(
                doc(question("q2", "Exit clear?", QuestionType.YES_NO, true)));

        assertThat(canonical.json()).isEqualTo(
                "{\"items\":[{\"key\":\"q2\",\"required\":true,\"text\":\"Exit clear?\",\"type\":\"YES_NO\"}],"
                + "\"schema\":2}");
    }

    @Test
    void scoringIsPartOfTheContent() {
        Item unscored = question("q2", "Exit clear?", QuestionType.YES_NO, false).withOptions(List.of(
                new Option("o3", "Yes", "PASS", null, false),
                new Option("o4", "No", "FAIL", null, false)));
        Item scored = unscored.withOptions(List.of(
                new Option("o3", "Yes", "PASS", 10, false),
                new Option("o4", "No", "FAIL", 0, false)));

        assertThat(canonicalizer.canonicalize(doc(unscored)).hash())
                .isNotEqualTo(canonicalizer.canonicalize(doc(scored)).hash());
    }

    @Test
    void thresholdsArePartOfTheContent() {
        // They hang off the document rather than any item, so it would be easy
        // to drop them from the hash and never notice until two versions that
        // score differently collided as "already published".
        Item q = question("q2", "Exit clear?", QuestionType.YES_NO, false);
        var bare = new DefinitionDocument(2, List.of(q), List.of());
        var banded = new DefinitionDocument(2, List.of(q),
                List.of(new Threshold(null, 0, 69, "FAIL"), new Threshold(null, 70, null, "PASS")));

        assertThat(canonicalizer.canonicalize(bare).hash())
                .isNotEqualTo(canonicalizer.canonicalize(banded).hash());
        assertThat(canonicalizer.canonicalize(banded).json()).contains("\"thresholds\"");
    }

    @Test
    void aScoredDocumentSurvivesTheRoundTrip() {
        var first = canonicalizer.canonicalize(new DefinitionDocument(2,
                List.of(new Item("s1", "Fire exits", null, QuestionType.SECTION, false, false, List.of(),
                                null, null, null, false, null, 2, null, null, null, null, List.of()),
                        new Item("q2", "Exit clear?", null, QuestionType.YES_NO, true, true,
                                List.of(new Option("o3", "Yes", "PASS", 10, false),
                                        new Option("o4", "No", "FAIL", 0, false),
                                        new Option("o5", "N/A", null, null, true)),
                                null, null, null, false, null, 3, null, null, null,
                                Rollup.WORST,
                                List.of(new Item("q6", "Why not?", null, QuestionType.TEXT, true, false,
                                        List.of(), null, null, null, false, null, null, null, null,
                                        "o4", null, List.of())))),
                List.of(new Threshold(null, null, 69, "FAIL"),
                        new Threshold(DefinitionDocument.Scope.SECTION, 70, null, "PASS"))));

        var again = canonicalizer.canonicalize(canonicalizer.parse(first.json()));

        assertThat(again.json()).isEqualTo(first.json());
        assertThat(again.hash()).isEqualTo(first.hash());
    }

    /** Canonical bytes exactly as a version published before scoring existed holds them. */
    private static final String STORED_BEFORE_SCORING =
            "{\"items\":[{\"key\":\"s1\",\"text\":\"Fire exits\",\"type\":\"SECTION\"},"
            + "{\"alertProfile\":\"ap-fire\","
            + "\"follow\":[{\"key\":\"q5\",\"required\":true,\"text\":\"Why not?\","
            + "\"type\":\"TEXT\",\"when\":\"o4\"}],"
            + "\"key\":\"q2\",\"options\":["
            + "{\"key\":\"o3\",\"label\":\"Yes\",\"result\":\"PASS\"},"
            + "{\"key\":\"o4\",\"label\":\"No\",\"result\":\"FAIL\"}],"
            + "\"required\":true,\"text\":\"Exit clear?\",\"type\":\"YES_NO\",\"workOrder\":true}],"
            + "\"schema\":2}";

    @Test
    void aVersionStoredBeforeScoringExistedKeepsItsBytesAndItsHash() {
        // The guarantee the whole feature rests on, and the one no test written
        // against freshly built objects can make: a published version is
        // content-addressed and immutable, so if adding scoring changed what
        // these bytes canonicalise to, every version already published would
        // stop matching its own hash.
        var again = canonicalizer.canonicalize(canonicalizer.parse(STORED_BEFORE_SCORING));

        assertThat(again.json()).isEqualTo(STORED_BEFORE_SCORING);
        assertThat(again.hash()).isEqualTo(DefinitionCanonicalizer.sha256Hex(STORED_BEFORE_SCORING));
        assertThat(again.document().hasScoring()).isFalse();
    }

    @Test
    void rejectsStoredBytesWithUnknownFields() {
        assertThatThrownBy(() -> canonicalizer.parse("{\"schema\":2,\"surprise\":true}"))
                .isInstanceOf(IllegalStateException.class);
    }
}
