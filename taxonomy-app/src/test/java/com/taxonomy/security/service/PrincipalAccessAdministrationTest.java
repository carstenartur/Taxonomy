package com.taxonomy.security.service;

import com.taxonomy.backup.*;
import com.taxonomy.security.model.PrincipalUserDetails;
import com.taxonomy.security.persistence.PrincipalSchemaMigration;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.access.AccessDeniedException;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class PrincipalAccessAdministrationTest {
    JdbcTemplate jdbc;
    PrincipalIdentityService identities;
    PrincipalAccessAdministration administration;
    PrincipalId target;
    Authentication administrator;

    @BeforeEach void fixture() {
        var database = new JDBCDataSource();
        database.setUrl("jdbc:hsqldb:mem:grants-" + UUID.randomUUID()); database.setUser("sa");
        jdbc = new JdbcTemplate(database);
        jdbc.execute("create table app_user(id bigint primary key, username varchar(255), enabled boolean)");
        jdbc.execute("create table app_role(id bigint primary key, name varchar(255))");
        jdbc.execute("create table user_roles(user_id bigint, role_id bigint)");
        jdbc.update("insert into app_user values (1, 'operator', true), (2, 'reader', true)");
        jdbc.update("insert into app_role values (1, 'ROLE_ADMIN')");
        jdbc.update("insert into user_roles values (1, 1)");
        PrincipalSchemaMigration.migrate(database);
        identities = new PrincipalIdentityService(database);
        administration = new PrincipalAccessAdministration(database, identities);
        target = identities.local(2L).id();
        var operator = identities.local(1L);
        var roles = List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
        var details = new PrincipalUserDetails(operator.id(), operator.scopeKey(), "unused", true, roles);
        administrator = UsernamePasswordAuthenticationToken.authenticated(details, null, roles);
    }

    @Test void separatelyGrantsHistoryWithoutGrantingRestoreAndAuditsTheStableActor() {
        administration.setCapability(administrator, target, BackupCapability.EXPORT_HISTORY, true);
        assertThat(identities.hasCapability(target, BackupCapability.EXPORT_HISTORY)).isTrue();
        assertThat(identities.hasCapability(target, BackupCapability.RESTORE_INSTALLATION)).isFalse();
        assertThat(jdbc.queryForObject("select actor_principal from principal_access_audit", String.class))
                .isEqualTo(identities.require(administrator).value().toString());
        administration.setCapability(administrator, target, BackupCapability.EXPORT_HISTORY, false);
        assertThat(identities.hasCapability(target, BackupCapability.EXPORT_HISTORY)).isFalse();
    }

    @Test void revokedAdministratorCannotUseItsOldSessionToGrantRights() {
        jdbc.update("delete from user_roles where user_id=1");
        assertThatThrownBy(() -> administration.setCapability(administrator, target, BackupCapability.EXPORT_HISTORY, true))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(identities.hasCapability(target, BackupCapability.EXPORT_HISTORY)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from principal_access_audit", Integer.class)).isZero();
    }

    @Test void grantsOnlyTheExplicitVersionAndSupportsImmediateRevocation() {
        var repository = new BackupRepositoryKey("repo", "private");
        administration.setVersionAccess(administrator, target, repository, "a".repeat(40), true);
        assertThat(identities.hasVersionAccess(target, repository, "a".repeat(40))).isTrue();
        assertThat(identities.hasVersionAccess(target, repository, "b".repeat(40))).isFalse();
        administration.setVersionAccess(administrator, target, repository, "a".repeat(40), false);
        assertThat(identities.hasVersionAccess(target, repository, "a".repeat(40))).isFalse();
    }
}
