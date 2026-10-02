package com.taxonomy.backup;

import com.taxonomy.security.backup.IdentityBackupContributor;
import com.taxonomy.security.backup.PrincipalScopeBackupSelector;
import com.taxonomy.workspace.backup.WorkspaceBackupContributor;
import com.taxonomy.workspace.model.RepositoryTenantIdentity;
import com.taxonomy.portfolio.backup.PortfolioBackupContributor;
import com.taxonomy.security.model.AppRole;
import com.taxonomy.security.model.AppUser;
import com.taxonomy.security.persistence.PrincipalSchemaMigration;
import com.taxonomy.security.service.PrincipalIdentityService;
import org.hibernate.cfg.Configuration;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class IdentityExportIT {
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");

    @Test void moduleOwnershipReferencesResolveThroughPersistedPrincipalScopesWithoutCapturingUnrelatedUsers() throws Exception {
        try (var f = new Fixture()) {
            ownershipTables(f); var output = new CurrentStateExportIT.Contents();
            var before = f.jdbc.queryForList("select principal_id,scope_key,enabled from app_principal order by principal_id");
            new IdentityBackupContributor(f.database, ownershipSelector(f)).write(snapshot(f.alice, BackupProfile.CURRENT_STATE, SecretsSelection.EXCLUDE), output);
            assertThat(output.text()).contains(f.alice.value().toString(), f.retired.value().toString(), f.external.value().toString(), "EXPLICIT_MAPPING")
                    .doesNotContain(f.bob.value().toString(), "BOB-PRIVATE", "PENDING-ACCOUNT", "ALICE-HASH", "ROLE_ADMIN");
            assertThat(f.jdbc.queryForList("select principal_id,scope_key,enabled from app_principal order by principal_id")).isEqualTo(before);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"system_repository", "repository_membership", "user_workspace", "sync_state", "arch_project", "project_requirement", "solution_definition"})
    void anUnmappedOwnerInAnyCapturedRecordFamilyAbortsBeforeIdentityOutput(String table) throws Exception {
        try (var f = new Fixture()) {
            ownershipTables(f);
            String column = table.equals("system_repository") ? "owner_id" : Set.of("arch_project", "project_requirement", "solution_definition").contains(table) ? "owner_username" : "username";
            f.jdbc.update("update " + table + " set " + column + "='PRIVATE-UNMAPPED'");
            var output = new CurrentStateExportIT.Contents();
            assertThatThrownBy(() -> new IdentityBackupContributor(f.database, ownershipSelector(f)).write(snapshot(f.alice, BackupProfile.CURRENT_STATE, SecretsSelection.EXCLUDE), output))
                    .isInstanceOf(java.io.IOException.class).hasMessageNotContaining("PRIVATE").hasNoCause().hasNoSuppressedExceptions();
            assertThat(output.entries).isEmpty();
            assertThat(f.jdbc.queryForObject("select count(*) from app_principal", Integer.class)).isEqualTo(4);
        }
    }

    @Test void selectedVersionIdentityDiscoveryNeverQueriesPresentDayOwnershipTables() throws Exception {
        try (var f = new Fixture()) { // Deliberately no workspace or portfolio tables.
            var output = new CurrentStateExportIT.Contents();
            new IdentityBackupContributor(f.database, ownershipSelector(f)).write(snapshot(f.alice, BackupProfile.SELECTED_VERSION, SecretsSelection.EXCLUDE), output);
            assertThat(output.text()).contains(f.alice.value().toString()).doesNotContain(f.retired.value().toString(), f.external.value().toString(), f.bob.value().toString());
        }
    }

    @Test void onlyUserRepositoryOwnersArePrincipalReferencesAndSavedBranchesKeepTheirOwnOwners() throws Exception {
        try (var f = new Fixture()) {
            ownershipTables(f); var context = snapshot(f.alice, BackupProfile.CURRENT_STATE, SecretsSelection.EXCLUDE);
            f.jdbc.update("update system_repository set owner_type='SYSTEM',owner_id='NOT-A-PERSON' where repository_id='repo'");
            var workspace = new WorkspaceBackupContributor(f.database, text -> text);
            assertThat(workspace.principalScopes(context, () -> { })).containsExactlyInAnyOrder("alice", "retired-owner");
            f.jdbc.update("update system_repository set owner_type=null,owner_id='legacy-owner' where repository_id='repo'");
            assertThat(workspace.principalScopes(context, () -> { })).containsExactlyInAnyOrder("alice", "retired-owner", "legacy-owner");
            String otherBranch = new RepositoryTenantIdentity("repo", "WORKSPACE:workspace", "feature").scopeKey();
            f.jdbc.update("insert into arch_project values (3,?,'branch-owner')", otherBranch);
            assertThat(new PortfolioBackupContributor(f.database).principalScopes(context, () -> { })).contains("branch-owner").doesNotContain("bob");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"repository", "workspace", "portfolio"})
    void ownershipReadersRejectCaseAliasedForeignRowsEvenIfSqlCollationMatches(String kind) throws Exception {
        try (var f = new Fixture()) {
            ownershipTables(f); var context = snapshot(f.alice, BackupProfile.CURRENT_STATE, SecretsSelection.EXCLUDE);
            if (kind.equals("portfolio")) {
                f.jdbc.execute("alter table arch_project alter column scope_key varchar_ignorecase(512)");
                f.jdbc.update("update arch_project set scope_key=? where id=1", new RepositoryTenantIdentity("Repo", "WORKSPACE:workspace", "draft").scopeKey());
                assertThatThrownBy(() -> new PortfolioBackupContributor(f.database).principalScopes(context, () -> { })).isInstanceOf(java.io.IOException.class);
            } else {
                String table = kind.equals("repository") ? "system_repository" : "user_workspace", column = kind.equals("repository") ? "repository_id" : "source_repository_id";
                f.jdbc.execute("alter table " + table + " alter column " + column + " varchar_ignorecase(255)");
                f.jdbc.update("update " + table + " set " + column + "='Repo' where " + column + "='repo'");
                assertThatThrownBy(() -> new WorkspaceBackupContributor(f.database, text -> text).principalScopes(context, () -> { })).isInstanceOf(java.io.IOException.class);
            }
        }
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(value = BackupProfile.class, names = {"INSTALLATION_CURRENT", "INSTALLATION_FULL"})
    void installationOwnershipDiscoveryIncludesEveryPersistedOwnerWithoutGrantingAuthority(BackupProfile profile) throws Exception {
        try (var f = new Fixture()) {
            ownershipTables(f);
            assertThat(ownershipSelector(f).select(snapshot(f.alice, profile, SecretsSelection.EXCLUDE), () -> { }))
                    .containsExactlyInAnyOrder(f.alice, f.bob, f.external, f.retired);
            assertThat(f.jdbc.queryForObject("select enabled from app_principal where principal_id=?", Integer.class, f.retired.value().toString())).isZero();
        }
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(value = BackupProfile.class, names = {"CURRENT_STATE", "INSTALLATION_CURRENT"})
    void aCaseAliasedSynchronizationChildCannotBorrowTheSelectedParentsWorkspaceIdentity(BackupProfile profile) throws Exception {
        try (var f = new Fixture()) {
            ownershipTables(f); f.jdbc.execute("alter table sync_state alter column workspace_id varchar_ignorecase(255)");
            f.jdbc.execute("alter table user_workspace alter column workspace_id varchar_ignorecase(255)");
            f.jdbc.update("update sync_state set workspace_id='Workspace',username='PRIVATE-ALIASED-OWNER' where id=1");
            assertThat(f.jdbc.queryForObject("select count(*) from sync_state s join user_workspace w on w.workspace_id=s.workspace_id where s.id=1 and w.workspace_id='workspace'", Integer.class)).isEqualTo(1);
            assertThatThrownBy(() -> new WorkspaceBackupContributor(f.database, text -> text).principalScopes(snapshot(f.alice, profile, SecretsSelection.EXCLUDE), () -> { }))
                    .isInstanceOf(java.io.IOException.class).hasMessageNotContaining("PRIVATE").hasNoCause().hasNoSuppressedExceptions();
        }
    }

    private static PrincipalScopeBackupSelector ownershipSelector(Fixture f) {
        var workspace = new WorkspaceBackupContributor(f.database, text -> text); var portfolio = new PortfolioBackupContributor(f.database);
        return new PrincipalScopeBackupSelector(f.database, List.of(workspace::principalScopes, portfolio::principalScopes));
    }
    private static void ownershipTables(Fixture f) {
        f.jdbc.execute("create table system_repository(repository_id varchar(255),owner_type varchar(50),owner_id varchar(255))");
        f.jdbc.execute("create table user_workspace(workspace_id varchar(255),source_repository_id varchar(255),username varchar(255))");
        f.jdbc.execute("create table repository_membership(id integer,repository_id varchar(255),username varchar(255))");
        f.jdbc.execute("create table sync_state(id integer,workspace_id varchar(255),username varchar(255))");
        for (String table : List.of("arch_project", "project_requirement", "solution_definition"))
            f.jdbc.execute("create table " + table + "(id integer,scope_key varchar(512),owner_username varchar(255))");
        f.jdbc.update("insert into system_repository values ('repo','USER','alice'),('foreign','USER','bob')");
        f.jdbc.update("insert into user_workspace values ('workspace','repo','retired-owner'),('private-other','repo','bob')");
        f.jdbc.update("insert into repository_membership values (1,'repo','alice'),(2,'foreign','bob')");
        f.jdbc.update("insert into sync_state values (1,'workspace','retired-owner'),(2,'private-other','bob')");
        String selected = new RepositoryTenantIdentity("repo", "WORKSPACE:workspace", "draft").scopeKey();
        String foreign = new RepositoryTenantIdentity("repo", "WORKSPACE:private-other", "draft").scopeKey();
        String external = f.jdbc.queryForObject("select scope_key from app_principal where principal_id=?", String.class, f.external.value().toString());
        for (String table : List.of("arch_project", "project_requirement", "solution_definition")) {
            f.jdbc.update("insert into " + table + " values (1,?,?),(2,?,'bob')", selected, external, foreign);
        }
    }

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

    @ParameterizedTest
    @CsvSource({
            "1-1-1-1-1,00000001-0001-0001-0001-000000000001",
            "ABCDEFAB-CDEF-ABCD-EFAB-CDEFABCDEFAB,abcdefab-cdef-abcd-efab-cdefabcdefab"
    })
    void differentStoredOwnersCannotCollapseIntoTheSameExportedPrincipal(String sourceId, String canonicalId) throws Exception {
        try (var fixture = new Fixture()) {
            fixture.jdbc.update("insert into app_principal values (?,?,0)", canonicalId, "canonical-owner");
            fixture.jdbc.update("insert into app_principal values (?,?,0)", sourceId, "noncanonical-owner");
            var output = new CurrentStateExportIT.Contents();
            assertThatThrownBy(() -> fixture.installationContributor().write(
                    snapshot(fixture.alice, BackupProfile.INSTALLATION_CURRENT, SecretsSelection.EXCLUDE), output))
                    .isInstanceOf(java.io.IOException.class).hasMessage("Invalid source principal ID").hasNoCause();
            assertThat(output.entries).isEmpty();
            assertThat(fixture.jdbc.queryForList("select principal_id from app_principal where scope_key in (?,?)", String.class,
                    "canonical-owner", "noncanonical-owner")).containsExactlyInAnyOrder(sourceId, canonicalId);
        }
    }

    @Test void malformedPendingAccountIsRejectedBeforeWritingIdentityDatasets() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.jdbc.update("update app_user set principal_id=? where username='pending'", "INVALID-SOURCE-ID");
            var output = new CurrentStateExportIT.Contents();
            assertThatThrownBy(() -> fixture.installationContributor().write(
                    snapshot(fixture.alice, BackupProfile.INSTALLATION_CURRENT, SecretsSelection.EXCLUDE), output))
                    .isInstanceOf(java.io.IOException.class).hasMessage("Invalid source principal ID").hasNoCause();
            assertThat(output.entries).isEmpty();
            assertThat(fixture.jdbc.queryForObject("select principal_id from app_user where username='pending'", String.class))
                    .isEqualTo("INVALID-SOURCE-ID");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"1-1-1-1-1", "ABCDEFAB-CDEF-ABCD-EFAB-CDEFABCDEFAB", "INVALID-SOURCE-ID"})
    void malformedInstallationIdentityCannotBeSilentlyNormalized(String sourceId) throws Exception {
        try (var fixture = new Fixture()) {
            fixture.jdbc.update("update principal_installation set installation_id=? where registry_key='local'", sourceId);
            var output = new CurrentStateExportIT.Contents();
            assertThatThrownBy(() -> fixture.installationContributor().write(
                    snapshot(fixture.alice, BackupProfile.INSTALLATION_CURRENT, SecretsSelection.EXCLUDE), output))
                    .isInstanceOf(java.io.IOException.class).hasMessage("Invalid source installation ID").hasNoCause();
            assertThat(output.entries).isEmpty();
            assertThat(fixture.jdbc.queryForObject("select installation_id from principal_installation where registry_key='local'", String.class))
                    .isEqualTo(sourceId);
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
