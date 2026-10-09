package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.controller.GlobalLibraryController;
import com.sclera.applicationplane.procedure.definition.DefinitionCanonicalizer;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.domain.GlobalProcedureTemplate;
import com.sclera.applicationplane.procedure.domain.GlobalProcedureTemplateVersion;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.applicationplane.procedure.domain.TemplateStatus;
import com.sclera.applicationplane.procedure.domain.VersionState;
import com.sclera.applicationplane.procedure.dto.GlobalLibraryBrowseDtos.QuestionBankEntry;
import com.sclera.applicationplane.procedure.event.GlobalTemplateEvent;
import com.sclera.applicationplane.procedure.repository.GlobalProcedureTemplateRepository;
import com.sclera.applicationplane.procedure.repository.GlobalProcedureTemplateVersionRepository;
import com.sclera.applicationplane.procedure.support.PostgresIntegrationTest;
import com.sclera.applicationplane.procedure.tenancy.PropertyContext;
import com.sclera.controlplane.common.security.OrgContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The question bank: searching the questions of the whole Sclera-wide library.
 *
 * Rows get into the index the way they do in production, by publishing a version
 * and handing the indexer its event, so this also proves the search reads what the
 * indexer writes. The global tables are shared by every test in the run, so each
 * test puts a token of its own in what it plants and searches for that token.
 */
class GlobalQuestionBankIT extends PostgresIntegrationTest {

    @Autowired
    private GlobalLibraryService library;

    @Autowired
    private GlobalLibraryController controller;

    @Autowired
    private GlobalQuestionIndexer indexer;

    @Autowired
    private GlobalProcedureTemplateRepository templates;

    @Autowired
    private GlobalProcedureTemplateVersionRepository versions;

    @Autowired
    private DefinitionCanonicalizer canonicalizer;

    // The actuator registers a second mapping of the same type, so the application's own is named.
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private record Planted(UUID templateId, UUID versionId) {
    }

    @BeforeEach
    void noOrganization() {
        OrgContext.clear();
        PropertyContext.clear();
    }

    // --- builders -----------------------------------------------------------

    /** A word no other test uses, safe to search for. Letters only, so full-text search keeps it whole. */
    private static String token() {
        StringBuilder word = new StringBuilder("zq");
        for (char c : UUID.randomUUID().toString().replace("-", "").toCharArray()) {
            word.append((char) (c >= '0' && c <= '9' ? 'g' + (c - '0') : c));
        }
        return word.toString();
    }

    private static Item question(String key, String text) {
        return Item.builder().key(key).text(text).type(QuestionType.YES_NO).build();
    }

    private static Item question(String key, String text, String standard) {
        return Item.builder().key(key).text(text).type(QuestionType.YES_NO).standard(standard).build();
    }

    private static Item section(String key, String text) {
        return Item.builder().key(key).text(text).type(QuestionType.SECTION).build();
    }

    private static DefinitionDocument document(Item... items) {
        return new DefinitionDocument(DefinitionDocument.CURRENT_SCHEMA, List.of(items), List.of(), List.of(),
                List.of());
    }

    private UUID newTemplate(String name) {
        GlobalProcedureTemplate template = new GlobalProcedureTemplate();
        template.setName(name);
        return templates.saveAndFlush(template).getId();
    }

    /** Publishes a version, indexes it, and makes it the template's current one. */
    private UUID publishVersion(UUID templateId, int versionNo, DefinitionDocument document) {
        DefinitionCanonicalizer.Canonical canonical = canonicalizer.canonicalize(document);
        GlobalProcedureTemplateVersion version = new GlobalProcedureTemplateVersion();
        version.setGlobalTemplateId(templateId);
        version.setVersionNo(versionNo);
        version.setState(VersionState.PUBLISHED);
        version.setDefinitionJson(canonical.json());
        version.setDefinitionHash(canonical.hash());
        version.setPublishedAt(OffsetDateTime.now());
        UUID versionId = versions.saveAndFlush(version).getId();
        indexer.indexPublished(new GlobalTemplateEvent(UUID.randomUUID(), GlobalTemplateEvent.EventType.PUBLISHED,
                templateId, versionId, versionNo, OffsetDateTime.now()));
        GlobalProcedureTemplate template = templates.findById(templateId).orElseThrow();
        template.setCurrentPublishedVersionId(versionId);
        templates.saveAndFlush(template);
        return versionId;
    }

    private Planted plant(String templateName, Item... items) {
        UUID templateId = newTemplate(templateName);
        return new Planted(templateId, publishVersion(templateId, 1, document(items)));
    }

    private List<String> texts(Page<QuestionBankEntry> page) {
        return page.getContent().stream().map(QuestionBankEntry::text).toList();
    }

    private Page<QuestionBankEntry> search(String search, String standard) {
        return library.searchQuestions(search, standard, 0, 20);
    }

