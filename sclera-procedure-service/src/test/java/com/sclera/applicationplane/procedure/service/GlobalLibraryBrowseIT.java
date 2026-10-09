package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.definition.DefinitionCanonicalizer;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.domain.GlobalProcedureTemplate;
import com.sclera.applicationplane.procedure.domain.GlobalProcedureTemplateVersion;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.applicationplane.procedure.domain.TemplateStatus;
import com.sclera.applicationplane.procedure.domain.VersionState;
import com.sclera.applicationplane.procedure.dto.GlobalLibraryBrowseDtos.GlobalTemplateDetail;
import com.sclera.applicationplane.procedure.dto.GlobalLibraryBrowseDtos.GlobalTemplateSummary;
import com.sclera.applicationplane.procedure.repository.GlobalProcedureTemplateRepository;
import com.sclera.applicationplane.procedure.repository.GlobalProcedureTemplateVersionRepository;
import com.sclera.applicationplane.procedure.support.PostgresIntegrationTest;
import com.sclera.applicationplane.procedure.tenancy.PropertyContext;
import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import com.sclera.controlplane.common.security.OrgContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Browsing the Sclera-wide library: the list, its two filters, and opening one
 * template.
 *
 * The global tables are shared by every test in this run, so a test never asserts
 * on "the whole library". Each one gives its templates a name carrying a token of
 * its own and searches for that token, which leaves other tests' rows out of the
 * answer without having to clean up after them.
 */
