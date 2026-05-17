package com.wallet.integration;

import com.wallet.integration.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #3: Flyway owns the schema. Guards that the baseline (V1) plus the
 * role migration (V2) are the applied, successful migration state and that
 * the schema they build is the one Hibernate validates against (the whole
 * integration suite booting at all already proves validate passes).
 */
class FlywayMigrationIntegrationTest extends IntegrationTestBase {

    @Test
    void flywayHistoryShowsBaselineAndRoleMigrationApplied() {
        List<Map<String, Object>> history = jdbcTemplate.queryForList(
                "SELECT version, description, success FROM flyway_schema_history " +
                "WHERE version IS NOT NULL ORDER BY installed_rank");

        assertThat(history).hasSize(2);
        assertThat(history.get(0)).containsEntry("version", "1").containsEntry("success", true);
        assertThat(history.get(1)).containsEntry("version", "2").containsEntry("success", true);
    }

    @Test
    void roleColumnFromV2ExistsWithUserDefault() {
        Map<String, Object> col = jdbcTemplate.queryForMap(
                "SELECT data_type, column_default, is_nullable " +
                "FROM information_schema.columns " +
                "WHERE table_name = 'users' AND column_name = 'role'");

        assertThat(col).containsEntry("data_type", "character varying");
        assertThat(col).containsEntry("is_nullable", "NO");
        assertThat(col.get("column_default").toString()).contains("'USER'");
    }

    @Test
    void hibernateRunsInValidateModeNotDdlAuto() {
        // If ddl-auto were still 'update' Hibernate would have silently
        // created any missing object; the baseline-vs-entity contract that
        // issue #3 establishes only holds under 'validate'. The context
        // started, so validate already succeeded against the Flyway schema;
        // this asserts the table set is exactly the migration's, with no
        // Hibernate-added drift.
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables " +
                "WHERE table_schema = 'public' AND table_type = 'BASE TABLE' " +
                "ORDER BY table_name", String.class);

        assertThat(tables).containsExactlyInAnyOrder(
                "audit_logs", "ledger_entries", "transactions", "users",
                "wallets", "flyway_schema_history");
    }
}