    // --- finding a question -----------------------------------------------------

    @Test
    void aQuestionIsFoundByAWordInsideItsTextAndSaysWhereItCameFrom() {
        String t = token();
        Planted p = plant("Extinguisher round " + t,
                question("q1", "Above every heading"),
                section("s2", "Fire safety"),
                question("q3", "Is the " + t + " gauge in the green?", "NFPA 10"));

        Page<QuestionBankEntry> found = search(t, null);

        assertThat(found.getContent()).hasSize(1);
        QuestionBankEntry hit = found.getContent().get(0);
        assertThat(hit.questionKey()).isEqualTo("q3");
        assertThat(hit.text()).isEqualTo("Is the " + t + " gauge in the green?");
        assertThat(hit.standard()).isEqualTo("NFPA 10");
        assertThat(hit.sectionText()).isEqualTo("Fire safety");
        assertThat(hit.templateId()).isEqualTo(p.templateId());
        assertThat(hit.templateName()).isEqualTo("Extinguisher round " + t);
        assertThat(hit.versionNo()).isEqualTo(1);
    }

    @Test
    void aQuestionAboveTheFirstHeadingHasNoSection() {
        String t = token();
        plant("Plain " + t, question("q1", "Is the " + t + " clear?"));

        assertThat(search(t, null).getContent()).extracting(QuestionBankEntry::sectionText).containsExactly((String) null);
    }

    @Test
    void theSearchUnderstandsWordFormsNotJustExactText() {
        // Full-text search stems words, which a plain substring match would not.
        String t = token();
        plant("Stemming " + t, question("q1", "Are all " + t + " extinguishers charged?"));

        assertThat(texts(search(t + " extinguisher", null))).hasSize(1);
        assertThat(texts(search("charging " + t, null))).hasSize(1);
    }

    @Test
    void aSearchThatMatchesNothingIsAnEmptyPageNotAnError() {
        assertThat(search(token(), null).getContent()).isEmpty();
    }

    @Test
    void searchInputOfAnyShapeNeverRaisesAnError() {
        // websearch_to_tsquery takes whatever a person can type. A stricter parser
        // would turn an unbalanced quote or a stray operator into a 500.
        assertThatCode(() -> {
            search("\"unbalanced quote", null);
            search("a & | ( ! b", null);
            search("-", null);
            search("the", null);
            search("%_'; drop table global_question_index; --", null);
        }).doesNotThrowAnyException();
    }

    // --- only what is current ---------------------------------------------------

    @Test
    void onlyTheCurrentVersionsQuestionsAreInTheBank() {
        // Every version ever published is in the index; the bank must not offer a
        // question twice, or one the template no longer asks.
        String old = token();
        String current = token();
        UUID templateId = newTemplate("Versioned " + old);
        publishVersion(templateId, 1, document(question("q1", "The " + old + " wording"),
                question("q2", "Kept as " + current)));
        publishVersion(templateId, 2, document(question("q2", "Kept as " + current),
                question("q3", "New " + current + " wording")));

        assertThat(search(old, null).getContent()).as("a question only version 1 had").isEmpty();
        Page<QuestionBankEntry> kept = search(current, null);
        assertThat(kept.getContent()).extracting(QuestionBankEntry::questionKey).containsExactlyInAnyOrder("q2", "q3");
        assertThat(kept.getContent()).as("once each, not once per version")
                .extracting(QuestionBankEntry::versionNo).containsOnly(2);
    }

    @Test
    void anArchivedTemplatesQuestionsLeaveTheBank() {
        String t = token();
        Planted p = plant("Retiring " + t, question("q1", "A " + t + " question"));
        assertThat(search(t, null).getContent()).hasSize(1);
        GlobalProcedureTemplate template = templates.findById(p.templateId()).orElseThrow();
        template.setStatus(TemplateStatus.ARCHIVED);
        templates.saveAndFlush(template);

        assertThat(search(t, null).getContent()).isEmpty();
    }

    // --- the standard filter -----------------------------------------------------

    @Test
    void theStandardFilterIgnoresCaseAndNarrowsASearch() {
        String t = token();
        plant("Standards " + t,
                question("q1", "First " + t, "NFPA 10"),
                question("q2", "Second " + t, "ISO 9001"),
                question("q3", "Third " + t));

        assertThat(texts(search(t, null))).hasSize(3);
        assertThat(texts(search(t, "nfpa 10"))).containsExactly("First " + t);
        assertThat(texts(search(t, "  ISO 9001 "))).containsExactly("Second " + t);
        assertThat(texts(search(t, "BS 5839"))).isEmpty();
    }

