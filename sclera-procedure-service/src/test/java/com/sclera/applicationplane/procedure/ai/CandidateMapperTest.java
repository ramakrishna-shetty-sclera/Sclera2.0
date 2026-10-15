package com.sclera.applicationplane.procedure.ai;

import com.sclera.applicationplane.procedure.ai.CandidateQuestion.CandidateBand;
import com.sclera.applicationplane.procedure.ai.CandidateQuestion.CandidateFollowUp;
import com.sclera.applicationplane.procedure.ai.CandidateQuestion.CandidateOption;
import com.sclera.applicationplane.procedure.domain.ItemSource;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.controlplane.common.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The gate between LLM output and persistence: deterministic repair where the
 * fix is obvious, rejection where it is not, and the result always passes the
 * structure validator.
 */
class CandidateMapperTest {

    private static final Set<String> ACTIVE = Set.of("PASS", "FAIL", "AMBER");

    private final CandidateMapper mapper = new CandidateMapper();

    @Test
    void yesNoIsForcedToCanonicalOptionsWithPassFail() {
        CandidateQuestion sanitized = mapper.sanitize(question(QuestionType.YES_NO,
                List.of(new CandidateOption("Yep", "AMBER"))), ACTIVE);

        assertThat(sanitized.options()).extracting(CandidateOption::label)
                .containsExactly("Yes", "No");
        assertThat(sanitized.options()).extracting(CandidateOption::result)
                .containsExactly("PASS", "FAIL");
    }

    @Test
    void yesNoInvertedMappingIsKeptWhenKeysAreReal() {
        CandidateQuestion sanitized = mapper.sanitize(question(QuestionType.YES_NO,
                List.of(new CandidateOption("Yes", "FAIL"), new CandidateOption("No", "PASS"))), ACTIVE);

        assertThat(sanitized.options()).extracting(CandidateOption::result)
                .containsExactly("FAIL", "PASS");
    }

    @Test
    void resultMappingOnOtherChoiceTypesIsStrippedForTheAuthor() {
        CandidateQuestion sanitized = mapper.sanitize(question(QuestionType.RADIO,
                List.of(new CandidateOption("Full", "PASS"), new CandidateOption("Empty", "FAIL"))), ACTIVE);

        assertThat(sanitized.options()).extracting(CandidateOption::result)
                .containsExactly(null, null);
    }

    @Test
    void optionsOnNonChoiceTypesAreDropped() {
        CandidateQuestion sanitized = mapper.sanitize(question(QuestionType.TEXT,
                List.of(new CandidateOption("Yes", null))), ACTIVE);

        assertThat(sanitized.options()).isEmpty();
    }

    @Test
    void integerKeepsItsScaleAndSwapsAnInvertedRange() {
        CandidateQuestion sanitized = mapper.sanitize(new CandidateQuestion(
                "Pressure reading", null, QuestionType.INTEGER, true,
                null, "psi", 200, 100,
                List.of(new CandidateBand(100, 150, "PASS"), new CandidateBand(160, 150, "FAIL")),
                null, null), ACTIVE);

        assertThat(sanitized.min()).isEqualTo(100);
        assertThat(sanitized.max()).isEqualTo(200);
        assertThat(sanitized.unit()).isEqualTo("psi");
        // The inverted band is dropped; the surviving band's result is the
        // author's to map, so it comes back null.
        assertThat(sanitized.rules()).hasSize(1);
        assertThat(sanitized.rules().get(0).result()).isNull();
    }

    @Test
    void unitAndBandsOnNonIntegerAreDropped() {
        CandidateQuestion sanitized = mapper.sanitize(new CandidateQuestion(
                "Door closes?", null, QuestionType.YES_NO, true,
                null, "psi", 1, 5, List.of(new CandidateBand(1, 5, "PASS")), null, null), ACTIVE);

        assertThat(sanitized.unit()).isNull();
        assertThat(sanitized.min()).isNull();
        assertThat(sanitized.max()).isNull();
        assertThat(sanitized.rules()).isEmpty();
    }

    @Test
    void followUpPointingAtNoOptionIsDropped() {
        CandidateQuestion sanitized = mapper.sanitize(new CandidateQuestion(
                "Extinguisher present?", null, QuestionType.YES_NO, true,
                null, null, null, null, null, null,
                List.of(followUp(1, "Describe what is missing"),
                        followUp(9, "Never shown"))), ACTIVE);

        assertThat(sanitized.followUps()).hasSize(1);
        assertThat(sanitized.followUps().get(0).text()).isEqualTo("Describe what is missing");
    }

    @Test
    void blankTextIsRejectedNotRepaired() {
        assertThatThrownBy(() -> mapper.sanitize(new CandidateQuestion(
                "   ", null, QuestionType.TEXT, false, null, null, null, null, null, null, null), ACTIVE))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("no text");
    }

    @Test
    void importMintsOptionKeysOnlyWhenAFollowUpNeedsThem() {
        CandidateQuestion plain = mapper.sanitize(question(QuestionType.YES_NO, null), ACTIVE);
        Item plainItem = mapper.toItem(plain, ItemSource.DOCUMENT, failingSupplier());
        assertThat(plainItem.options()).allMatch(o -> o.key() == null);
        assertThat(plainItem.source()).isEqualTo(ItemSource.DOCUMENT);

        CandidateQuestion withFollowUp = mapper.sanitize(new CandidateQuestion(
                "Extinguisher present?", null, QuestionType.YES_NO, true,
                null, null, null, null, null, null,
                List.of(followUp(1, "Describe what is missing"))), ACTIVE);
        AtomicInteger seq = new AtomicInteger(40);
        Item keyed = mapper.toItem(withFollowUp, ItemSource.SUGGESTED, () -> "o" + seq.incrementAndGet());

        assertThat(keyed.options()).extracting(o -> o.key()).containsExactly("o41", "o42");
        assertThat(keyed.follow()).hasSize(1);
        // The follow-up fires on option index 1 ("No"), which was minted o42.
        assertThat(keyed.follow().get(0).when()).isEqualTo("o42");
        assertThat(keyed.follow().get(0).source()).isEqualTo(ItemSource.SUGGESTED);
    }

    private static CandidateQuestion question(QuestionType type, List<CandidateOption> options) {
        return new CandidateQuestion("Door closes and latches?", null, type, true,
                options, null, null, null, null, null, null);
    }

    private static CandidateFollowUp followUp(int whenOptionIndex, String text) {
        return new CandidateFollowUp(whenOptionIndex, text, null, QuestionType.TEXT, false,
                null, null, null, null, null, null);
    }

    private static Supplier<String> failingSupplier() {
        return () -> {
            throw new AssertionError("No option keys should be minted without follow-ups");
        };
    }
}
