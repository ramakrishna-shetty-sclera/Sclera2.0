package com.sclera.applicationplane.procedure.definition;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.RangeRule;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.TargetKind;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.TargetType;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Threshold;
import com.sclera.applicationplane.procedure.domain.ItemSource;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.applicationplane.procedure.domain.Rollup;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefinitionCanonicalizerTest {

    private final DefinitionCanonicalizer canonicalizer = new DefinitionCanonicalizer();

    private static DefinitionDocument doc(Item... items) {
        return new DefinitionDocument(1, List.of(items), List.of(), List.of(), List.of());
    }

    private static Item question(String key, String text, QuestionType type, boolean required) {
        return new Item(key, text, null, type, required, false, List.of(), null, null, null,
                false, null, false, null, null, null, null, null, List.of(), List.of());
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
                        "  ", null, null, false, null, false, null, null, "  ", null, null, List.of(), List.of())));

        assertThat(messy.json()).isEqualTo(tidy.json())
                .doesNotContain("required").doesNotContain("help").doesNotContain("unit");
        assertThat(messy.hash()).isEqualTo(tidy.hash());
    }

    @Test
    void ignoresTheSchemaNumberTheClientSent() {
        var sent = new DefinitionDocument(99, List.of(), List.of(), List.of(), List.of());

        assertThat(canonicalizer.canonicalize(sent).json()).isEqualTo("{\"schema\":2}");
    }

    @Test
    void everyComponentLandsInItsOwnNamedSlot() {
        // A probe against transposition, not against the canonicaliser: one
        // Item built with a distinct value per component, read back by the
        // record's own named accessors. The compiler only checks arity — it
        // does not catch a value landing one slot over — so this is what
        // would have caught the mistake made (and corrected) while threading
        // evidenceRequired through every call site in this feature.
        Item follow = new Item("q9", "follow-text", null, QuestionType.TEXT, false, false, List.of(),
                null, null, null, false, null, false, null, null, null, null, null, List.of(), List.of());
        Option option = new Option("o1", "option-label", "PASS", 7, false);
        RangeRule rule = new RangeRule(1, 2, "PASS");
        Item item = new Item("q1", "item-text", "item-help", QuestionType.INTEGER, true, true,
                List.of(option), "item-unit", 3, 4, true, "item-alert", true, 5, ItemSource.DOCUMENT,
                "item-standard", "item-when", Rollup.WORST, List.of(follow), List.of(rule));

        assertThat(item.key()).isEqualTo("q1");
        assertThat(item.text()).isEqualTo("item-text");
        assertThat(item.help()).isEqualTo("item-help");
        assertThat(item.type()).isEqualTo(QuestionType.INTEGER);
        assertThat(item.required()).isTrue();
        assertThat(item.critical()).isTrue();
        assertThat(item.options()).containsExactly(option);
        assertThat(item.unit()).isEqualTo("item-unit");
        assertThat(item.min()).isEqualTo(3);
        assertThat(item.max()).isEqualTo(4);
        assertThat(item.workOrder()).isTrue();
        assertThat(item.alertProfile()).isEqualTo("item-alert");
        assertThat(item.evidenceRequired()).isTrue();
        assertThat(item.weight()).isEqualTo(5);
        assertThat(item.source()).isEqualTo(ItemSource.DOCUMENT);
        assertThat(item.standard()).isEqualTo("item-standard");
        assertThat(item.when()).isEqualTo("item-when");
        assertThat(item.followRollup()).isEqualTo(Rollup.WORST);
        assertThat(item.follow()).containsExactly(follow);
        assertThat(item.rules()).containsExactly(rule);
    }

    @Test
    void reCanonicalisingStoredBytesIsStable() {
        var first = canonicalizer.canonicalize(doc(
                new Item("s1", "Condition", null, QuestionType.SECTION, false, false, List.of(), null, null, null,
                        false, null, false, null, null, null, null, null, List.of(), List.of()),
                new Item("q2", "Gauge in the green?", "Tap it first", QuestionType.YES_NO_NA, true, false,
                        List.of(new Option("o3", "Yes", "PASS", null, false),
                                new Option("o4", "No", "FAIL", null, false),
                                new Option("o5", "N/A", null, null, false)),
                        null, null, null, true, "ap-std", false, null, null, "NFPA 10", null,
                        null,
                        List.of(new Item("q6", "Record the reading", null, QuestionType.INTEGER, false, false,
                                List.of(), "psi", 0, 300, false, null, false, null, null, null, "o4", null,
                                List.of(), List.of())), List.of())));

        var again = canonicalizer.canonicalize(canonicalizer.parse(first.json()));

        assertThat(again.json()).isEqualTo(first.json());
        assertThat(again.hash()).isEqualTo(first.hash());
    }

    @Test
    void aFollowUpIsPartOfTheContent() {
        Item parent = new Item("q2", "Exit clear?", null, QuestionType.YES_NO, false, false,
                List.of(new Option("o3", "Yes", "PASS", null, false), new Option("o4", "No", "FAIL", null, false)),
                null, null, null, false, null, false, null, null, null, null, null, List.of(), List.of());
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
                        "psi", 0, 300, false, null, false, null, null, null, null, null, List.of(), List.of())));

        assertThat(canonical.json()).contains("\"min\":0");
    }

    @Test
    void aQuestionWithoutBandsIsWrittenExactlyAsBefore() {
        // Bands arrived after versions had been published. An empty list is
        // dropped like any empty value, so those versions' bytes — and so their
        // hashes — are unchanged.
        var canonical = canonicalizer.canonicalize(doc(
                new Item("q2", "Reading", null, QuestionType.INTEGER, false, false, List.of(),
                        "psi", 0, 300, false, null, false, null, null, null, null, null, List.of(), List.of())));

        assertThat(canonical.json()).isEqualTo(
                "{\"items\":[{\"key\":\"q2\",\"max\":300,\"min\":0,\"text\":\"Reading\","
                + "\"type\":\"INTEGER\",\"unit\":\"psi\"}],\"schema\":2}");
    }

    @Test
    void bandsAreWrittenInOrderWithOpenEndsLeftOut() {
        var canonical = canonicalizer.canonicalize(doc(
                new Item("q2", "Pressure", null, QuestionType.INTEGER, false, false, List.of(),
                        null, null, null, false, null, false, null, null, null, null, null, List.of(),
                        List.of(new RangeRule(null, 0, "PASS"), new RangeRule(1, null, "FAIL")))));

        // An open end is a null, so it is absent; a 0 edge is a real one and stays.
        assertThat(canonical.json()).contains(
                "\"rules\":[{\"max\":0,\"result\":\"PASS\"},{\"min\":1,\"result\":\"FAIL\"}]");
        assertThat(canonicalizer.canonicalize(canonicalizer.parse(canonical.json())).json())
                .isEqualTo(canonical.json());
    }

    @Test
    void rejectsStoredBytesWithUnknownFields() {
        assertThatThrownBy(() -> canonicalizer.parse("{\"schema\":2,\"surprise\":true}"))
                .isInstanceOf(IllegalStateException.class);
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
        var bare = new DefinitionDocument(2, List.of(q), List.of(), List.of(), List.of());
        var banded = new DefinitionDocument(2, List.of(q),
                List.of(new Threshold(null, 0, 69, "FAIL"), new Threshold(null, 70, null, "PASS")), List.of(), List.of());

        assertThat(canonicalizer.canonicalize(bare).hash())
                .isNotEqualTo(canonicalizer.canonicalize(banded).hash());
        assertThat(canonicalizer.canonicalize(banded).json()).contains("\"thresholds\"");
    }

    @Test
    void aScoredDocumentSurvivesTheRoundTrip() {
        var first = canonicalizer.canonicalize(new DefinitionDocument(2,
                List.of(new Item("s1", "Fire exits", null, QuestionType.SECTION, false, false, List.of(),
                                null, null, null, false, null, false, 2, null, null, null, null, List.of(), List.of()),
                        new Item("q2", "Exit clear?", null, QuestionType.YES_NO, true, true,
                                List.of(new Option("o3", "Yes", "PASS", 10, false),
                                        new Option("o4", "No", "FAIL", 0, false),
                                        new Option("o5", "N/A", null, null, true)),
                                null, null, null, false, null, false, 3, null, null, null,
                                Rollup.WORST,
                                List.of(new Item("q6", "Why not?", null, QuestionType.TEXT, true, false,
                                        List.of(), null, null, null, false, null, false, null, null, null,
                                        "o4", null, List.of(), List.of())),
                                List.of())),
                List.of(new Threshold(null, null, 69, "FAIL"),
                        new Threshold(DefinitionDocument.Scope.SECTION, 70, null, "PASS")), List.of(), List.of()));

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
    void whatAProcedureAppliesToIsPartOfItsIdentity() {
        Item question = question("q1", "Present?", QuestionType.TEXT, false);
        DefinitionDocument anywhere = new DefinitionDocument(2, List.of(question), List.of(), List.of(), List.of());
        DefinitionDocument extinguishers = new DefinitionDocument(2, List.of(question), List.of(),
                List.of(new TargetType(TargetKind.ASSET_CLASS, "EXTINGUISHER")), List.of());

        assertThat(canonicalizer.canonicalize(anywhere).hash())
                .isNotEqualTo(canonicalizer.canonicalize(extinguishers).hash());
    }

    @Test
    void aProcedureThatAppliesToAnythingIsWrittenExactlyAsBefore() {
        // Every version published before target types existed has no such field,
        // and must keep its bytes and its hash.
        var canonical = canonicalizer.canonicalize(
                new DefinitionDocument(2, List.of(question("q1", "Present?", QuestionType.TEXT, false)), List.of(), List.of(), List.of()));

        assertThat(canonical.json()).doesNotContain("targetTypes");
        assertThat(canonical.json()).isEqualTo("{\"items\":[{\"key\":\"q1\",\"text\":\"Present?\",\"type\":\"TEXT\"}],\"schema\":2}");
    }

    @Test
    void targetTypesAreWrittenAndReadBack() {
        var canonical = canonicalizer.canonicalize(new DefinitionDocument(2,
                List.of(question("q1", "Present?", QuestionType.TEXT, false)), List.of(),
                List.of(new TargetType(TargetKind.HIERARCHY_LEVEL, "FLOOR"), new TargetType(TargetKind.ASSET_CLASS, "EXTINGUISHER")), List.of()));

        assertThat(canonical.json()).contains("\"targetTypes\":[{\"key\":\"FLOOR\",\"kind\":\"HIERARCHY_LEVEL\"},"
                + "{\"key\":\"EXTINGUISHER\",\"kind\":\"ASSET_CLASS\"}]");
        assertThat(canonicalizer.parse(canonical.json()).targetTypes())
                .containsExactly(new TargetType(TargetKind.HIERARCHY_LEVEL, "FLOOR"), new TargetType(TargetKind.ASSET_CLASS, "EXTINGUISHER"));
    }
}
