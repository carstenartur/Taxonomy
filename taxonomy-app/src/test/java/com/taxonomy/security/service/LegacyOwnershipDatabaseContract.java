package com.taxonomy.security.service;

import com.taxonomy.security.model.PrincipalUserDetails;
import com.taxonomy.security.persistence.PrincipalSchemaMigration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Controlled accounts only. Each invocation receives its own empty database or schema. */
public final class LegacyOwnershipDatabaseContract {
    private LegacyOwnershipDatabaseContract() { }

    public static void verify(DataSource source, String enabledColumnType) {
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("create table app_user (id integer primary key, username varchar(255), enabled "
                + enabledColumnType + " not null)");
        jdbc.execute("create table app_role (id integer primary key, name varchar(255))");
        jdbc.execute("create table user_roles (user_id integer, role_id integer)");
        jdbc.update("insert into app_user values (1, 'fixture-operator', ?)", true);
        jdbc.update("insert into app_role values (1, 'ROLE_ADMIN')");
        jdbc.update("insert into user_roles values (1, 1)");
        PrincipalSchemaMigration.migrate(source);
        jdbc.execute("create table analysis_continuation (username varchar(255))");
        String scope = "controlled-historical-owner";
        jdbc.update("insert into analysis_continuation values (?)", scope);

        var identities = new PrincipalIdentityService(source);
        var operator = identities.local(1);
        var roles = List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
        var details = new PrincipalUserDetails(operator.id(), operator.scopeKey(), "", true, roles);
        var admin = UsernamePasswordAuthenticationToken.authenticated(details, null, roles);
        var migration = new VerifiedLegacyOwnerMigration(source, identities);

        var before = migration.reservationStatus(admin, 1);
        assertThat(before.inventory().missingReservations()).isOne();
        assertThat(before.inventory().ready()).isFalse();
        assertThat(migration.reserveMissing(admin)).isOne();
        assertThat(migration.reserveMissing(admin)).isZero();

        var reserved = migration.reservationStatus(admin, 1);
        assertThat(reserved.inventory().missingReservations()).isZero();
        assertThat(reserved.inventory().ready()).isFalse();
        assertThat(reserved.reservations().owners()).hasSize(1);
        var owner = reserved.reservations().owners().getFirst();
        assertThat(owner.legacyScope()).isEqualTo(scope);
        assertThat(identities.isEnabled(owner.principal())).isFalse();
        String target = owner.principal().value().toString();
        assertThat(jdbc.queryForObject("select count(*) from principal_binding where principal_id=?",
                Integer.class, target)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from backup_capability_grant where principal_id=?",
                Integer.class, target)).isZero();

        String issuer = "https://controlled-issuer.invalid/realm";
        String subject = "opaque-controlled-subject";
        var mapping = new VerifiedLegacyOwnerMigration.VerifiedMapping(
                owner.principal(), scope, issuer, subject, "controlled-fixture-approval");
        migration.linkVerified(admin, reserved.reservations().fingerprint(), List.of(mapping));

        var reopened = new PrincipalIdentityService(source);
        assertThat(reopened.oidc(issuer, subject).id()).isEqualTo(owner.principal());
        assertThat(LegacyOwnerMigrationReadiness.inspect(source).ready()).isTrue();
        new VerifiedLegacyOwnerMigration(source, reopened).unlinkVerified(
                admin, owner.principal(), issuer, subject, "controlled-fixture-revocation");
        assertThatThrownBy(() -> reopened.oidc(issuer, subject)).isInstanceOf(DisabledException.class);
        assertThat(LegacyOwnerMigrationReadiness.inspect(source).ready()).isTrue();
        assertThat(jdbc.queryForList("""
                select decision_kind from principal_access_audit where actor_principal=? and target_principal=?
                """, String.class, operator.id().value().toString(), target)).containsExactlyInAnyOrder(
                "RESERVE_LEGACY_OWNER", "LINK_LEGACY_OIDC", "UNLINK_LEGACY_OIDC");
    }
}
