package com.taxonomy.backup.runtime;

import com.taxonomy.backup.*;

import com.taxonomy.security.persistence.PrincipalSchemaMigration;
import com.taxonomy.security.service.PrincipalIdentityService;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;

/** Real persisted identity migration and restart/concurrency boundaries; no identity mocks. */
class PrincipalMappingIT {
    JDBCDataSource database;
    JdbcTemplate jdbc;
    PrincipalIdentityService identities;

    @BeforeEach void migrateLegacyAccounts() {
        database = new JDBCDataSource();
        database.setUrl("jdbc:hsqldb:mem:principals-" + UUID.randomUUID());
        database.setUser("sa");
        jdbc = new JdbcTemplate(database);
        jdbc.execute("create table app_user (id bigint primary key, username varchar(255) unique, enabled boolean)");
        jdbc.update("insert into app_user values (1, 'alice', true), (2, 'disabled', false)");
        PrincipalSchemaMigration.migrate(database);
        identities = new PrincipalIdentityService(database);
    }

    @Test void legacyLocalBindingSurvivesRestartWithoutRenamingItsScope() {
        var before = identities.local(1L);
        assertThat(before.scopeKey()).isEqualTo("alice");
        PrincipalSchemaMigration.migrate(database);
        var after = new PrincipalIdentityService(database).local(1L);
        assertThat(after.id()).isEqualTo(before.id());
        assertThat(jdbc.queryForObject("select principal_id from app_user where id=1", String.class))
                .isEqualTo(before.id().value().toString());
    }

    @Test void sameSubjectAtAnotherIssuerCannotAcquireExistingLocalOrExternalScope() {
        var local = identities.local(1L);
        var first = identities.oidc("https://idp-a.example/realm", "alice");
        var other = identities.oidc("https://idp-b.example/realm", "alice");
        assertThat(first.id()).isNotEqualTo(local.id()).isNotEqualTo(other.id());
        assertThat(first.scopeKey()).isNotEqualTo("alice").isNotEqualTo(other.scopeKey());
        assertThat(new PrincipalIdentityService(database).oidc("https://idp-a.example/realm", "alice"))
                .isEqualTo(first);
    }

    @Test void localDisableAndBindingRevocationAreObservedByExistingPrincipal() {
        var local = identities.local(1L);
        jdbc.update("update app_user set enabled=false where id=1");
        assertThat(identities.isEnabled(local.id())).isFalse();
        assertThatThrownBy(() -> identities.local(1L)).isInstanceOf(org.springframework.security.authentication.DisabledException.class);
        var external = identities.oidc("https://idp.example", "subject");
        jdbc.update("update principal_binding set enabled=0 where principal_id=?", external.id().value().toString());
        assertThat(identities.isEnabled(external.id())).isFalse();
        assertThatThrownBy(() -> identities.oidc("https://idp.example", "subject"))
                .isInstanceOf(org.springframework.security.authentication.DisabledException.class);
    }

    @Test void simultaneousFirstLoginsShareOneDurableBinding() throws Exception {
        try (var workers = Executors.newFixedThreadPool(4)) {
            var calls = IntStream.range(0, 8).<Callable<PrincipalId>>mapToObj(i -> () ->
                    new PrincipalIdentityService(database).oidc("https://idp.example", "new-user").id()).toList();
            var ids = new java.util.HashSet<PrincipalId>();
            for (var result : workers.invokeAll(calls)) ids.add(result.get());
            assertThat(ids).hasSize(1);
        }
        assertThat(jdbc.queryForObject("select count(*) from principal_binding where binding_kind='OIDC'", Integer.class))
                .isEqualTo(1);
    }

    @Test void recreatedLocalNameDoesNotInheritAnEarlierPrincipal() {
        var original = identities.local(1L);
        jdbc.update("delete from app_user where id=1");
        jdbc.update("insert into app_user(id, username, enabled, principal_id) values (3, 'alice', true, ?)", UUID.randomUUID().toString());
        assertThatThrownBy(() -> identities.local(3L)).isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class);
        assertThat(identities.isEnabled(original.id())).isFalse();
    }

    @Test void revokingDefaultDownloadCapabilitySurvivesTheNextLogin() {
        var local = identities.local(1L);
        assertThat(identities.hasCapability(local.id(), BackupCapability.DOWNLOAD_BACKUP)).isTrue();
        jdbc.update("delete from backup_capability_grant where principal_id=? and capability='DOWNLOAD_BACKUP'", local.id().value().toString());
        assertThat(identities.hasCapability(local.id(), BackupCapability.DOWNLOAD_BACKUP)).isFalse();
        identities.local(1L);
        assertThat(identities.hasCapability(local.id(), BackupCapability.DOWNLOAD_BACKUP)).isFalse();
        assertThat(identities.hasCapability(local.id(), BackupCapability.EXPORT_CURRENT)).isTrue();
        assertThat(identities.hasCapability(local.id(), BackupCapability.EXPORT_HISTORY)).isFalse();
    }

    @Test void restorePreviewUsesPersistedBindingsAndDoesNotTrustSourceDisplayNames() {
        var target = identities.oidc("https://target.example", "subject-1");
        var source = PrincipalId.create();
        var mapping = new com.taxonomy.security.service.PrincipalMappingService(database, identities);
        var matching = new SourceIdentitySet(List.of(new SourceIdentitySet.Identity(source, "renamed", "different@example.org",
                Set.of(new IdentityBinding(IdentityBinding.Kind.OIDC, "https://target.example", "subject-1")))));
        assertThat(mapping.preview(matching).entries().get(source).status()).isEqualTo(PrincipalMappingPlan.Status.MATCHED);
        var wrongProvider = new SourceIdentitySet(List.of(new SourceIdentitySet.Identity(source, "alice", "alice@example.org",
                Set.of(new IdentityBinding(IdentityBinding.Kind.OIDC, "https://unrelated.example", "subject-1")))));
        assertThat(mapping.preview(wrongProvider).entries().get(source).status()).isEqualTo(PrincipalMappingPlan.Status.UNRESOLVED);
        jdbc.update("update app_principal set enabled=0 where principal_id=?", target.id().value().toString());
        assertThat(mapping.preview(matching).entries().get(source).status()).isEqualTo(PrincipalMappingPlan.Status.CONFLICT);
    }
}
