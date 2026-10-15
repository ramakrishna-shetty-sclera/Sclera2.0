package com.sclera.applicationplane.procedure.definition;

import com.sclera.applicationplane.procedure.definition.DefinitionDiff.ItemChange;
import com.sclera.applicationplane.procedure.definition.DefinitionDiff.Kind;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.RangeRule;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class DefinitionDiffTest {

    private static Item q(String key, String text) {
        return new Item(key, text, null, QuestionType.TEXT, false, List.of(), null, null, null,
                false, null, null, null, null, List.of(), List.of());
    }

    private static Item section(String key, String title) {
        return new Item(key, title, null, QuestionType.SECTION, false, List.of(), null, null, null,
                false, null, null, null, null, List.of(), List.of());
    }

    private static DefinitionDocument doc(Item... items) {
        return new DefinitionDocument(2, List.of(items));
    }

    @Test
    void identicalDocumentsHaveNoChanges() {
        DefinitionDocument d = doc(section("s1", "Fire"), q("q2", "Exit clear?"));

        DefinitionDiff diff = DefinitionDiff.between(d, d);

        assertThat(diff.identical()).isTrue();
        assertThat(diff.items()).isEmpty();
    }

    @Test
    void reportsAddedRemovedAndModifiedByKey() {
        DefinitionDocument v1 = doc(q("q2", "Exit clear?"), q("q3", "Old"));
        DefinitionDocument v2 = doc(
                new Item("q2", "Is the exit clear?", null, QuestionType.YES_NO, true, List.of(),
                        null, null, null, false, null, null, null, null, List.of(), List.of()),
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
        Item v1Parent = new Item("q2", "Clear?", null, QuestionType.YES_NO, false,
                List.of(new Option("o3", "Yes", "PASS"), new Option("o4", "No", "FAIL")),
                null, null, null, false, null, null, null, null,
                List.of(q("q5", "Describe it").withKey("q5")), List.of());
        Item v2Parent = v1Parent.withOptions(
                List.of(new Option("o3", "Yes", "PASS"), new Option("o4", "No — blocked", "FAIL")));

        DefinitionDiff diff = DefinitionDiff.between(doc(v1Parent), doc(v2Parent));

        assertThat(diff.items()).singleElement().satisfies(i -> {
            assertThat(i.key()).isEqualTo("q2");
            assertThat(i.changedFields()).containsExactly("options");
        });
    }

    @Test
    void aFollowUpIsComparedLikeAnyOtherItem() {
        Item parent = new Item("q2", "Clear?", null, QuestionType.YES_NO, false,
                List.of(new Option("o3", "No", "FAIL")), null, null, null,
                false, null, null, null, null, List.of(), List.of());
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
        Item v1 = new Item("q2", "Pressure", null, QuestionType.INTEGER, true, List.of(),
                "psi", null, null, false, null, null, null, null, List.of(),
                List.of(new RangeRule(null, 11, "PASS"), new RangeRule(12, null, "FAIL")));
        Item v2 = new Item("q2", "Pressure", null, QuestionType.INTEGER, true, List.of(),
                "psi", null, null, false, null, null, null, null, List.of(),
                List.of(new RangeRule(null, 13, "PASS"), new RangeRule(14, null, "FAIL")));

        DefinitionDiff diff = DefinitionDiff.between(doc(v1), doc(v2));

        // What a reading of 12 means has changed — the one edit a compare
        // must not miss, and min/max are untouched.
        assertThat(diff.items()).singleElement().satisfies(i -> {
            assertThat(i.key()).isEqualTo("q2");
            assertThat(i.changedFields()).containsExactly("rules");
        });
    }
}
