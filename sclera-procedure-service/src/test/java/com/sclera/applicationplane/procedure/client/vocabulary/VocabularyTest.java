package com.sclera.applicationplane.procedure.client.vocabulary;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VocabularyTest {

    private static Vocabulary withExtinguisherAndRetiredHoseReel() {
        return new Vocabulary(Map.of(VocabularyKind.ASSET_CLASS, List.of(
                new VocabularyEntry("EXTINGUISHER", "Fire extinguisher", 1, true),
                new VocabularyEntry("HOSE_REEL", "Hose reel", 2, false))));
    }

    @Test
    void aKeyThatIsThereIsContainedAndActive() {
        Vocabulary vocabulary = withExtinguisherAndRetiredHoseReel();

        assertThat(vocabulary.contains(VocabularyKind.ASSET_CLASS, "EXTINGUISHER")).isTrue();
        assertThat(vocabulary.isActive(VocabularyKind.ASSET_CLASS, "EXTINGUISHER")).isTrue();
    }

    @Test
    void aRetiredKeyIsStillContainedButNoLongerActive() {
        // A published version that names it must stay valid; a new one may not use it.
        Vocabulary vocabulary = withExtinguisherAndRetiredHoseReel();

        assertThat(vocabulary.contains(VocabularyKind.ASSET_CLASS, "HOSE_REEL")).isTrue();
        assertThat(vocabulary.isActive(VocabularyKind.ASSET_CLASS, "HOSE_REEL")).isFalse();
    }

    @Test
    void aKeyThatIsNotThereIsNeitherContainedNorActive() {
        Vocabulary vocabulary = withExtinguisherAndRetiredHoseReel();

        assertThat(vocabulary.contains(VocabularyKind.ASSET_CLASS, "SPRINKLER")).isFalse();
        assertThat(vocabulary.isActive(VocabularyKind.ASSET_CLASS, "SPRINKLER")).isFalse();
    }

    @Test
    void aKeyIsOnlyInTheListOfItsOwnKind() {
        // EXTINGUISHER is an asset class, not a location type.
        assertThat(withExtinguisherAndRetiredHoseReel().contains(VocabularyKind.LOCATION_TYPE, "EXTINGUISHER")).isFalse();
    }

    @Test
    void aKindLeftOutIsAnEmptyListNotAnError() {
        Vocabulary vocabulary = new Vocabulary(Map.of());

        assertThat(vocabulary.entries(VocabularyKind.ASSET_TAG)).isEmpty();
        assertThat(vocabulary.contains(VocabularyKind.ASSET_TAG, "ANYTHING")).isFalse();
        assertThat(new Vocabulary(null).entries(VocabularyKind.HIERARCHY_LEVEL)).isEmpty();
    }

    @Test
    void theEntriesComeBackAsGiven() {
        assertThat(withExtinguisherAndRetiredHoseReel().entries(VocabularyKind.ASSET_CLASS))
                .extracting(VocabularyEntry::key).containsExactly("EXTINGUISHER", "HOSE_REEL");
    }
}