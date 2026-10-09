package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.definition.DefinitionCanonicalizer;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.domain.GlobalProcedureTemplate;
import com.sclera.applicationplane.procedure.domain.GlobalProcedureTemplateVersion;
import com.sclera.applicationplane.procedure.domain.GlobalQuestionIndexEntry;
import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.applicationplane.procedure.domain.VersionState;
import com.sclera.applicationplane.procedure.event.GlobalTemplateEvent;
import com.sclera.applicationplane.procedure.event.GlobalTemplateEventListener;
import com.sclera.applicationplane.procedure.repository.GlobalProcedureTemplateRepository;
import com.sclera.applicationplane.procedure.repository.GlobalProcedureTemplateVersionRepository;
import com.sclera.applicationplane.procedure.repository.GlobalQuestionIndexRepository;
import com.sclera.applicationplane.procedure.support.PostgresIntegrationTest;
import com.sclera.applicationplane.procedure.tenancy.PropertyContext;
import com.sclera.applicationplane.procedure.tenancy.TenantSchemas;
import com.sclera.controlplane.common.security.OrgContext;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.support.serializer.SerializationUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The question bank's index: what gets written when a global version is
 * published, and the plumbing that gets the event to the code that writes it.
 *
 * Nothing here needs a broker. The indexer is called the way the listener calls
 * it, with a hand-built event, and the Kafka configuration is exercised by
 * running the real deserializer over the bytes the real serializer produces.
 * Every test runs with no organization in context, because a Kafka listener has
 * none: that is the condition the index has to work under.
 */
class GlobalQuestionIndexIT extends PostgresIntegrationTest {

    @Autowired
    private GlobalProcedureTemplateRepository templates;

    @Autowired
    private GlobalProcedureTemplateVersionRepository versions;

    @Autowired
    private GlobalQuestionIndexRepository index;

    @Autowired
    private GlobalQuestionIndexer indexer;

    @Autowired
    private GlobalTemplateEventListener listener;

    @Autowired
    private DefinitionCanonicalizer canonicalizer;

    @Autowired
    private KafkaProperties kafkaProperties;

    @Autowired
    private JdbcTemplate jdbc;

    private record Seeded(UUID templateId, UUID versionId) {
    }

    @BeforeEach
    void noOrganization() {
        OrgContext.clear();
        PropertyContext.clear();
    }

    // --- builders -----------------------------------------------------------

    private static Item question(String key, String text) {
        return Item.builder().key(key).text(text).type(QuestionType.YES_NO).build();
    }

    private static Item section(String key, String text) {
        return Item.builder().key(key).text(text).type(QuestionType.SECTION).build();
    }

    private static DefinitionDocument document(Item... items) {
        return new DefinitionDocument(DefinitionDocument.CURRENT_SCHEMA, List.of(items), List.of(), List.of(),
                List.of());
    }

    private UUID newTemplate() {
        GlobalProcedureTemplate template = new GlobalProcedureTemplate();
        template.setName("Library " + UUID.randomUUID());
        return templates.saveAndFlush(template).getId();
    }

    private UUID newVersion(UUID templateId, int versionNo, VersionState state, DefinitionDocument document) {
        DefinitionCanonicalizer.Canonical canonical = canonicalizer.canonicalize(document);
        GlobalProcedureTemplateVersion version = new GlobalProcedureTemplateVersion();
        version.setGlobalTemplateId(templateId);
        version.setVersionNo(versionNo);
        version.setState(state);
        version.setDefinitionJson(canonical.json());
        version.setDefinitionHash(canonical.hash());
        return versions.saveAndFlush(version).getId();
    }

    private Seeded published(DefinitionDocument document) {
        UUID templateId = newTemplate();
        return new Seeded(templateId, newVersion(templateId, 1, VersionState.PUBLISHED, document));
    }

    private static GlobalTemplateEvent published(UUID templateId, UUID versionId) {
        return new GlobalTemplateEvent(UUID.randomUUID(), GlobalTemplateEvent.EventType.PUBLISHED, templateId,
                versionId, 1, OffsetDateTime.now());
    }

    private List<GlobalQuestionIndexEntry> rows(UUID versionId) {
        return index.findAllByGlobalVersionIdOrderByQuestionKey(versionId);
    }

    // --- what is written ------------------------------------------------------

    @Test
    void everyQuestionIsIndexedAndNoHeadingIs() {
        Seeded p = published(document(
                Item.builder().key("q1").text("Is the sign visible?").type(QuestionType.YES_NO)
                        .standard("NFPA 10").build(),
                section("s2", "Fire safety"),
                question("q3", "Is the extinguisher in date?"),
                question("q4", "Is the pin intact?")));

        int written = indexer.indexPublished(published(p.templateId(), p.versionId()));

        assertThat(written).isEqualTo(3);
        List<GlobalQuestionIndexEntry> rows = rows(p.versionId());
        assertThat(rows).extracting(GlobalQuestionIndexEntry::getQuestionKey).containsExactly("q1", "q3", "q4");
        assertThat(rows).extracting(GlobalQuestionIndexEntry::getText)
                .containsExactly("Is the sign visible?", "Is the extinguisher in date?", "Is the pin intact?");
        assertThat(rows).allSatisfy(r -> assertThat(r.getGlobalTemplateId()).isEqualTo(p.templateId()));
    }

