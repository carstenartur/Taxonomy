package com.taxonomy.security.persistence;

import com.taxonomy.backup.PrincipalId;
import com.taxonomy.security.service.PrincipalIdentityService;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class LegacyPrincipalScopeMigrationTest {
    @ParameterizedTest
    @CsvSource({
            "user_workspace,username", "repository_membership,username",
            "workspace_projection,username", "sync_state,username", "context_history_record,username",
            "analysis_working_draft,username", "relation_proposal,owner_username",
            "relation_hypothesis,owner_username", "taxonomy_relation,owner_username",
            "arch_project,owner_username", "project_requirement,owner_username",
            "solution_definition,owner_username", "webdav_application_credential,username",
            "system_repository,owner_id"
    })
    void orphanedLegacyOwnershipNeverBecomesANewLocalAccountsAuthority(String table, String column) {
        var database = new JDBCDataSource();
        database.setUrl("jdbc:hsqldb:mem:orphan-" + UUID.randomUUID());
        database.setUser("sa");
        var jdbc = new JdbcTemplate(database);
        jdbc.execute("create table app_user(id bigint primary key, username varchar(255), enabled boolean)");
        jdbc.execute("create table " + table + " (" + column + " varchar(255)" +
                (table.equals("system_repository") ? ", owner_type varchar(20)" : "") + ")");
        jdbc.update("insert into " + table + " (" + column + ") values ('orphan')");

        PrincipalSchemaMigration.migrate(database);
        var identities = new PrincipalIdentityService(database);
        jdbc.update("insert into app_user values (1, 'orphan', true, ?)", UUID.randomUUID().toString());

        assertThatThrownBy(() -> identities.local(1)).isInstanceOf(BadCredentialsException.class);
        var historical = new PrincipalId(UUID.fromString(jdbc.queryForObject(
                "select principal_id from app_principal where scope_key='orphan'", String.class)));
        assertThat(identities.isEnabled(historical)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from principal_binding where principal_id=?", Integer.class,
                historical.value().toString())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from backup_capability_grant where principal_id=?", Integer.class,
                historical.value().toString())).isZero();
        PrincipalSchemaMigration.migrate(database);
        assertThat(jdbc.queryForObject("select count(*) from app_principal where scope_key='orphan'", Integer.class)).isEqualTo(1);
    }
}
