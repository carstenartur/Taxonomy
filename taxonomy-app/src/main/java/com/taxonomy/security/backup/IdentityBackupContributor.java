package com.taxonomy.security.backup;

import com.taxonomy.backup.*;
import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.exchange.backup.PortableRows.Query;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

import static com.taxonomy.exchange.backup.PortableRows.*;

/** Source identity evidence never grants target login, ownership or administrative privileges. */
public final class IdentityBackupContributor implements BackupDataContributor {
    private static final int MAX_IDENTITIES = 100_000;
    private final PortableRows rows;
    private final BackupPrincipalSelector references;

    public IdentityBackupContributor(DataSource database, BackupPrincipalSelector references) {
        rows = new PortableRows(database); this.references = Objects.requireNonNull(references);
    }
    @Override public BackupComponentId componentId() { return new BackupComponentId("application"); }
    @Override public int schemaVersion() { return 1; }
    @Override public Set<String> categories() { return Set.of("com.taxonomy.security.model.AppUser", "com.taxonomy.security.model.AppRole",
            "storage.security.user-roles", "storage.security.principals", "storage.security.backup-grants"); }
    @Override public List<String> omissions(BackupProfile profile) {
        var omissions = new ArrayList<String>();
        if (!profile.isInstallation()) omissions.add("identities: only referenced principals and their provider bindings are included; local accounts, roles and administrative grants require installation scope");
        if (profile != BackupProfile.INSTALLATION_FULL) omissions.add("identities: historical access audit is excluded");
        omissions.add("identities: password hashes require explicit encrypted full-installation selection; sessions, tokens and target authority are never imported automatically");
        return List.copyOf(omissions);
    }