    @Test
    void theStandardAloneListsItsQuestionsWithNoSearchWords() {
        String standard = "STD" + token().substring(2, 22);
        plant("One " + token(), question("q1", "Alpha", standard), question("q2", "Beta", "Other"));
        plant("Two " + token(), question("q1", "Gamma", standard));

        assertThat(texts(search(null, standard))).containsExactlyInAnyOrder("Alpha", "Gamma");
        assertThat(texts(search("", standard))).containsExactlyInAnyOrder("Alpha", "Gamma");
    }

    @Test
    void aBlankSearchAndStandardMeanNoFilter() {
        String t = token();
        plant("Anything " + t, question("q1", "Here " + t));

        assertThat(library.searchQuestions("   ", "   ", 0, 20).getTotalElements()).isGreaterThanOrEqualTo(1);
    }

    // --- order and paging ---------------------------------------------------------

    @Test
    void theMostRelevantQuestionComesFirst() {
        String t = token();
        // The weak match has the name that sorts first, so name order and relevance
        // order disagree and only relevance can put the strong match on top.
        plant("Alpha " + t, question("q1", "A passing mention of " + t + " among many other words here"));
        plant("Zeta " + t, question("q1", t + " " + t + " " + t));

        List<String> templateNames = search(t, null).getContent().stream().map(QuestionBankEntry::templateName).toList();

        assertThat(templateNames).containsExactly("Zeta " + t, "Alpha " + t);
    }

    @Test
    void withNoSearchTheOrderIsTemplateNameThenKey() {
        String standard = "STD" + token().substring(2, 22);
        plant("B template " + token(), question("q2", "b two", standard), question("q1", "b one", standard));
        plant("A template " + token(), question("q1", "a one", standard));

        assertThat(texts(search(null, standard))).containsExactly("a one", "b one", "b two");
    }

    @Test
    void templatesWithTheSameNameAreOrderedByQuestionKey() {
        // Two templates can share a name. The key is what breaks that tie, so the
        // order of a page never depends on which row the database happened to read first.
        String standard = "STD" + token().substring(2, 22);
        plant("Same name", question("q2", "planted first", standard));
        plant("Same name", question("q1", "planted second", standard));

        assertThat(texts(search(null, standard))).containsExactly("planted second", "planted first");
    }

    @Test
    void theBankIsPaged() {
        String standard = "STD" + token().substring(2, 22);
        List<Item> items = new ArrayList<>();
        for (int n = 1; n <= 25; n++) {
            items.add(question(String.format("q%02d", n), String.format("Paged %02d", n), standard));
        }
        plant("Paged " + token(), items.toArray(new Item[0]));

        Page<QuestionBankEntry> first = library.searchQuestions(null, standard, 0, 20);
        Page<QuestionBankEntry> second = library.searchQuestions(null, standard, 1, 20);

        assertThat(first.getTotalElements()).isEqualTo(25);
        assertThat(first.getContent()).hasSize(20);
        assertThat(second.getContent()).hasSize(5);
        assertThat(first.getContent().get(0).text()).isEqualTo("Paged 01");
        assertThat(second.getContent().get(4).text()).isEqualTo("Paged 25");
    }

    @Test
    void aPageSizeIsCutToTheMaximumAndAnOddPageIsRaisedToAValidOne() {
        String standard = "STD" + token().substring(2, 22);
        plant("Clamp " + token(), question("q1", "Only one", standard));

        assertThat(controller.questions(null, standard, 0, 100_000).getSize()).isEqualTo(100);
        assertThat(library.searchQuestions(null, standard, -3, 0).getSize()).isEqualTo(1);
        assertThat(library.searchQuestions(null, standard, -3, 0).getNumber()).isZero();
    }

    // --- who may search and how the route resolves --------------------------------

    @Test
    void everyOrganizationSearchesTheSameBank() {
        String t = token();
        plant("Shared " + t, question("q1", "Shared " + t));
        UUID orgA = actAsNewOrg();
        UUID orgB = actAsNewOrg();

        long seenByA = asOrg(orgA, () -> search(t, null).getTotalElements());
        long seenByB = asOrg(orgB, () -> search(t, null).getTotalElements());

        assertThat(seenByA).isEqualTo(1);
        assertThat(seenByB).isEqualTo(1);
    }

    @Test
    void theQuestionsPathIsTheBankAndNotATemplateIdOnTheDetailEndpoint() {
        // Both are GETs under the same prefix, one a literal and one a variable.
        assertThat(handlerFor("/api/v1/global-procedure-templates/questions")).isEqualTo("questions");
        assertThat(handlerFor("/api/v1/global-procedure-templates/" + UUID.randomUUID())).isEqualTo("get");
        assertThat(handlerFor("/api/v1/global-procedure-templates")).isEqualTo("list");
    }

    private String handlerFor(String path) {
        try {
            HandlerExecutionChain chain = handlerMapping.getHandler(new MockHttpServletRequest("GET", path));
            return ((HandlerMethod) chain.getHandler()).getMethod().getName();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
