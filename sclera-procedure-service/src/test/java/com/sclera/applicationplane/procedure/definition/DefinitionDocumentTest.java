package com.sclera.applicationplane.procedure.definition;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Group;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Which section each question belongs to. Nothing in the document says — a
 * section does not contain its questions — so {@link DefinitionDocument#groups()}
 * works it out from position, and these hold it to the same rule the screen
 * that shows a procedure uses.
 */
class DefinitionDocumentTest {

    private static Item q(String key) {
        return new Item(key, "Question " + key, null, QuestionType.TEXT, false, false, List.of(), null, null, null,
                false, null, false, null, null, null, null, null, List.of(), List.of());
    }

    private static Item section(String key) {
        return new Item(key, "Section " + key, null, QuestionType.SECTION, false, false, List.of(), null, null, null,
                false, null, false, null, null, null, null, null, List.of(), List.of());
    }

    private static DefinitionDocument doc(Item... items) {
        return new DefinitionDocument(DefinitionDocument.CURRENT_SCHEMA, List.of(items), List.of(), List.of());
    }

    /** Each group as (section key or null, its question keys) — enough to see the shape at a glance. */
    private static List<Tuple> shape(DefinitionDocument document) {
        return document.groups().stream()
                .map(g -> tuple(g.section() == null ? null : g.section().key(),
                        g.questions().stream().map(Item::key).toList()))
                .toList();
    }

    @Test
    void aQuestionBelongsToTheSectionAboveIt() {
        assertThat(shape(doc(section("s1"), q("q2"), q("q3"), section("s4"), q("q5"))))
                .containsExactly(
                        tuple("s1", List.of("q2", "q3")),
                        tuple("s4", List.of("q5")));
    }

    @Test
    void questionsBeforeTheFirstHeadingFormOneGroupWithNoSectionAndComeFirst() {
        assertThat(shape(doc(q("q1"), q("q2"), section("s3"), q("q4"))))
                .containsExactly(
                        tuple(null, List.of("q1", "q2")),
                        tuple("s3", List.of("q4")));
    }

    @Test
    void aDocumentThatStartsWithAHeadingHasNoUngroupedGroup() {
        // The group with no section exists only when something needs it.
        assertThat(doc(section("s1"), q("q2")).groups())
                .extracting(Group::section)
                .doesNotContainNull();
    }

    @Test
    void aSectionWithNothingUnderItIsStillAGroup() {
        assertThat(shape(doc(section("s1"), section("s2"), q("q3"), section("s4"))))
                .containsExactly(
                        tuple("s1", List.of()),
                        tuple("s2", List.of("q3")),
                        tuple("s4", List.of()));
    }

    @Test
    void aDocumentWithNoHeadingsIsOneGroupWithNoSection() {
        assertThat(shape(doc(q("q1"), q("q2"))))
                .containsExactly(tuple(null, List.of("q1", "q2")));
    }

    @Test
    void anEmptyDocumentHasNoGroups() {
        assertThat(DefinitionDocument.empty().groups()).isEmpty();
    }

    @Test
    void followUpsStayInsideTheirParentRatherThanJoiningTheGroup() {
        Item parent = q("q2").withFollow(List.of(q("q3").withFollow(List.of(q("q4")))));

        List<Group> groups = doc(section("s1"), parent, q("q5")).groups();

        // Only the top-level questions are listed; the follow-ups ride along
        // inside q2, at whatever depth they were written.
        assertThat(groups).singleElement().satisfies(g -> {
            assertThat(g.questions()).extracting(Item::key).containsExactly("q2", "q5");
            assertThat(g.questions().get(0).follow().get(0).follow())
                    .extracting(Item::key).containsExactly("q4");
        });
    }

    @Test
    void aGroupCannotBeChangedByWhoeverReadsIt() {
        Group group = doc(section("s1"), q("q2")).groups().get(0);

        assertThat(group.questions()).isUnmodifiable();
    }
}