    @Test
    void aQuestionRecordsTheHeadingItSitsUnderAndTheStandardItCameFrom() {
        Seeded p = published(document(
                question("q1", "Above every heading"),
                section("s2", "Fire safety"),
                Item.builder().key("q3").text("Under the first").type(QuestionType.YES_NO)
                        .standard("NFPA 10").build(),
                section("s4", "Housekeeping"),
                question("q5", "Under the second")));

        indexer.indexPublished(published(p.templateId(), p.versionId()));

        Map<String, GlobalQuestionIndexEntry> byKey = new java.util.HashMap<>();
        rows(p.versionId()).forEach(r -> byKey.put(r.getQuestionKey(), r));
        assertThat(byKey.get("q1").getSectionText()).as("above the first heading").isNull();
        assertThat(byKey.get("q3").getSectionText()).isEqualTo("Fire safety");
        assertThat(byKey.get("q5").getSectionText()).isEqualTo("Housekeeping");
        assertThat(byKey.get("q3").getStandard()).isEqualTo("NFPA 10");
        assertThat(byKey.get("q5").getStandard()).isNull();
    }

    @Test
    void aFollowUpIsIndexedUnderItsParentsHeading() {
        Item parent = Item.builder().key("q2").text("Is it pressurised?").type(QuestionType.YES_NO)
                .follow(List.of(question("q3", "What is the gauge reading?"))).build();
        Seeded p = published(document(section("s1", "Pressure"), parent));

        indexer.indexPublished(published(p.templateId(), p.versionId()));

        List<GlobalQuestionIndexEntry> rows = rows(p.versionId());
        assertThat(rows).extracting(GlobalQuestionIndexEntry::getQuestionKey).containsExactly("q2", "q3");
        assertThat(rows).extracting(GlobalQuestionIndexEntry::getSectionText).containsOnly("Pressure");
    }

    @Test
    void aQuestionWithoutAKeyIsLeftOutRatherThanFailingTheWholeVersion() {
        Seeded p = published(document(question("q1", "Keyed"), question(null, "Never keyed")));

        int written = indexer.indexPublished(published(p.templateId(), p.versionId()));

        assertThat(written).isEqualTo(1);
        assertThat(rows(p.versionId())).extracting(GlobalQuestionIndexEntry::getQuestionKey).containsExactly("q1");
    }

    // --- safe to repeat ---------------------------------------------------------

    @Test
    void theSameEventTwiceDoesNotDuplicateAnyRow() {
        // Kafka delivers at least once, so the same event arriving again must be harmless.
        Seeded p = published(document(question("q1", "One"), question("q2", "Two"), question("q3", "Three")));
        GlobalTemplateEvent event = published(p.templateId(), p.versionId());

        indexer.indexPublished(event);
        indexer.indexPublished(event);

        assertThat(rows(p.versionId())).hasSize(3);
    }

    @Test
    void indexingAgainReplacesWhatWasThereInsteadOfAddingToIt() {
        Seeded p = published(document(question("q1", "Original wording"), question("q2", "Will go")));
        indexer.indexPublished(published(p.templateId(), p.versionId()));

        DefinitionCanonicalizer.Canonical changed = canonicalizer.canonicalize(
                document(question("q1", "New wording"), question("q9", "Added")));
        GlobalProcedureTemplateVersion version = versions.findById(p.versionId()).orElseThrow();
        version.setDefinitionJson(changed.json());
        version.setDefinitionHash(changed.hash());
        versions.saveAndFlush(version);
        indexer.indexPublished(published(p.templateId(), p.versionId()));

        List<GlobalQuestionIndexEntry> rows = rows(p.versionId());
        assertThat(rows).extracting(GlobalQuestionIndexEntry::getQuestionKey).containsExactly("q1", "q9");
        assertThat(rows.get(0).getText()).isEqualTo("New wording");
    }

    @Test
    void indexingOneVersionLeavesAnotherVersionsRowsAlone() {
        UUID templateId = newTemplate();
        UUID v1 = newVersion(templateId, 1, VersionState.PUBLISHED, document(question("q1", "In version one")));
        UUID v2 = newVersion(templateId, 2, VersionState.PUBLISHED,
                document(question("q1", "In version two"), question("q2", "Only in two")));

        indexer.indexPublished(published(templateId, v1));
        indexer.indexPublished(published(templateId, v2));
        indexer.indexPublished(published(templateId, v2));

        assertThat(rows(v1)).extracting(GlobalQuestionIndexEntry::getText).containsExactly("In version one");
        assertThat(rows(v2)).hasSize(2);
    }

    // --- what is refused ----------------------------------------------------------