class GlobalLibraryBrowseIT extends PostgresIntegrationTest {

    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20, Sort.by("name"));

    @Autowired
    private GlobalLibraryService library;

    @Autowired
    private GlobalProcedureTemplateRepository templates;

    @Autowired
    private GlobalProcedureTemplateVersionRepository versions;

    @Autowired
    private DefinitionCanonicalizer canonicalizer;

    @BeforeEach
    void noOrganization() {
        OrgContext.clear();
        PropertyContext.clear();
    }

    // --- builders -----------------------------------------------------------

    private static String token() {
        return "tok" + UUID.randomUUID().toString().replace("-", "");
    }

    private static DefinitionDocument document(String questionText) {
        Item question = Item.builder().key("q1").text(questionText).type(QuestionType.YES_NO).build();
        return new DefinitionDocument(DefinitionDocument.CURRENT_SCHEMA, List.of(question), List.of(), List.of(),
                List.of());
    }

    private GlobalProcedureTemplateVersion publishedVersion(UUID templateId, int versionNo, String questionText,
                                                            String changeNote) {
        DefinitionCanonicalizer.Canonical canonical = canonicalizer.canonicalize(document(questionText));
        GlobalProcedureTemplateVersion version = new GlobalProcedureTemplateVersion();
        version.setGlobalTemplateId(templateId);
        version.setVersionNo(versionNo);
        version.setState(VersionState.PUBLISHED);
        version.setDefinitionJson(canonical.json());
        version.setDefinitionHash(canonical.hash());
        version.setChangeNote(changeNote);
        version.setPublishedAt(OffsetDateTime.now());
        return versions.saveAndFlush(version);
    }

    /** A published template, named with the token. */
    private UUID published(String name, String description, String consumer) {
        GlobalProcedureTemplate template = new GlobalProcedureTemplate();
        template.setName(name);
        template.setDescription(description);
        template.setConsumerKey(consumer);
        template = templates.saveAndFlush(template);
        GlobalProcedureTemplateVersion v1 = publishedVersion(template.getId(), 1, "First wording", "first");
        template.setCurrentPublishedVersionId(v1.getId());
        templates.saveAndFlush(template);
        return template.getId();
    }

    private List<String> namesFound(String search, String consumer) {
        return library.browse(search, consumer, FIRST_PAGE).map(GlobalTemplateSummary::name).getContent();
    }

    // --- the list ---------------------------------------------------------------

    @Test
    void aPublishedTemplateAppearsWithItsCurrentVersionNumber() {
        String t = token();
        UUID id = published("Boiler " + t, "Annual boiler check", "INSPECTION");
        GlobalProcedureTemplate template = templates.findById(id).orElseThrow();
        GlobalProcedureTemplateVersion v2 = publishedVersion(id, 2, "Second wording", "second");
        template.setCurrentPublishedVersionId(v2.getId());
        templates.saveAndFlush(template);

        Page<GlobalTemplateSummary> page = library.browse(t, null, FIRST_PAGE);

        assertThat(page.getContent()).hasSize(1);
        GlobalTemplateSummary summary = page.getContent().get(0);
        assertThat(summary.id()).isEqualTo(id);
        assertThat(summary.name()).isEqualTo("Boiler " + t);
        assertThat(summary.description()).isEqualTo("Annual boiler check");
        assertThat(summary.consumerKey()).isEqualTo("INSPECTION");
        assertThat(summary.currentVersionNo()).as("the version an import would take").isEqualTo(2);
        assertThat(summary.publishedAt()).isNotNull();
    }

    @Test
    void aTemplateNeverPublishedIsNotListed() {
        // Nothing in it could be imported, so it is not offered.
        String t = token();
        GlobalProcedureTemplate draftOnly = new GlobalProcedureTemplate();
        draftOnly.setName("Unfinished " + t);
        templates.saveAndFlush(draftOnly);

        assertThat(namesFound(t, null)).isEmpty();
    }

    @Test
    void anArchivedTemplateIsNotListed() {
        String t = token();
        UUID id = published("Retired " + t, null, null);
        GlobalProcedureTemplate template = templates.findById(id).orElseThrow();
        template.setStatus(TemplateStatus.ARCHIVED);
        templates.saveAndFlush(template);

        assertThat(namesFound(t, null)).isEmpty();
    }

    @Test
    void theListIsPagedAndSortedByName() {
        String t = token();
        for (int n = 1; n <= 25; n++) {
            published(String.format("%s item %02d", t, n), null, null);
        }

        Page<GlobalTemplateSummary> first = library.browse(t, null, PageRequest.of(0, 20, Sort.by("name")));
        Page<GlobalTemplateSummary> second = library.browse(t, null, PageRequest.of(1, 20, Sort.by("name")));

        assertThat(first.getTotalElements()).isEqualTo(25);
        assertThat(first.getContent()).hasSize(20);
        assertThat(second.getContent()).hasSize(5);
        assertThat(first.getContent().get(0).name()).endsWith("item 01");
        assertThat(second.getContent().get(4).name()).endsWith("item 25");
    }

    // --- search -----------------------------------------------------------------

    @Test
    void searchFindsAWordInTheNameWhateverItsCase() {
        String t = token();
        published("Fire Extinguisher " + t, null, null);
        published("Boiler " + t, null, null);

        assertThat(namesFound("EXTINGUISHER " + t, null)).containsExactly("Fire Extinguisher " + t);
        assertThat(namesFound(t.toUpperCase(), null)).hasSize(2);
    }

    @Test
    void searchAlsoLooksInTheDescription() {
        String t = token();
        published("Plain name " + t, "Checks the pressure gauge", null);
        published("Other " + t, "Checks the door", null);

        assertThat(namesFound("pressure gauge", null)).contains("Plain name " + t).doesNotContain("Other " + t);
    }

    @Test
    void aSearchThatMatchesNothingIsAnEmptyPageNotAnError() {
        assertThat(namesFound("zzz-" + token(), null)).isEmpty();
    }

    @Test
    void whatIsTypedIsTakenLiterallyNotAsAWildcard() {
        // A % or _ in the box must match itself. If it were passed through as a
        // wildcard, "100%" would match every template.
        String t = token();
        published("Half " + t, "Passes at 100% of items", null);
        published("Whole " + t, "Passes at 100 percent", null);
        published("Under_score " + t, null, null);
        published("Underxscore " + t, null, null);   // what an unescaped _ would wrongly match

        assertThat(namesFound("100%", null)).contains("Half " + t).doesNotContain("Whole " + t);
        assertThat(namesFound("under_score", null)).containsExactly("Under_score " + t);
    }

    // --- consumer ---------------------------------------------------------------

    @Test
    void theConsumerFilterAndTheSearchWorkIndependentlyAndTogether() {
        String t = token();
        published("Alpha " + t, null, "INSPECTION");
        published("Beta " + t, null, "TASK");
        published("Gamma " + t, null, "INSPECTION");

        assertThat(namesFound(t, null)).hasSize(3);                                       // neither filter beyond the token
        assertThat(namesFound(t, "INSPECTION")).containsExactly("Alpha " + t, "Gamma " + t); // consumer narrows it
        assertThat(namesFound("alpha " + t, null)).containsExactly("Alpha " + t);         // search alone
        assertThat(namesFound("beta " + t, "INSPECTION")).isEmpty();                      // both, and they disagree
        assertThat(namesFound("beta " + t, "TASK")).containsExactly("Beta " + t);         // both, and they agree
    }

    @Test
    void aConsumerIsTrimmedAndUpperCasedAsItIsEverywhereElse() {
        String t = token();
        published("Alpha " + t, null, "INSPECTION");

        assertThat(namesFound(t, "  inspection ")).containsExactly("Alpha " + t);
    }

    @Test
    void aBlankConsumerOrSearchMeansNoFilter() {
        String t = token();
        published("Alpha " + t, null, "INSPECTION");

        assertThat(library.browse("   ", "   ", FIRST_PAGE).getTotalElements()).isGreaterThanOrEqualTo(1);
        assertThat(namesFound(t, "")).containsExactly("Alpha " + t);
    }

    // --- one template ---------------------------------------------------------------

    @Test
    void openingATemplateGivesItsCurrentVersionsWholeForm() {
        String t = token();
        UUID id = published("Boiler " + t, "Annual check", "INSPECTION");
        GlobalProcedureTemplate template = templates.findById(id).orElseThrow();
        GlobalProcedureTemplateVersion v2 = publishedVersion(id, 2, "Is the boiler lit?", "reworded");
        template.setCurrentPublishedVersionId(v2.getId());
        templates.saveAndFlush(template);

        GlobalTemplateDetail detail = library.get(id);

        assertThat(detail.id()).isEqualTo(id);
        assertThat(detail.name()).isEqualTo("Boiler " + t);
        assertThat(detail.description()).isEqualTo("Annual check");
        assertThat(detail.consumerKey()).isEqualTo("INSPECTION");
        assertThat(detail.currentVersionNo()).isEqualTo(2);
        assertThat(detail.changeNote()).isEqualTo("reworded");
        assertThat(detail.definition().items()).extracting(Item::text).containsExactly("Is the boiler lit?");
    }

    @Test
    void anUnknownTemplateIsNotFound() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> library.get(id)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aTemplateNeverPublishedOrArchivedCannotBeOpened() {
        GlobalProcedureTemplate draftOnly = new GlobalProcedureTemplate();
        draftOnly.setName("Unfinished " + token());
        UUID draftOnlyId = templates.saveAndFlush(draftOnly).getId();

        UUID archivedId = published("Retired " + token(), null, null);
        GlobalProcedureTemplate archived = templates.findById(archivedId).orElseThrow();
        archived.setStatus(TemplateStatus.ARCHIVED);
        templates.saveAndFlush(archived);

        assertThatThrownBy(() -> library.get(draftOnlyId)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> library.get(archivedId)).isInstanceOf(ResourceNotFoundException.class);
    }

    // --- who may browse ---------------------------------------------------------------

    @Test
    void everyOrganizationSeesTheSameLibrary() {
        // The library is Sclera's, not any organization's, so who asks changes nothing.
        String t = token();
        UUID id = published("Shared " + t, null, null);
        UUID orgA = actAsNewOrg();
        UUID orgB = actAsNewOrg();

        List<UUID> seenByA = asOrg(orgA, () -> library.browse(t, null, FIRST_PAGE)
                .map(GlobalTemplateSummary::id).getContent());
        List<UUID> seenByB = asOrg(orgB, () -> library.browse(t, null, FIRST_PAGE)
                .map(GlobalTemplateSummary::id).getContent());
        UUID opened = asOrg(orgA, () -> library.get(id).id());

        assertThat(seenByA).containsExactly(id);
        assertThat(seenByB).containsExactly(id);
        assertThat(opened).isEqualTo(id);
    }
}