    @Override public void write(SnapshotContext snapshot, ComponentSink sink) throws IOException {
        var request = snapshot.authorization().request(); var profile = request.profile();
        var requested = new HashSet<PrincipalId>();
        if (!profile.isInstallation()) requested.addAll(references.select(snapshot, sink::checkpoint));
        requested.add(snapshot.authorization().principalId());
        if (requested.contains(null) || requested.size() > MAX_IDENTITIES) throw new IOException("Invalid identity selection");
        var selection = new Selection(profile.isInstallation(), requested.stream().map(id -> id.value().toString()).sorted().toList());
        var found = new HashSet<PrincipalId>();
        for (String table : List.of("app_principal", "app_user")) {
            for (var query : selected("select principal_id from " + table, "1=1", "principal_id", selection)) {
                rows.visit(query, r -> { sink.checkpoint(); return new IdentityKey(principal(r, "principal_id")); }, key -> {
                    found.add(key.id());
                    if (found.size() > MAX_IDENTITIES) throw new IOException("Identity capture limit exceeded");
                });
            }
        }
        if (!found.containsAll(requested)) throw new IOException("A referenced source principal is missing");
        var installation = new ArrayList<InstallationRecord>();
        rows.visit(new Query("select installation_id from principal_installation where registry_key='local'", List.of()),
                r -> new InstallationRecord(UUID.fromString(text(r, "installation_id")).toString()), installation::add);
        if (installation.size() != 1) throw new IOException("Missing source identity installation");
        verifyDependencies(selection, profile, sink);

        var principalQueries = new ArrayList<>(selected("select p.principal_id,p.scope_key,p.enabled,u.display_name,u.email from app_principal p left join app_user u on u.principal_id=p.principal_id",
                "1=1", "p.principal_id", selection));
        // Accounts created since the last migration may not have logged in yet. Preserve them without registering
        // a binding or assigning their username as an ownership scope during this read-only operation.
        principalQueries.addAll(selected("select u.principal_id,cast(null as varchar(255)) as scope_key,0 as enabled,u.display_name,u.email from app_user u",
                "not exists (select 1 from app_principal p where p.principal_id=u.principal_id)", "u.principal_id", selection));
        write(sink, "principals", profile, principalQueries, r -> {
            String scope = text(r, "scope_key");
            return new PrincipalRecord(principal(r, "principal_id"), scope,
                    scope == null ? RegistryState.PENDING_LOCAL_ACCOUNT : RegistryState.REGISTERED,
                    flag(r, "enabled"), text(r, "display_name"), text(r, "email"), RestorePolicy.EXPLICIT_MAPPING);
        });
        write(sink, "bindings", profile, selected("select binding_key,principal_id,binding_kind,issuer,subject_id,enabled from principal_binding",
                "1=1", "principal_id", selection), r -> new BindingRecord(reference(r, "application.identity-binding", "binding_key"),
                principal(r, "principal_id"), new IdentityBinding(IdentityBinding.Kind.valueOf(text(r, "binding_kind")),
                text(r, "issuer"), text(r, "subject_id")), flag(r, "enabled")));
        write(sink, "local-accounts", profile, installation(profile,
                "select id,principal_id,username,enabled,must_change_password,display_name,email from app_user order by id"), r ->
                new AccountRecord(reference(r, "application.local-account", "id"), principal(r, "principal_id"), text(r, "username"),
                        r.getBoolean("enabled"), r.getBoolean("must_change_password"), text(r, "display_name"), text(r, "email")));
        write(sink, "roles", profile, installation(profile, "select id,name from app_role order by id"),
                r -> new RoleRecord(reference(r, "application.role", "id"), text(r, "name")));
        write(sink, "user-roles", profile, installation(profile, "select user_id,role_id from user_roles order by user_id,role_id"),
                r -> new UserRoleRecord(reference(r, "application.local-account", "user_id"), reference(r, "application.role", "role_id")));
        write(sink, "capability-grants", profile, installation(profile,
                "select principal_id,capability from backup_capability_grant order by principal_id,capability"),
                r -> new CapabilityGrant(principal(r, "principal_id"), BackupCapability.valueOf(text(r, "capability"))));
        write(sink, "version-grants", profile, installation(profile,
                "select principal_id,repository_id,workspace_key,commit_id from backup_version_grant order by principal_id,repository_id,workspace_key,commit_id"), r -> {
            String workspace = text(r, "workspace_key");
            if (workspace == null || (!workspace.equals("C:") && (!workspace.startsWith("W:") || workspace.length() == 2)))
                throw new IOException("Invalid source version-grant scope");
            String commit = text(r, "commit_id");
            if (commit == null || !commit.matches("[0-9a-f]{40}")) throw new IOException("Invalid source version grant");
            return new VersionGrant(principal(r, "principal_id"), new BackupRepositoryKey(text(r, "repository_id"),
                    workspace.equals("C:") ? null : workspace.substring(2)), commit);
        });
        write(sink, "access-audit", profile, profile == BackupProfile.INSTALLATION_FULL ? List.of(new Query(
                "select audit_id,actor_principal,target_principal,decision_kind,detail,occurred_at from principal_access_audit order by occurred_at,audit_id", List.of())) : List.of(),
                r -> new AccessAudit(reference(r, "application.identity-audit", "audit_id"), principal(r, "actor_principal"), principal(r, "target_principal"),
                        text(r, "decision_kind"), text(r, "detail"), Instant.ofEpochMilli(r.getLong("occurred_at")).toString()));
        PortableRows.document(sink, "identities/installation.json", installation.getFirst());
        if (request.secrets() == SecretsSelection.INCLUDE_ENCRYPTED) {
            // BackupRequest/AuthorizedBackupRequest require FULL + INCLUDE_SECRETS; the capture coordinator
            // also requires encrypted staging. Keeping the hashes in this namespace enforces archive protection.
            rows.writeBatchesAtPath(sink, "protected/identities/password-hashes.ndjson", "password-hashes", profile,
                    List.of(new Query("select id,principal_id,password_hash from app_user order by id", List.of())), r -> {
                        sink.checkpoint();
                        return new PasswordHash(reference(r, "application.local-account", "id"), principal(r, "principal_id"), text(r, "password_hash"));
                    });
        }
    }

