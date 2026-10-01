package com.taxonomy.backup;

import com.taxonomy.security.backup.IdentityBackupContributor;
import com.taxonomy.security.model.AppRole;
import com.taxonomy.security.model.AppUser;
import com.taxonomy.security.persistence.PrincipalSchemaMigration;
import com.taxonomy.security.service.PrincipalIdentityService;
import org.hibernate.cfg.Configuration;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class IdentityExportIT {
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");

    @Test void scopedExportIncludesOnlyReferencedIdentitiesWithoutAdministrativeStateOrHashes() throws Exception {
        try (var fixture = new Fixture()) {
            var output = new CurrentStateExportIT.Contents();
            new IdentityBackupContributor(fixture.database, (snapshot, checkpoint) -> Set.of(fixture.external, fixture.retired))
                    .write(snapshot(fixture.alice, BackupProfile.CURRENT_STATE, SecretsSelection.EXCLUDE), output);
            assertThat(output.text()).contains(fixture.alice.value().toString(), fixture.external.value().toString(),
                            fixture.retired.value().toString(), "external-subject", "https://issuer.example", "EXPLICIT_MAPPING")
                    .doesNotContain("BOB-PRIVATE", "PENDING-ACCOUNT", "ALICE-HASH", "BOB-HASH", "HISTORICAL-AUDIT", "ROLE_ADMIN", "INCLUDE_SECRETS");
            assertThat(output.entries.keySet()).noneMatch(path -> path.startsWith("protected/"));
            assertThat(fixture.jdbc.queryForObject("select count(*) from app_principal", Integer.class)).isEqualTo(4);
        }
    }

    @Test void aMissingReferencedPrincipalFailsBeforeAnyDatasetIsWritten() throws Exception {
        try (var fixture = new Fixture()) {
            var output = new CurrentStateExportIT.Contents();
            assertThatThrownBy(() -> new IdentityBackupContributor(fixture.database,
                    (snapshot, checkpoint) -> Set.of(PrincipalId.create())).write(
                    snapshot(fixture.alice, BackupProfile.CURRENT_STATE, SecretsSelection.EXCLUDE), output))
                    .isInstanceOf(java.io.IOException.class);
            assertThat(output.entries).isEmpty();
        }
    }

    @Test void installationCurrentPreservesAccountsAndSourceGrantsButNoPastAuditOrPasswords() throws Exception {
        try (var fixture = new Fixture()) {
            var output = new CurrentStateExportIT.Contents();
            fixture.installationContributor().write(snapshot(fixture.alice, BackupProfile.INSTALLATION_CURRENT, SecretsSelection.EXCLUDE), output);
            assertThat(output.text()).contains("BOB-PRIVATE", "PENDING-ACCOUNT", "ROLE_ADMIN", "INCLUDE_SECRETS",
                            "PENDING_LOCAL_ACCOUNT", "EXPLICIT_MAPPING", fixture.pending.value().toString())
                    .doesNotContain("ALICE-HASH", "BOB-HASH", "PENDING-HASH", "HISTORICAL-AUDIT");
            // Reading a backup cannot register a not-yet-logged-in account or create a login binding.
            assertThat(fixture.jdbc.queryForObject("select count(*) from app_principal where principal_id=?", Integer.class,
                    fixture.pending.value().toString())).isZero();
            assertThat(fixture.jdbc.queryForObject("select count(*) from principal_binding where principal_id=?", Integer.class,
                    fixture.pending.value().toString())).isZero();
        }
    }

    @Test void fullInstallationSeparatesExplicitlySelectedPasswordHashesIntoProtectedEntries() throws Exception {
        try (var fixture = new Fixture()) {
            var excluded = new CurrentStateExportIT.Contents();
            fixture.installationContributor().write(snapshot(fixture.alice, BackupProfile.INSTALLATION_FULL, SecretsSelection.EXCLUDE), excluded);
            assertThat(excluded.text()).contains("HISTORICAL-AUDIT").doesNotContain("ALICE-HASH", "BOB-HASH", "PENDING-HASH");
            assertThat(excluded.entries.keySet()).noneMatch(path -> path.startsWith("protected/"));
            var included = new CurrentStateExportIT.Contents();
            fixture.installationContributor().write(snapshot(fixture.alice, BackupProfile.INSTALLATION_FULL, SecretsSelection.INCLUDE_ENCRYPTED), included);
            assertThat(new String(included.entries.get("protected/identities/password-hashes.ndjson"), StandardCharsets.UTF_8))
                    .contains("ALICE-HASH", "BOB-HASH", "PENDING-HASH");
            included.entries.forEach((path, content) -> {
                if (!path.startsWith("protected/")) assertThat(new String(content, StandardCharsets.UTF_8))
                        .doesNotContain("ALICE-HASH", "BOB-HASH", "PENDING-HASH");
            });
            assertThat(fixture.jdbc.queryForList("select password_hash from app_user", String.class))
                    .containsExactlyInAnyOrder("ALICE-HASH", "BOB-HASH", "PENDING-HASH");
        }
    }

    @Test void missingAuditActorsCannotBeSilentlyDroppedFromFullInstallationCapture() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.jdbc.update("update principal_access_audit set actor_principal=?", PrincipalId.create().value().toString());
            var output = new CurrentStateExportIT.Contents();
            assertThatThrownBy(() -> fixture.installationContributor().write(
                    snapshot(fixture.alice, BackupProfile.INSTALLATION_FULL, SecretsSelection.EXCLUDE), output))
                    .isInstanceOf(java.io.IOException.class);
            assertThat(output.entries).isEmpty();
        }
    }

    @Test void historicalSelectionCarriesIdentityMappingMetadataWithoutCurrentLoginRoles() throws Exception {
        try (var fixture = new Fixture()) {
            var output = new CurrentStateExportIT.Contents();
            new IdentityBackupContributor(fixture.database, (snapshot, checkpoint) -> Set.of(fixture.retired))
                    .write(snapshot(fixture.alice, BackupProfile.SELECTED_VERSION, SecretsSelection.EXCLUDE), output);
            assertThat(output.text()).contains(fixture.retired.value().toString(), "retired-owner", "EXPLICIT_MAPPING")
                    .doesNotContain("ROLE_ADMIN", "BOB-PRIVATE", "ALICE-HASH", "HISTORICAL-AUDIT", "external-subject");
        }
    }

    private static SnapshotContext snapshot(PrincipalId actor, BackupProfile profile, SecretsSelection secrets) {
        var key = new BackupRepositoryKey("repo", "workspace");
        BackupScope scope = profile.isInstallation() ? new BackupScope.Installation() : new BackupScope.Workspace("repo", "workspace");
        BackupTime time = profile.includesHistory() ? new BackupTime.History() : profile == BackupProfile.SELECTED_VERSION
                ? new BackupTime.SelectedVersion(Map.of(key, "a".repeat(40))) : new BackupTime.Current();
        var request = new BackupRequest(profile, scope, time, profile.includesHistory() ? GitRepresentation.BUNDLE : GitRepresentation.NONE, secrets);
        var authorized = new AuthorizedBackupRequest(request, actor, "identity-fixture", NOW, EnumSet.allOf(BackupCapability.class));
        return new SnapshotContext(BackupId.create(), authorized, NOW, NOW, 1,
                profile.isInstallation() ? Map.of() : Map.of(key, new SnapshotContext.RepositoryState(Map.of(), null, Map.of(), Set.of())),
                Map.of(new BackupComponentId("application"), 1));
    }

    private static final class Fixture implements AutoCloseable {
        final JDBCDataSource database = new JDBCDataSource();
        final org.hibernate.SessionFactory factory;
        final JdbcTemplate jdbc;
        final PrincipalId alice, bob, external, retired = PrincipalId.create(), pending;
        Fixture() {
            database.setUrl("jdbc:hsqldb:mem:identity-backup-" + UUID.randomUUID()); database.setUser("sa");
            factory = new Configuration().addAnnotatedClass(AppUser.class).addAnnotatedClass(AppRole.class)
                    .setProperty("hibernate.connection.url", database.getUrl()).setProperty("hibernate.connection.username", "sa")
                    .setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
            jdbc = new JdbcTemplate(database);
            alice = account("alice", "Alice", "ALICE-HASH", "ROLE_ADMIN");
            bob = account("bob", "BOB-PRIVATE", "BOB-HASH", "ROLE_USER");
            PrincipalSchemaMigration.migrate(database);
            external = new PrincipalIdentityService(database).oidc("https://issuer.example", "external-subject").id();
            jdbc.update("insert into app_principal values (?,?,0)", retired.value().toString(), "retired-owner");
            jdbc.update("insert into backup_capability_grant values (?,?)", alice.value().toString(), "INCLUDE_SECRETS");
            jdbc.update("insert into backup_version_grant values (?,?,?,?)", bob.value().toString(), "repo", "W:workspace", "a".repeat(40));
            jdbc.update("insert into principal_access_audit values (?,?,?,?,?,?)", UUID.randomUUID().toString(), alice.value().toString(),
                    bob.value().toString(), "GRANT_VERSION", "HISTORICAL-AUDIT", NOW.toEpochMilli());
            pending = account("pending", "PENDING-ACCOUNT", "PENDING-HASH", null);
        }
        private PrincipalId account(String username, String display, String hash, String roleName) {
            try (var session = factory.openSession()) {
                var tx = session.beginTransaction(); var user = new AppUser();
                user.setUsername(username); user.setDisplayName(display); user.setEmail(username + "@example.test"); user.setPasswordHash(hash);
                if (roleName != null) { var role = new AppRole(roleName); session.persist(role); user.getRoles().add(role); }
                session.persist(user); tx.commit();
                return new PrincipalId(UUID.fromString(user.getPrincipalId()));
            }
        }
        IdentityBackupContributor installationContributor() {
            return new IdentityBackupContributor(database, (snapshot, checkpoint) -> {
                throw new AssertionError("Installation inventory must enumerate source identities directly");
            });
        }
        @Override public void close() { factory.close(); }
    }
}
