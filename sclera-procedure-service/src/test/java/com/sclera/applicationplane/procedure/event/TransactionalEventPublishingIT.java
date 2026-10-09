package com.sclera.applicationplane.procedure.event;

import com.sclera.applicationplane.procedure.domain.GlobalProcedureTemplate;
import com.sclera.applicationplane.procedure.domain.GlobalProcedureTemplateVersion;
import com.sclera.applicationplane.procedure.domain.ProcedureTemplate;
import com.sclera.applicationplane.procedure.domain.ProcedureTemplateVersion;
import com.sclera.applicationplane.procedure.domain.VersionState;
import com.sclera.applicationplane.procedure.repository.GlobalProcedureTemplateRepository;
import com.sclera.applicationplane.procedure.repository.GlobalProcedureTemplateVersionRepository;
import com.sclera.applicationplane.procedure.repository.ProcedureTemplateRepository;
import com.sclera.applicationplane.procedure.repository.ProcedureTemplateVersionRepository;
import com.sclera.applicationplane.procedure.tenancy.PropertyContext;
import com.sclera.applicationplane.procedure.tenancy.TenantRegistryService;
import com.sclera.controlplane.common.security.OrgContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The regression check that matters most for the dual-write fix: an event
 * must reach Kafka only once its transaction has actually committed, and
 * must never reach Kafka at all if that transaction rolls back.
 *
 * <p>Deliberately does <b>not</b> extend {@code PostgresIntegrationTest} —
 * that base class mocks both {@link TemplateEventPublisher} and
 * {@link GlobalTemplateEventPublisher} wholesale (so every other test can
 * assert "the service called publish()" without touching Kafka), which
 * would make the one thing this test needs to observe — whether the real
 * publisher actually reaches {@link KafkaTemplate} — invisible. This class
 * mocks only {@code KafkaTemplate} itself, leaving both publishers real.
 *
 * <p>No {@code @Transactional} on these test methods: each one drives its
 * own transaction explicitly via {@link TransactionTemplate}, so the test
 * controls exactly when it commits or rolls back rather than relying on
 * Spring Test's own (rollback-always) transactional test support, which
 * would make "the transaction committed" unobservable. The entity each test
 * publishes an event about is persisted inside that same transaction, the
 * same shape every real service method here already has.
 */
