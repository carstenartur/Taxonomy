package com.taxonomy.security.service;

import com.taxonomy.backup.PrincipalId;
import com.taxonomy.security.controller.LegacyOwnerReservationController;
import com.taxonomy.security.keycloak.PrincipalJwtAuthenticationToken;
import com.taxonomy.security.keycloak.PrincipalOidcUser;
import com.taxonomy.security.model.PrincipalUserDetails;
import com.taxonomy.security.persistence.PrincipalSchemaMigration;

import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VerifiedLegacyOwnerMigrationTest {
    JDBCDataSource database;
    JdbcTemplate jdbc;
    PrincipalIdentityService identities;
    VerifiedLegacyOwnerMigration migration;
    Authentication admin;

    @BeforeEach void fixture() {
        database = new JDBCDataSource();
        database.setUrl("jdbc:hsqldb:mem:verified-legacy-" + UUID.randomUUID());
        database.setUser("sa");
        jdbc = new JdbcTemplate(database);
        jdbc.execute("create table app_user (id bigint primary key, username varchar(255), enabled boolean)");
        jdbc.execute("create table app_role (id bigint primary key, name varchar(255))");
        jdbc.execute("create table user_roles (user_id bigint, role_id bigint)");
        jdbc.execute("create table user_workspace (username varchar(255), workspace_id varchar(255))");
        jdbc.update("insert into app_user values (1, 'operator', true)");
        jdbc.update("insert into app_role values (1, 'ROLE_ADMIN')");
        jdbc.update("insert into user_roles values (1, 1)");
        jdbc.update("insert into user_workspace values ('alice', 'private-a'), ('bob', 'private-b')");
        PrincipalSchemaMigration.migrate(database);
        identities = new PrincipalIdentityService(database);
        migration = new VerifiedLegacyOwnerMigration(database, identities);
        var operator = identities.local(1);
        var roles = List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
        var details = new PrincipalUserDetails(operator.id(), operator.scopeKey(), "unused", true, roles);
        admin = UsernamePasswordAuthenticationToken.authenticated(details, null, roles);
    }

    private static VerifiedLegacyOwnerMigration.VerifiedMapping verified(
            VerifiedLegacyOwnerMigration.UnresolvedOwner owner, String subject) {
        return new VerifiedLegacyOwnerMigration.VerifiedMapping(
                owner.principal(), owner.legacyScope(), "https://idp.example", subject, "approved:owner:" + subject);
    }

    @Test void previewIsBoundedAndNeverInfersUsernameAsSubject() {
        var preview = migration.preview(admin, 1);
        assertThat(preview.total()).isEqualTo(2);
        assertThat(preview.owners()).hasSize(1);
        assertThat(preview.truncated()).isTrue();
        assertThat(preview.fingerprint()).hasSize(64);
        assertThat(jdbc.queryForObject("select count(*) from principal_binding where binding_kind='OIDC'",
                Integer.class)).isZero();
        var second = migration.preview(admin, 256);
        assertThat(second.total()).isEqualTo(2);
        assertThat(second.truncated()).isFalse();
        assertThat(second.fingerprint()).isEqualTo(preview.fingerprint());
        assertThat(second.owners()).extracting(VerifiedLegacyOwnerMigration.UnresolvedOwner::legacyScope)
                .containsExactly("alice", "bob");
    }

    @Test void requiresFullExactApprovedPlanAndCommitsBothLinksWithAuditAtomically() {
        var preview = migration.preview(admin, 256);
        var alice = preview.owners().get(0);
        var bob = preview.owners().get(1);
        assertThatThrownBy(() -> migration.linkVerified(admin, preview.fingerprint(), List.of(verified(alice, "sub-a"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> migration.linkVerified(admin, "0".repeat(64),
                List.of(verified(alice, "sub-a"), verified(bob, "sub-b"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> migration.linkVerified(admin, preview.fingerprint(),
                List.of(verified(alice, "same"), verified(bob, "same"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("select count(*) from principal_binding where binding_kind='OIDC'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from principal_access_audit",
                Integer.class)).isZero();

        migration.linkVerified(admin, preview.fingerprint(),
                List.of(verified(bob, "sub-b"), verified(alice, "sub-a")));
        assertThat(migration.preview(admin, 256).total()).isZero();
        assertThat(identities.oidc("https://idp.example", "sub-a").id()).isEqualTo(alice.principal());
        assertThat(identities.oidc("https://idp.example", "sub-b").scopeKey()).isEqualTo("bob");
        assertThat(jdbc.queryForObject("select count(*) from principal_access_audit",
                Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from backup_capability_grant where principal_id=?",
                Integer.class, alice.principal().value().toString())).isZero();
        var auditDetail = jdbc.queryForObject("select detail from principal_access_audit where target_principal=?",
                String.class, alice.principal().value().toString());
        assertThat(auditDetail).startsWith("approval-sha256:").doesNotContain("approved:owner:");
        assertThatThrownBy(() -> migration.linkVerified(admin, preview.fingerprint(),
                List.of(verified(alice, "sub-a"), verified(bob, "sub-b"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void existingOidcSubjectCollisionRollsBackAllMappings() {
        var preview = migration.preview(admin, 256);
        identities.oidc("https://idp.example", "taken");
        assertThatThrownBy(() -> migration.linkVerified(admin, preview.fingerprint(), List.of(
                verified(preview.owners().get(0), "safe"),
                verified(preview.owners().get(1), "taken"))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(migration.preview(admin, 256).total()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from principal_access_audit", Integer.class)).isZero();
    }

    @Test void unlinkRevokesTheExactSubjectWithoutGivingAwayItsHistoricalOwnership() {
        var preview = migration.preview(admin, 256);
        migration.linkVerified(admin, preview.fingerprint(), List.of(
                verified(preview.owners().get(0), "sub-a"),
                verified(preview.owners().get(1), "sub-b")));
        PrincipalId target = preview.owners().get(0).principal();
        assertThatThrownBy(() -> migration.unlinkVerified(admin, target, "https://idp.example", "wrong", "approved:revoke"))
                .isInstanceOf(AccessDeniedException.class);
        migration.unlinkVerified(admin, target, "https://idp.example", "sub-a", "approved:revoke");
        assertThat(identities.isEnabled(target)).isFalse();
        LegacyOwnerMigrationReadiness.requireReady(database);
        assertThatThrownBy(() -> identities.oidc("https://idp.example", "sub-a"))
                .isInstanceOf(org.springframework.security.authentication.DisabledException.class);
        assertThat(jdbc.queryForObject("select count(*) from principal_access_audit", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from principal_binding where principal_id=?",
                Integer.class, target.value().toString())).isEqualTo(1);
        // A disabled row without its committed removal audit is still unresolved.
        jdbc.update("delete from principal_access_audit where decision_kind='UNLINK_LEGACY_OIDC'");
        assertThat(LegacyOwnerMigrationReadiness.inspect(database).ready()).isFalse();
    }

    @Test void genericOidcPrincipalIsNotAnEligibleHistoricalUnlinkTarget() {
        var newlyRegistered = identities.oidc("https://idp.example", "unrelated");
        assertThatThrownBy(() -> migration.unlinkVerified(admin, newlyRegistered.id(),
                "https://idp.example", "unrelated", "approved:revoke"))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(identities.isEnabled(newlyRegistered.id())).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from principal_access_audit",
                Integer.class)).isZero();
    }

    @Test void removedAdminRoleOrUnknownActorCannotPreviewOrLink() {
        var preview = migration.preview(admin, 256);
        jdbc.update("delete from user_roles where user_id=1");
        assertThatThrownBy(() -> migration.preview(admin, 1)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> migration.linkVerified(admin, preview.fingerprint(),
                List.of(verified(preview.owners().get(0), "a"), verified(preview.owners().get(1), "b"))))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(jdbc.queryForObject("select count(*) from principal_access_audit", Integer.class)).isZero();
    }

    @Test void restartKeepsVerifiedMappingAndDisabledTombstone() {
        var before = migration.preview(admin, 256);
        migration.linkVerified(admin, before.fingerprint(), List.of(
                verified(before.owners().get(0), "a"), verified(before.owners().get(1), "b")));
        migration = new VerifiedLegacyOwnerMigration(database, new PrincipalIdentityService(database));
        assertThat(migration.preview(admin, 256).total()).isZero();
        assertThat(identities.oidc("https://idp.example", "a").id())
                .isEqualTo(before.owners().get(0).principal());
    }

    @Test void invalidPreviewLimitsAndMissingApprovedPlanFailClosed() {
        assertThatThrownBy(() -> migration.preview(admin, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> migration.preview(admin, 257)).isInstanceOf(IllegalArgumentException.class);
        var before = migration.preview(admin, 256);
        assertThatThrownBy(() -> migration.linkVerified(admin, before.fingerprint(), null))
                .isInstanceOf(NullPointerException.class);

        var alice = before.owners().get(0);
        var bob = before.owners().get(1);
        var missingEvidence = new VerifiedLegacyOwnerMigration.VerifiedMapping(
                alice.principal(), alice.legacyScope(), "https://idp.example", "alice-subject", " ");
        assertThatThrownBy(() -> migration.linkVerified(admin, before.fingerprint(),
                List.of(missingEvidence, verified(bob, "bob-subject"))))
                .isInstanceOf(IllegalArgumentException.class);
        var missingIssuer = new VerifiedLegacyOwnerMigration.VerifiedMapping(
                alice.principal(), alice.legacyScope(), "", "alice-subject", "verified-approval");
        assertThatThrownBy(() -> migration.linkVerified(admin, before.fingerprint(),
                List.of(missingIssuer, verified(bob, "bob-subject"))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(migration.preview(admin, 256).fingerprint()).isEqualTo(before.fingerprint());
        assertThat(jdbc.queryForObject("select count(*) from principal_binding where binding_kind='OIDC'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from principal_access_audit",
                Integer.class)).isZero();
    }

    @Test void duplicateReservedIdentityAndWrongHistoricalScopeRollBackWholePlan() {
        var before = migration.preview(admin, 256);
        var alice = before.owners().get(0);
        var bob = before.owners().get(1);
        // Distinct subjects cannot legitimize a repeated historical principal.
        assertThatThrownBy(() -> migration.linkVerified(admin, before.fingerprint(),
                List.of(verified(alice, "sub-1"), verified(alice, "sub-2"))))
                .isInstanceOf(IllegalArgumentException.class);
        var wrongScope = new VerifiedLegacyOwnerMigration.VerifiedMapping(
                alice.principal(), "not-alice", "https://idp.example", "sub-1", "approved:scope-check");
        assertThatThrownBy(() -> migration.linkVerified(admin, before.fingerprint(),
                List.of(wrongScope, verified(bob, "sub-2"))))
                .isInstanceOf(IllegalStateException.class);
        var inventedPrincipal = new VerifiedLegacyOwnerMigration.VerifiedMapping(
                new PrincipalId(UUID.randomUUID()), alice.legacyScope(),
                "https://idp.example", "sub-1", "approved:invented-principal");
        assertThatThrownBy(() -> migration.linkVerified(admin, before.fingerprint(),
                List.of(inventedPrincipal, verified(bob, "sub-2"))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(migration.preview(admin, 256).total()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from principal_binding where binding_kind='OIDC'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from principal_access_audit",
                Integer.class)).isZero();
    }

    @Test void analysisAndBranchRecoveryOwnersMustBeAccountedForBeforeAStableRestart() {
        for (String table : List.of("analysis_continuation", "analysis_cluster_run", "workspace_branch_sync")) {
            jdbc.execute("create table " + table + " (username varchar(160))");
            jdbc.update("insert into " + table + " values (?)", "fixture-" + table);
        }
        var readiness = LegacyOwnerMigrationReadiness.inspect(database);
        assertThat(readiness.observedScopes()).isEqualTo(5);
        assertThat(readiness.missingReservations()).isEqualTo(3);
        assertThatThrownBy(() -> LegacyOwnerMigrationReadiness.requireReady(database))
                .isInstanceOf(IllegalStateException.class);

        assertThat(migration.reserveMissing(admin)).isEqualTo(3);
        assertThat(migration.reserveMissing(admin)).isZero();
        var reserved = LegacyOwnerMigrationReadiness.inspect(database);
        assertThat(reserved.missingReservations()).isZero();
        assertThat(reserved.unresolvedReservations()).isEqualTo(5);
        assertThat(reserved.ready()).isFalse();
        assertThat(migration.preview(admin, 256).owners()).extracting(VerifiedLegacyOwnerMigration.UnresolvedOwner::legacyScope)
                .contains("fixture-analysis_cluster_run", "fixture-analysis_continuation", "fixture-workspace_branch_sync");
        assertThat(jdbc.queryForObject("select count(*) from principal_binding where binding_kind='OIDC'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from principal_access_audit where decision_kind='RESERVE_LEGACY_OWNER' "
                + "and actor_principal=? and target_principal in (select principal_id from app_principal where enabled=0)",
                Integer.class, identities.requireCurrent(admin).value().toString())).isEqualTo(3);
    }

    @Test void theOperatorApiReportsDriftAndCreatesOnlyUnboundReservations() throws Exception {
        var mvc = MockMvcBuilders
                .standaloneSetup(new LegacyOwnerReservationController(migration)).build();
        var route = "/api/admin/users/ownership-reservations";
        jdbc.update("insert into user_workspace values ('late-owner', 'late-copy')");
        mvc.perform(get(route).principal(admin))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.inventory.missingReservations").value(1));
        mvc.perform(post(route).principal(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1));
        mvc.perform(get(route).param("limit", "1").principal(admin))
                .andExpect(jsonPath("$.inventory.missingReservations").value(0))
                .andExpect(jsonPath("$.reservations.total").value(3))
                .andExpect(jsonPath("$.reservations.truncated").value(true));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void anExpiredVerifiedLoginCannotInspectOrChangeHistoricalOwnership(boolean browser) {
        String issuer = "https://fixture.invalid/realm";
        String subject = "controlled-operator";
        var principal = identities.oidc(issuer, subject);
        var roles = List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
        Instant issued = Instant.now().minusSeconds(120);
        Instant expires = issued.plusSeconds(60);
        Authentication expired;
        if (browser) {
            var idToken = new OidcIdToken("controlled-id-token", issued, expires, Map.of("iss", issuer, "sub", subject));
            var user = new PrincipalOidcUser(roles, new DefaultOidcUser(roles, idToken), principal);
            expired = new OAuth2AuthenticationToken(user, roles, "fixture");
        } else {
            var jwt = Jwt.withTokenValue("controlled-access-token").header("alg", "RS256").issuer(issuer)
                    .subject(subject).issuedAt(issued).expiresAt(expires).build();
            expired = new PrincipalJwtAuthenticationToken(jwt, roles, principal);
        }
        jdbc.update("insert into user_workspace values ('late-owner', 'late-copy')");
        var currentPlan = migration.preview(admin, 256);
        assertThatThrownBy(() -> migration.reservationStatus(expired, 1)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> migration.reserveMissing(expired)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> migration.preview(expired, 256)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> migration.linkVerified(expired, currentPlan.fingerprint(), List.of(
                verified(currentPlan.owners().get(0), "one"), verified(currentPlan.owners().get(1), "two"))))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> migration.unlinkVerified(expired, currentPlan.owners().getFirst().principal(),
                issuer, subject, "controlled-review")).isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("fresh");
        assertThat(migration.preview(admin, 256)).isEqualTo(currentPlan);
        assertThat(jdbc.queryForObject("select count(*) from principal_access_audit", Integer.class)).isZero();
    }

    @Test void reservationNeedsACurrentAdminAndRollsBackWhenItsAuditCannotCommit() {
        jdbc.update("insert into user_workspace values ('late-owner', 'late-copy')");
        jdbc.update("delete from user_roles where user_id=1");
        assertThatThrownBy(() -> migration.reserveMissing(admin)).isInstanceOf(AccessDeniedException.class);
        jdbc.update("insert into user_roles values (1, 1)");
        jdbc.execute("alter table principal_access_audit add constraint reject_reservation "
                + "check (decision_kind <> 'RESERVE_LEGACY_OWNER')");
        assertThatThrownBy(() -> migration.reserveMissing(admin))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("select count(*) from app_principal where scope_key='late-owner'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from principal_access_audit", Integer.class)).isZero();
    }

    @Test void independentAdministratorsReserveTheSameUnboundOwnerOnlyOnce() throws Exception {
        jdbc.update("insert into user_workspace values ('late-owner', 'late-copy')");
        var second = new VerifiedLegacyOwnerMigration(database, new PrincipalIdentityService(database));
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> { start.await(); return migration.reserveMissing(admin); });
            var other = workers.submit(() -> { start.await(); return second.reserveMissing(admin); });
            start.countDown();
            assertThat(List.of(first.get(15, java.util.concurrent.TimeUnit.SECONDS),
                    other.get(15, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(0, 1);
        }
        assertThat(jdbc.queryForObject("select count(*) from principal_access_audit", Integer.class)).isOne();
        assertThat(migration.preview(admin, 256).total()).isEqualTo(3);
    }

    @Test void postV3LegacyWritesCannotBeHiddenByAnOtherwiseCompleteApprovedPlan() {
        var initial = migration.preview(admin, 256);
        assertThat(LegacyOwnerMigrationReadiness.inspect(database).missingReservations()).isZero();

        // This write happens after the one-time V3 reservation migration.
        jdbc.update("insert into user_workspace values ('late-legacy-owner', 'late-private-workspace')");
        var readiness = LegacyOwnerMigrationReadiness.inspect(database);
        assertThat(readiness.observedScopes()).isEqualTo(3);
        assertThat(readiness.missingReservations()).isEqualTo(1);
        assertThat(readiness.ready()).isFalse();
        assertThatThrownBy(() -> migration.linkVerified(admin, initial.fingerprint(), List.of(
                verified(initial.owners().get(0), "sub-a"),
                verified(initial.owners().get(1), "sub-b"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reserved principal");
        assertThat(migration.preview(admin, 256).total()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from principal_access_audit", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from principal_binding where binding_kind='OIDC'",
                Integer.class)).isZero();
    }

    @Test void blankPostMigrationOwnerFailsClosedWithoutIssuingMappings() {
        var initial = migration.preview(admin, 256);
        jdbc.update("insert into user_workspace values ('  ', 'malformed-owner')");
        assertThatThrownBy(() -> LegacyOwnerMigrationReadiness.inspect(database))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("operator repair");
        assertThatThrownBy(() -> migration.linkVerified(admin, initial.fingerprint(), List.of(
                verified(initial.owners().get(0), "sub-a"),
                verified(initial.owners().get(1), "sub-b"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("operator repair");
        assertThat(jdbc.queryForObject("select count(*) from principal_access_audit", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from principal_binding where binding_kind='OIDC'",
                Integer.class)).isZero();
    }

    @Test void verifiedLinksClearTheReadinessFenceWithoutInferringUsernames() {
        var initial = migration.preview(admin, 256);
        var inventory = LegacyOwnerMigrationReadiness.inspect(database);
        assertThat(inventory.unresolvedReservations()).isEqualTo(2);
        assertThat(inventory.ready()).isFalse();

        migration.linkVerified(admin, initial.fingerprint(), List.of(
                verified(initial.owners().get(0), "opaque-subject-a"),
                verified(initial.owners().get(1), "opaque-subject-b")));
        assertThat(LegacyOwnerMigrationReadiness.inspect(database).ready()).isTrue();

        // Re-using a mapped historical name as a newly created unverified OIDC identity
        // must not be accepted merely because its owner row has a binding.
        var late = identities.oidc("https://idp.example", "different-subject");
        jdbc.update("insert into user_workspace values (?, 'new-private-workspace')", late.scopeKey());
        assertThat(LegacyOwnerMigrationReadiness.inspect(database).ready()).isTrue();
        jdbc.update("update app_principal set enabled=0 where principal_id=?", late.id().value().toString());
        LegacyOwnerMigrationReadiness.requireReady(database);
        assertThatThrownBy(() -> identities.oidc("https://idp.example", "different-subject"))
                .isInstanceOf(org.springframework.security.authentication.DisabledException.class);
        jdbc.update("insert into user_workspace values ('unmapped-after-link', 'other')");
        assertThat(LegacyOwnerMigrationReadiness.inspect(database).ready()).isFalse();
    }

    @Test void removalRequiresExactFieldsAndCannotReuseARevokedSubject() {
        var before = migration.preview(admin, 256);
        var alice = before.owners().get(0);
        var bob = before.owners().get(1);
        migration.linkVerified(admin, before.fingerprint(),
                List.of(verified(alice, "sub-a"), verified(bob, "sub-b")));
        PrincipalId target = alice.principal();
        assertThatThrownBy(() -> migration.unlinkVerified(admin, null,
                "https://idp.example", "sub-a", "approved:revoke"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> migration.unlinkVerified(admin, target,
                "", "sub-a", "approved:revoke"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> migration.unlinkVerified(admin, target,
                "https://idp.example", "", "approved:revoke"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> migration.unlinkVerified(admin, target,
                "https://idp.example", "sub-a", " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(identities.isEnabled(target)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from principal_access_audit",
                Integer.class)).isEqualTo(2);

        migration.unlinkVerified(admin, target, "https://idp.example", "sub-a", "approved:revoke");
        assertThatThrownBy(() -> migration.unlinkVerified(admin, target,
                "https://idp.example", "sub-a", "approved:revoke-again"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> identities.oidc("https://idp.example", "sub-a"))
                .isInstanceOf(org.springframework.security.authentication.DisabledException.class);
        assertThat(jdbc.queryForObject("select count(*) from principal_access_audit",
                Integer.class)).isEqualTo(3);
    }

    @Test void aStaleRoleClaimWithoutCurrentAdminAuthorityCannotReadReservedScopes() {
        var noRole = UsernamePasswordAuthenticationToken.authenticated(
                admin.getPrincipal(), null, List.of());
        assertThatThrownBy(() -> migration.preview(noRole, 256))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> migration.unlinkVerified(noRole, new PrincipalId(UUID.randomUUID()),
                "https://idp.example", "unknown", "approved:revoke"))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(jdbc.queryForObject("select count(*) from principal_access_audit",
                Integer.class)).isZero();
    }
}