    @Test
    void aDraftVersionIsNeverIndexedWhateverTheEventSays() {
        // A draft's questions must not reach a bank every organization searches.
        UUID templateId = newTemplate();
        UUID draft = newVersion(templateId, 1, VersionState.DRAFT, document(question("q1", "Unfinished")));

        int written = indexer.indexPublished(published(templateId, draft));

        assertThat(written).isZero();
        assertThat(rows(draft)).isEmpty();
    }

    @Test
    void anEventNamingAVersionThatDoesNotExistIsIgnoredNotRetried() {
        long before = index.count();

        int written = indexer.indexPublished(published(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(written).isZero();
        assertThat(index.count()).isEqualTo(before);
    }

    @Test
    void anEventNamingNoVersionIsIgnored() {
        int written = indexer.indexPublished(published(UUID.randomUUID(), null));

        assertThat(written).isZero();
    }

    // --- the wiring ---------------------------------------------------------------

    @Test
    void theListenerHandsAPublishedEventToTheIndexer() {
        Seeded p = published(document(question("q1", "Heard"), question("q2", "Also heard")));

        listener.onGlobalTemplateEvent(published(p.templateId(), p.versionId()));

        assertThat(rows(p.versionId())).hasSize(2);
    }

    @Test
    void theConfiguredConsumerReadsTheJsonTheProducerSends() {
        // The producer adds no type headers, so the consumer has to be told the
        // type from its own configuration. This runs the real serializer and the
        // real deserializer, built from the real application settings.
        GlobalTemplateEvent sent = published(UUID.randomUUID(), UUID.randomUUID());
        byte[] bytes;
        try (JsonSerializer<GlobalTemplateEvent> serializer = new JsonSerializer<>()) {
            bytes = serializer.serialize(GlobalTemplateEvent.TOPIC, sent);
        }

        try (ErrorHandlingDeserializer<Object> deserializer = new ErrorHandlingDeserializer<>()) {
            deserializer.configure(kafkaProperties.buildConsumerProperties(null), false);
            Object received = deserializer.deserialize(GlobalTemplateEvent.TOPIC, new RecordHeaders(), bytes);

            assertThat(received).isInstanceOf(GlobalTemplateEvent.class);
            GlobalTemplateEvent event = (GlobalTemplateEvent) received;
            assertThat(event.eventId()).isEqualTo(sent.eventId());
            assertThat(event.eventType()).isEqualTo(GlobalTemplateEvent.EventType.PUBLISHED);
            assertThat(event.globalTemplateId()).isEqualTo(sent.globalTemplateId());
            assertThat(event.versionId()).isEqualTo(sent.versionId());
            assertThat(event.versionNo()).isEqualTo(1);
            assertThat(event.occurredAt().toInstant()).isEqualTo(sent.occurredAt().toInstant());
        }
    }

    @Test
    void anUnreadableMessageIsSkippedAndNotFatal() {
        // Without this, one bad message would make the consumer fail on it forever.
        RecordHeaders headers = new RecordHeaders();
        try (ErrorHandlingDeserializer<Object> deserializer = new ErrorHandlingDeserializer<>()) {
            deserializer.configure(kafkaProperties.buildConsumerProperties(null), false);

            Object received = deserializer.deserialize(GlobalTemplateEvent.TOPIC, headers,
                    "this is not json".getBytes(java.nio.charset.StandardCharsets.UTF_8));

            assertThat(received).isNull();
            assertThat(headers.lastHeader(SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER)).isNotNull();
        }
    }

    @Test
    void theConsumerIsConfiguredToUseTheForgivingDeserializer() {
        // The test above builds the deserializer itself, so this is what proves the
        // application settings actually select it.
        assertThat(kafkaProperties.getConsumer().getValueDeserializer()).isEqualTo(ErrorHandlingDeserializer.class);
    }

    @Test
    void theIndexCanBeReadFromInsideAnOrdinaryTenantScopedRequest() {
        // The browse endpoints will read it as an organization, whose search_path is
        // its own schema with no public fallback. That only works because the entity
        // is schema-qualified, so it is proved here and not assumed.
        Seeded p = published(document(question("q1", "Readable from a tenant")));
        indexer.indexPublished(published(p.templateId(), p.versionId()));
        UUID org = actAsNewOrg();

        List<GlobalQuestionIndexEntry> seen = asOrg(org, () -> index.findAllByGlobalVersionIdOrderByQuestionKey(p.versionId()));

        assertThat(seen).extracting(GlobalQuestionIndexEntry::getText).containsExactly("Readable from a tenant");
    }

    // --- where the table lives ------------------------------------------------------

    @Test
    void theTableIsOnlyInPublicAndNotCopiedIntoAnOrganizationsSchema() {
        // Like the other global tables: shared content, no tenant copy. Pinned so
        // that someone who reflexively writes the tenant migration notices.
        UUID org = actAsNewOrg();
        OrgContext.clear();

        List<String> schemas = jdbc.queryForList(
                "SELECT table_schema FROM information_schema.tables WHERE table_name = 'global_question_index'",
                String.class);

        assertThat(schemas).containsExactly("public");
        assertThat(schemas).doesNotContain(TenantSchemas.schemaFor(org));
    }
}