    private void verifyDependencies(Selection selection, BackupProfile profile, ComponentSink sink) throws IOException {
        for (var query : selected("select b.binding_key from principal_binding b",
                "not exists (select 1 from app_principal p where p.principal_id=b.principal_id)", "b.principal_id", selection)) {
            sink.checkpoint(); rows.requireEmpty(query);
        }
        if (!profile.isInstallation()) return;
        for (String sql : List.of(
                "select user_id from user_roles ur where not exists (select 1 from app_user u where u.id=ur.user_id) or not exists (select 1 from app_role r where r.id=ur.role_id)",
                "select principal_id from backup_capability_grant g where not exists (select 1 from app_principal p where p.principal_id=g.principal_id)",
                "select principal_id from backup_version_grant g where not exists (select 1 from app_principal p where p.principal_id=g.principal_id)")) {
            sink.checkpoint(); rows.requireEmpty(new Query(sql, List.of()));
        }
        if (profile == BackupProfile.INSTALLATION_FULL) {
            for (String column : List.of("actor_principal", "target_principal")) {
                sink.checkpoint();
                rows.requireEmpty(new Query("select audit_id from principal_access_audit a where not exists (select 1 from app_principal p where p.principal_id=a."
                        + column + ") and not exists (select 1 from app_user u where u.principal_id=a." + column + ")", List.of()));
            }
        }
    }

    private <T extends Record> void write(ComponentSink sink, String kind, BackupProfile profile, List<Query> queries,
                                          PortableRows.Mapper<T> mapper) throws IOException {
        rows.writeBatchesAtPath(sink, "identities/" + kind + ".ndjson", kind, profile, queries, r -> {
            sink.checkpoint(); return mapper.read(r);
        });
    }
    private static List<Query> installation(BackupProfile profile, String sql) {
        return profile.isInstallation() ? List.of(new Query(sql, List.of())) : List.of();
    }
    private static List<Query> selected(String select, String condition, String column, Selection selection) {
        String where = select + " where (" + condition + ")";
        if (selection.installation()) return List.of(new Query(where + " order by " + column, List.of()));
        var queries = new ArrayList<Query>();
        for (int start = 0; start < selection.ids().size(); start += 200) {
            var ids = selection.ids().subList(start, Math.min(start + 200, selection.ids().size()));
            queries.add(new Query(where + " and " + column + " in (" + String.join(",", Collections.nCopies(ids.size(), "?"))
                    + ") order by " + column, ids));
        }
        return queries;
    }
    private static PrincipalId principal(ResultSet row, String column) throws SQLException, IOException {
        try { return new PrincipalId(UUID.fromString(text(row, column))); }
        catch (IllegalArgumentException | NullPointerException invalid) { throw new IOException("Invalid source principal ID"); }
    }
    private static boolean flag(ResultSet row, String column) throws SQLException, IOException {
        int value = row.getInt(column);
        if (row.wasNull() || (value != 0 && value != 1)) throw new IOException("Invalid source identity flag");
        return value == 1;
    }
    private record Selection(boolean installation, List<String> ids) { }
    private record IdentityKey(PrincipalId id) { }
    public enum RegistryState { REGISTERED, PENDING_LOCAL_ACCOUNT }
    public enum RestorePolicy { EXPLICIT_MAPPING }
    public record PrincipalRecord(PrincipalId id, String sourceScope, RegistryState registryState, boolean sourceEnabled,
                                  String displayName, String email, RestorePolicy restorePolicy) { }
    public record BindingRecord(SourceRecordId sourceId, PrincipalId principal, IdentityBinding binding, boolean sourceEnabled) { }
    public record AccountRecord(SourceRecordId sourceId, PrincipalId principal, String username, boolean sourceEnabled,
                                boolean mustChangePassword, String displayName, String email) { }
    public record RoleRecord(SourceRecordId sourceId, String name) { }
    public record UserRoleRecord(SourceRecordId account, SourceRecordId role) { }
    public record CapabilityGrant(PrincipalId principal, BackupCapability capability) { }
    public record VersionGrant(PrincipalId principal, BackupRepositoryKey repository, String commit) { }
    public record AccessAudit(SourceRecordId sourceId, PrincipalId actor, PrincipalId target, String decision, String detail, String occurredAt) { }
    public record InstallationRecord(String installationId) { }
    public record PasswordHash(SourceRecordId account, PrincipalId principal, String passwordHash) { }
}
