package com.sclera.applicationplane.procedure.definition;

import com.sclera.applicationplane.procedure.definition.DefinitionDiff.ItemChange;
import com.sclera.applicationplane.procedure.definition.DefinitionDiff.Kind;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.DocumentRef;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.RangeRule;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Threshold;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.TargetType;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.TargetKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.assertj.core.api.Assertions.tuple;

class DefinitionDiffTest {

    private static Item q(String key, String text) {
        return new Item(key, text, null, QuestionType.TEXT, false, false, List.of(), null, null, null,
                false, null, false, null, null, null, null, null, List.of(), List.of());
    }

    private static Item section(String key, String title) {
        return new Item(key, title, null, QuestionType.SECTION, false, false, List.of(), null, null, null,
                false, null, false, null, null, null, null, null, List.of(), List.of());
    }

    private static DefinitionDocument doc(Item... items) {
        return new DefinitionDocument(2, List.of(items), List.of(), List.of(), List.of());
    }

    @Test
    void identicalDocumentsHaveNoChanges() {
        DefinitionDocument d = doc(section("s1", "Fire"), q("q2", "Exit clear?"));

        DefinitionDiff diff = DefinitionDiff.between(d, d);

        assertThat(diff.identical()).isTrue();
        assertThat(diff.thresholdsChanged()).isFalse();
        assertThat(diff.documentsChanged()).isFalse();
        assertThat(diff.items()).isEmpty();
    }

    @Test
    void changingOnlyTheThresholdsIsStillAChange() {
        // The one that would quietly go wrong: thresholds hang off the document
        // rather than any item, so a diff that only walks items would call
        // these identical while their hashes differ.
        Item q = q("q2", "Exit clear?");
        DefinitionDocument v1 = new DefinitionDocument(2, List.of(q),
                List.of(new Threshold(null, null, 69, "FAIL"), new Threshold(null, 70, null, "PASS")), List.of(), List.of());
        DefinitionDocument v2 = new DefinitionDocument(2, List.of(q),
                List.of(new Threshold(null, null, 49, "FAIL"), new Threshold(null, 50, null, "PASS")), List.of(), List.of());

        DefinitionDiff diff = DefinitionDiff.between(v1, v2);

        assertThat(diff.identical()).isFalse();
        assertThat(diff.thresholdsChanged()).isTrue();
        assertThat(diff.items()).isEmpty();
    }

    @Test
    void changingOnlyTheDocumentsIsStillAChange() {
        // Same reasoning as thresholds: which documents are cited, and which
        // question each hangs off, is version content that no item carries.
        Item q = q("q2", "Exit clear?");
        DefinitionDocument v1 = new DefinitionDocument(2, List.of(q), List.of(),
                List.of(), List.of(new DocumentRef("doc-1", null)));
        DefinitionDocument v2 = new DefinitionDocument(2, List.of(q), List.of(),
                List.of(), List.of(new DocumentRef("doc-1", null), new DocumentRef("doc-2", "q2")));

        DefinitionDiff diff = DefinitionDiff.between(v1, v2);

        assertThat(diff.identical()).isFalse();
        assertThat(diff.documentsChanged()).isTrue();
        assertThat(diff.items()).isEmpty();
    }

    @Test
    void rescoringAnAnswerIsReportedOnTheQuestion() {
        Item before = q("q2", "Exit clear?").withOptions(List.of(
                new Option("o3", "Yes", "PASS", 10, false)));
        Item after = before.withOptions(List.of(new Option("o3", "Yes", "PASS", 5, false)));

        DefinitionDiff diff = DefinitionDiff.between(doc(before), doc(after));

        assertThat(diff.items()).singleElement()
                .extracting(ItemChange::changedFields).asInstanceOf(LIST)
                .containsExactly("options");
    }

    @Test
    void reportsAddedRemovedAndModifiedByKey() {
        DefinitionDocument v1 = doc(q("q2", "Exit clear?"), q("q3", "Old"));
        DefinitionDocument v2 = doc(
                new Item("q2", "Is the exit clear?", null, QuestionType.YES_NO, true, false, List.of(),
                        null, null, null, false, null, false, null, null, null, null, null, List.of(), List.of()),
                q("q4", "Brand new"));

        DefinitionDiff diff = DefinitionDiff.between(v1, v2);

        assertThat(diff.identical()).isFalse();
        assertThat(diff.items()).extracting(ItemChange::key, ItemChange::kind)
                .containsExactly(
                        tuple("q2", Kind.MODIFIED),
                        tuple("q4", Kind.ADDED),
                        tuple("q3", Kind.REMOVED));
        // Reworded and retyped — one entry, not a removal plus an addition.
        assertThat(diff.items().get(0).changedFields()).containsExactly("text", "type", "required");
    }

    @Test
    void insertingAtTheTopIsNotAReorder() {
        DefinitionDocument v1 = doc(q("q2", "A"), q("q3", "B"));
        DefinitionDocument v2 = doc(q("q4", "New"), q("q2", "A"), q("q3", "B"));

        DefinitionDiff diff = DefinitionDiff.between(v1, v2);

        assertThat(diff.orderChanged()).isFalse();
        assertThat(diff.items()).singleElement()
                .extracting(ItemChange::kind).isEqualTo(Kind.ADDED);
    }

