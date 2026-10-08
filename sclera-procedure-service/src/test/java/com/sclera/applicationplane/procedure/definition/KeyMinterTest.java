package com.sclera.applicationplane.procedure.definition;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.controlplane.common.exception.ValidationException;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.TargetType;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.TargetKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyMinterTest {

    private static Item q(String key, String text) {
        return new Item(key, text, null, QuestionType.TEXT, false, false, List.of(), null, null, null,
                false, null, false, null, null, null, null, null, List.of(), List.of());
    }

    private static Item section(String key, String title) {
        return new Item(key, title, null, QuestionType.SECTION, false, false, List.of(), null, null, null,
                false, null, false, null, null, null, null, null, List.of(), List.of());
    }

    private static Item choice(String key, String text, Option... options) {
        return new Item(key, text, null, QuestionType.YES_NO, false, false, List.of(options), null, null, null,
                false, null, false, null, null, null, null, null, List.of(), List.of());
    }

    private static DefinitionDocument doc(Item... items) {
        return new DefinitionDocument(2, List.of(items), List.of(), List.of());
    }

    @Test
    void mintsKeysInDocumentOrderAcrossSectionsQuestionsAndOptions() {
        AtomicInteger seq = new AtomicInteger(0);

        DefinitionDocument result = KeyMinter.assignKeys(
                doc(section(null, "Fire safety"),
                    choice(null, "Exit clear?",
                            new Option(null, "Yes", "PASS", null, false),
                            new Option("", "No", "FAIL", null, false))),
                0, seq::incrementAndGet);

        assertThat(result.items()).extracting(Item::key).containsExactly("s1", "q2");
        assertThat(result.items().get(1).options()).extracting(Option::key).containsExactly("o3", "o4");
        // One counter for all three kinds, so a number is never reused.
        assertThat(seq.get()).isEqualTo(4);
    }

    @Test
    void mintsIntoFollowUpsAtEveryDepth() {
        AtomicInteger seq = new AtomicInteger(0);

        Item grandchild = q(null, "Explain why not");
        Item child = q(null, "Was it replaced?").withFollow(List.of(grandchild));
        DefinitionDocument result = KeyMinter.assignKeys(
                doc(q(null, "Seal intact?").withFollow(List.of(child))), 0, seq::incrementAndGet);

        Item parent = result.items().get(0);
        assertThat(parent.key()).isEqualTo("q1");
        assertThat(parent.follow().get(0).key()).isEqualTo("q2");
        assertThat(parent.follow().get(0).follow().get(0).key()).isEqualTo("q3");
    }

    @Test
    void keepsExistingKeysAndMintsOnlyForNewItems() {
        AtomicInteger seq = new AtomicInteger(3);

        DefinitionDocument result = KeyMinter.assignKeys(
                doc(q(null, "New one"), q("q2", "Exit clear?")), 3, seq::incrementAndGet);

        assertThat(result.items()).extracting(Item::key).containsExactly("q4", "q2");
    }

    @Test
    void anExistingOptionKeepsItsKeyWhenRelabelled() {
        // The point of keying options: a follow-up's `when` still resolves
        // after the author rewords the answer it hangs off.
        AtomicInteger seq = new AtomicInteger(4);

        DefinitionDocument result = KeyMinter.assignKeys(
                doc(choice("q2", "Clear?", new Option("o3", "No — blocked", "FAIL", null, false))),
                4, seq::incrementAndGet);

        assertThat(result.items().get(0).options()).extracting(Option::key).containsExactly("o3");
        assertThat(seq.get()).isEqualTo(4);
    }

    @Test
    void rejectsKeysTheTemplateNeverIssued() {
        assertThatThrownBy(() -> KeyMinter.assignKeys(doc(q("q9", "Invented")), 3, () -> 4))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("'q9' was never issued");
    }

    @Test
    void rejectsTheWrongPrefixForTheKind() {
        assertThatThrownBy(() -> KeyMinter.assignKeys(
                doc(section("q1", "Section with a question key")), 5, () -> 6))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("'q1' cannot be used on a section");

        assertThatThrownBy(() -> KeyMinter.assignKeys(
                doc(choice("q2", "X", new Option("q3", "Yes", "PASS", null, false))), 5, () -> 6))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("'q3' cannot be used on an answer");
    }

    @Test
    void rejectsADuplicateKeyAnywhereInTheTree() {
        assertThatThrownBy(() -> KeyMinter.assignKeys(
                doc(q("q2", "A"), q("q3", "B").withFollow(List.of(q("q2", "Same key, nested")))),
                5, () -> 6))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("'q2' appears more than once");
    }

    @Test
    void mintsNothingWhenAnySuppliedKeyIsBad() {
        AtomicInteger seq = new AtomicInteger(1);

        assertThatThrownBy(() -> KeyMinter.assignKeys(doc(q("zz", "Bad")), 1, seq::incrementAndGet))
                .isInstanceOf(ValidationException.class);
        assertThat(seq.get()).isEqualTo(1);
    }

    @Test
    void mintingKeysLeavesWhatTheProcedureAppliesToAlone() {
        // It rebuilds the document from its parts; a part left out of that is
        // dropped on every single save.
        DefinitionDocument document = new DefinitionDocument(2, List.of(q(null, "Present?")), List.of(),
                List.of(new TargetType(TargetKind.ASSET_CLASS, "EXTINGUISHER")));

        DefinitionDocument keyed = KeyMinter.assignKeys(document, 0, new java.util.concurrent.atomic.AtomicInteger()::incrementAndGet);

        assertThat(keyed.targetTypes()).containsExactly(new TargetType(TargetKind.ASSET_CLASS, "EXTINGUISHER"));
    }
}