@SpringBootTest
@ActiveProfiles("test")
class TransactionalEventPublishingIT {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("sclera_procedure");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "sclera_app");
        registry.add("spring.datasource.password", () -> "sclera_app");
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("sclera.datasource.owner.username", POSTGRES::getUsername);
        registry.add("sclera.datasource.owner.password", POSTGRES::getPassword);
        registry.add("sclera.fga.enabled", () -> "false");
        registry.add("spring.kafka.admin.auto-create", () -> "false");
    }

    @MockBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private TemplateEventPublisher events;

    @Autowired
    private GlobalTemplateEventPublisher globalEvents;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ProcedureTemplateRepository templates;

    @Autowired
    private ProcedureTemplateVersionRepository versions;

    @Autowired
    private GlobalProcedureTemplateRepository globalTemplates;

    @Autowired
    private GlobalProcedureTemplateVersionRepository globalVersions;

    @Autowired
    private TenantRegistryService tenantRegistry;

    private UUID actAsNewOrg() {
        UUID orgId = UUID.randomUUID();
        OrgContext.setOrgId(orgId);
        OrgContext.setUserId(UUID.randomUUID());
        tenantRegistry.ensureTenant(orgId);
        return orgId;
    }

    @AfterEach
    void clearContext() {
        OrgContext.clear();
        PropertyContext.clear();
    }

    private ProcedureTemplateVersion persistedTemplateAndVersion(UUID orgId, ProcedureTemplate[] templateOut) {
        ProcedureTemplate template = new ProcedureTemplate();
        template.setOrgId(orgId);
        template.setName("Walk " + UUID.randomUUID());
        templates.saveAndFlush(template);
        templateOut[0] = template;

        ProcedureTemplateVersion version = new ProcedureTemplateVersion();
        version.setTemplateId(template.getId());
        version.setVersionNo(1);
        version.setDefinition("{\"schema\":2,\"items\":[]}", "a".repeat(64));
        return versions.saveAndFlush(version);
    }

    private GlobalProcedureTemplateVersion persistedGlobalVersion() {
        GlobalProcedureTemplate template = new GlobalProcedureTemplate();
        template.setName("Shared " + UUID.randomUUID());
        globalTemplates.saveAndFlush(template);

        GlobalProcedureTemplateVersion version = new GlobalProcedureTemplateVersion();
        version.setGlobalTemplateId(template.getId());
        version.setVersionNo(1);
        version.setState(VersionState.PUBLISHED);
        version.setDefinitionJson("{\"schema\":2,\"items\":[]}");
        version.setDefinitionHash("a".repeat(64));
        return globalVersions.saveAndFlush(version);
    }

    @Test
    void anEventReachesKafkaOnlyAfterItsTransactionCommits() {
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        UUID org = actAsNewOrg();
        ProcedureTemplate[] templateOut = new ProcedureTemplate[1];

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ProcedureTemplateVersion version = persistedTemplateAndVersion(org, templateOut);
            events.publish(templateOut[0], version, ProcedureTemplateEvent.EventType.PUBLISHED);
        });

        verify(kafkaTemplate, times(1)).send(any(), any(), any());
    }

    @Test
    void anEventNeverReachesKafkaIfItsTransactionRollsBack() {
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        UUID org = actAsNewOrg();
        ProcedureTemplate[] templateOut = new ProcedureTemplate[1];

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ProcedureTemplateVersion version = persistedTemplateAndVersion(org, templateOut);
            events.publish(templateOut[0], version, ProcedureTemplateEvent.EventType.PUBLISHED);
            throw new IllegalStateException("forced rollback");
        })).isInstanceOf(IllegalStateException.class);

        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    @Test
    void aGlobalEventReachesKafkaOnlyAfterItsTransactionCommits() {
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        actAsNewOrg();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            GlobalProcedureTemplateVersion version = persistedGlobalVersion();
            globalEvents.publish(version, GlobalTemplateEvent.EventType.PUBLISHED);
        });

        verify(kafkaTemplate, times(1)).send(any(), any(), any());
    }

    @Test
    void aGlobalEventNeverReachesKafkaIfItsTransactionRollsBack() {
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        actAsNewOrg();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            GlobalProcedureTemplateVersion version = persistedGlobalVersion();
            globalEvents.publish(version, GlobalTemplateEvent.EventType.PUBLISHED);
            throw new IllegalStateException("forced rollback");
        })).isInstanceOf(IllegalStateException.class);

        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    @Test
    void publishingTwiceInOneTransactionReachesKafkaTwiceAfterTheSingleCommit() {
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        UUID org = actAsNewOrg();
        ProcedureTemplate[] templateOut = new ProcedureTemplate[1];

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ProcedureTemplateVersion version = persistedTemplateAndVersion(org, templateOut);
            events.publish(templateOut[0], version, ProcedureTemplateEvent.EventType.PUBLISHED);
            events.publish(templateOut[0], version, ProcedureTemplateEvent.EventType.ARCHIVED);
        });

        verify(kafkaTemplate, times(2)).send(any(), any(), any());
    }

    @Test
    void anEventRaisedOutsideAnyTransactionStillReachesKafka() {
        // Not every caller necessarily wraps publish() in an explicit
        // transaction, though every real service method here does via
        // @Transactional. With no transaction synchronization active,
        // Spring's transactional event listener falls back to firing
        // immediately, which is the right behaviour for a call with
        // nothing to wait for.
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        UUID org = actAsNewOrg();
        ProcedureTemplate[] templateOut = new ProcedureTemplate[1];
        ProcedureTemplateVersion version = persistedTemplateAndVersion(org, templateOut);

        events.publish(templateOut[0], version, ProcedureTemplateEvent.EventType.PUBLISHED);

        verify(kafkaTemplate, times(1)).send(any(), any(), any());
    }
}