    @Test
    void swappingTwoItemsIsAReorder() {
        DefinitionDocument v1 = doc(q("q2", "A"), q("q3", "B"));
        DefinitionDocument v2 = doc(q("q3", "B"), q("q2", "A"));

        DefinitionDiff diff = DefinitionDiff.between(v1, v2);

        assertThat(diff.items()).isEmpty();
        assertThat(diff.orderChanged()).isTrue();
        assertThat(diff.identical()).isFalse();
    }

    @Test
    void movingAQuestionUnderADifferentSectionIsReportedOnTheQuestion() {
        Item moved = q("q3", "A");
        DefinitionDocument v1 = doc(section("s1", "Fire").withFollow(List.of(moved)), section("s2", "Electrical"));
        DefinitionDocument v2 = doc(section("s1", "Fire"), section("s2", "Electrical").withFollow(List.of(moved)));

        DefinitionDiff diff = DefinitionDiff.between(v1, v2);

        assertThat(diff.items()).singleElement().satisfies(i -> {
            assertThat(i.kind()).isEqualTo(Kind.MODIFIED);
            assertThat(i.changedFields()).containsExactly("parent");
            assertThat(i.parentKey()).isEqualTo("s2");
        });
    }

    @Test
    void rewordingAnAnswerIsReportedWithoutLosingTheFollowUp() {
        // The option keeps its key, so the follow-up that points at it is
        // untouched — which is the whole reason options are keyed.
        Item v1Parent = new Item("q2", "Clear?", null, QuestionType.YES_NO, false, false,
                List.of(new Option("o3", "Yes", "PASS", null, false), new Option("o4", "No", "FAIL", null, false)),
                null, null, null, false, null, false, null, null, null, null,
                null,
                List.of(q("q5", "Describe it").withKey("q5")),
                List.of());
        Item v2Parent = v1Parent.withOptions(
                List.of(new Option("o3", "Yes", "PASS", null, false), new Option("o4", "No — blocked", "FAIL", null, false)));

        DefinitionDiff diff = DefinitionDiff.between(doc(v1Parent), doc(v2Parent));

        assertThat(diff.items()).singleElement().satisfies(i -> {
            assertThat(i.key()).isEqualTo("q2");
            assertThat(i.changedFields()).containsExactly("options");
        });
    }

    @Test
    void aFollowUpIsComparedLikeAnyOtherItem() {
        Item parent = new Item("q2", "Clear?", null, QuestionType.YES_NO, false, false,
                List.of(new Option("o3", "No", "FAIL", null, false)), null, null, null,
                false, null, false, null, null, null, null, null, List.of(), List.of());
        DefinitionDocument v1 = doc(parent.withFollow(List.of(q("q4", "Why?"))));
        DefinitionDocument v2 = doc(parent.withFollow(List.of(q("q4", "Why not?"))));

        DefinitionDiff diff = DefinitionDiff.between(v1, v2);

        assertThat(diff.items()).singleElement().satisfies(i -> {
            assertThat(i.key()).isEqualTo("q4");
            assertThat(i.parentKey()).isEqualTo("q2");
            assertThat(i.changedFields()).containsExactly("text");
        });
    }

    @Test
    void movingABandsEdgeIsReportedAsRules() {
        Item v1 = new Item("q2", "Pressure", null, QuestionType.INTEGER, true, false, List.of(),
                "psi", null, null, false, null, false, null, null, null, null, null, List.of(),
                List.of(new RangeRule(null, 11, "PASS"), new RangeRule(12, null, "FAIL")));
        Item v2 = new Item("q2", "Pressure", null, QuestionType.INTEGER, true, false, List.of(),
                "psi", null, null, false, null, false, null, null, null, null, null, List.of(),
                List.of(new RangeRule(null, 13, "PASS"), new RangeRule(14, null, "FAIL")));

        DefinitionDiff diff = DefinitionDiff.between(doc(v1), doc(v2));

        // What a reading of 12 means has changed — the one edit a compare
        // must not miss, and min/max are untouched.
        assertThat(diff.items()).singleElement().satisfies(i -> {
            assertThat(i.key()).isEqualTo("q2");
            assertThat(i.changedFields()).containsExactly("rules");
        });
    }

    @Test
    void widenningWhatAProcedureAppliesToIsADocumentLevelChange() {
        DefinitionDocument v1 = new DefinitionDocument(2, List.of(q("q1", "Present?")), List.of(),
                List.of(new TargetType(TargetKind.ASSET_CLASS, "EXTINGUISHER")), List.of());
        DefinitionDocument v2 = new DefinitionDocument(2, List.of(q("q1", "Present?")), List.of(),
                List.of(new TargetType(TargetKind.ASSET_CLASS, "EXTINGUISHER"), new TargetType(TargetKind.ASSET_CLASS, "HOSE_REEL")), List.of());

        DefinitionDiff diff = DefinitionDiff.between(v1, v2);

        // Not a single question changed, yet the versions differ.
        assertThat(diff.targetTypesChanged()).isTrue();
        assertThat(diff.items()).isEmpty();
        assertThat(diff.identical()).isFalse();
    }

    @Test
    void sameTargetTypesIsNotAChange() {
        DefinitionDocument v1 = new DefinitionDocument(2, List.of(q("q1", "Present?")), List.of(),
                List.of(new TargetType(TargetKind.ASSET_CLASS, "EXTINGUISHER")), List.of());

        DefinitionDiff diff = DefinitionDiff.between(v1, v1);

        assertThat(diff.targetTypesChanged()).isFalse();
        assertThat(diff.identical()).isTrue();
    }
}
