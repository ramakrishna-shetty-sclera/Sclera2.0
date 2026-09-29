package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.support.PostgresIntegrationTest;
import com.sclera.applicationplane.procedure.tenancy.TenantSchemas;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProcedureTemplateService against a real Postgres: migrations, tenant
 * schemas, row locks, optimistic locking and the database constraints behind
 * immutable versions.
 */
class ProcedureTemplateServiceIT extends PostgresIntegrationTest {

    @Autowired
    ProcedureTemplateService service;

    @Autowired
    JdbcTemplate jdbc;

    @Nested
    class Provisioning {

        @Test
        void aNewOrganizationGetsItsOwnMigratedSchema() {
            UUID orgId = actAsNewOrg();
            String schema = TenantSchemas.schemaFor(orgId);

            List<String> tables = jdbc.queryForList(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = ? ORDER BY table_name",
                    String.class, schema);
            String migratedTo = jdbc.queryForObject(
                    "SELECT version FROM \"" + schema + "\".flyway_schema_history "
                            + "WHERE success ORDER BY installed_rank DESC LIMIT 1",
                    String.class);

            assertThat(tables).contains("procedure_template", "procedure_template_version")
                    .doesNotContain("question_template", "template_section", "question");
            assertThat(migratedTo).isEqualTo("3");
        }
    }
}
