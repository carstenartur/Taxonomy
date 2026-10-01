package com.taxonomy.security.persistence;

import com.taxonomy.backup.IdentityBinding;
import com.taxonomy.security.model.PrincipalBinding;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** Independently versioned, portable identity schema; runs before Hibernate validation. */
public final class PrincipalSchemaMigration {
    private PrincipalSchemaMigration() { }

    public static void migrate(DataSource database) {
        Flyway.configure().dataSource(database).table("principal_schema_history")
                .locations("classpath:db/identity-java-migrations")
                .baselineOnMigrate(true).baselineVersion("0")
                .javaMigrations(new V1__StablePrincipals(), new V2__ExplicitBackupGrants(),
                        new V3__ReserveHistoricalScopes()).load().migrate();
    }

    /**
     * Legacy external accounts may own rows without having an AppUser. Reserve those
     * scope keys as disabled historical identities, without a login binding or grants.
     * This explicit list covers persisted ownership, not display/audit author fields.
     */
    public static final class V3__ReserveHistoricalScopes extends BaseJavaMigration {
        @Override public void migrate(Context context) throws Exception {
            Connection connection = context.getConnection();
            for (String table : new String[]{"user_workspace", "repository_membership", "workspace_projection",
                    "sync_state", "context_history_record", "analysis_working_draft", "webdav_application_credential"}) {
                reserve(connection, table, "username", "");
            }
            for (String table : new String[]{"relation_proposal", "relation_hypothesis", "taxonomy_relation",
                    "arch_project", "project_requirement", "solution_definition"}) {
                reserve(connection, table, "owner_username", "");
            }
            reserve(connection, "system_repository", "owner_id",
                    hasColumn(connection, "system_repository", "owner_type")
                            ? " and (owner_type is null or owner_type='USER')" : "");
        }

        private static void reserve(Connection connection, String table, String column, String predicate) throws SQLException {
            if (!hasTable(connection, table) || !hasColumn(connection, table, column)) return;
            try (var statement = connection.createStatement();
                 var owners = statement.executeQuery("select distinct " + column + " from " + table
                         + " where " + column + " is not null" + predicate)) {
                while (owners.next()) {
                    String scope = owners.getString(1);
                    if (scope.isBlank()) continue;
                    try (var existing = connection.prepareStatement("select principal_id from app_principal where scope_key=?")) {
                        existing.setString(1, scope);
                        try (var matches = existing.executeQuery()) {
                            if (matches.next()) continue;
                        }
                    }
                    update(connection, "insert into app_principal values (?, ?, 0)", UUID.randomUUID().toString(), scope);
                }
            }
        }
    }

    public static final class V2__ExplicitBackupGrants extends BaseJavaMigration {
        @Override public void migrate(Context context) throws Exception {
            Connection connection = context.getConnection();
            for (String capability : new String[]{"EXPORT_CURRENT", "DOWNLOAD_BACKUP"}) {
                execute(connection, "insert into backup_capability_grant(principal_id, capability) select p.principal_id, '" + capability
                        + "' from app_principal p where not exists (select 1 from backup_capability_grant g where g.principal_id=p.principal_id and g.capability='" + capability + "')");
            }
            execute(connection, "create table backup_version_grant (principal_id varchar(36) not null, repository_id varchar(255) not null, workspace_key varchar(257) not null, commit_id varchar(40) not null, constraint pk_backup_version primary key(principal_id, repository_id, workspace_key, commit_id), constraint fk_version_principal foreign key(principal_id) references app_principal(principal_id))");
            execute(connection, "create table principal_access_audit (audit_id varchar(36) primary key, actor_principal varchar(36) not null, target_principal varchar(36) not null, decision_kind varchar(40) not null, detail varchar(2048) not null, occurred_at numeric(19,0) not null)");
        }
    }

    public static final class V1__StablePrincipals extends BaseJavaMigration {
        @Override public void migrate(Context context) throws Exception {
            Connection connection = context.getConnection();
            execute(connection, "create table principal_installation (registry_key varchar(16) primary key, installation_id varchar(36) not null unique)");
            execute(connection, "create table app_principal (principal_id varchar(36) primary key, scope_key varchar(255) not null unique, enabled smallint not null)");
            execute(connection, "create table principal_binding (binding_key varchar(64) primary key, principal_id varchar(36) not null, binding_kind varchar(16) not null, issuer varchar(2048) not null, subject_id varchar(1024) not null, enabled smallint not null, constraint fk_binding_principal foreign key(principal_id) references app_principal(principal_id))");
            execute(connection, "create index idx_binding_principal on principal_binding(principal_id)");
            execute(connection, "create table backup_capability_grant (principal_id varchar(36) not null, capability varchar(40) not null, constraint pk_backup_capability primary key(principal_id, capability), constraint fk_capability_principal foreign key(principal_id) references app_principal(principal_id))");
            String installation = UUID.randomUUID().toString();
            update(connection, "insert into principal_installation values ('local', ?)", installation);
            if (!hasTable(connection, "app_user")) return;
            if (!hasColumn(connection, "app_user", "principal_id")) {
                execute(connection, "alter table app_user add principal_id varchar(36)");
            }
            try (var statement = connection.createStatement();
                 var users = statement.executeQuery("select id, username, principal_id from app_user")) {
                while (users.next()) {
                    String id = users.getString("principal_id");
                    if (id == null) {
                        id = UUID.randomUUID().toString();
                        try (var assign = connection.prepareStatement("update app_user set principal_id=? where id=?")) {
                            assign.setString(1, id); assign.setLong(2, users.getLong("id")); assign.executeUpdate();
                        }
                    }
                    UUID.fromString(id);
                    String scope = users.getString("username");
                    update(connection, "insert into app_principal values (?, ?, 1)", id, scope);
                    var binding = new IdentityBinding(IdentityBinding.Kind.LOCAL, installation, id);
                    update(connection, "insert into principal_binding values (?, ?, 'LOCAL', ?, ?, 1)",
                            PrincipalBinding.key(binding), id, installation, id);
                }
            }
            execute(connection, "create unique index uq_app_user_principal on app_user(principal_id)");
        }
    }

    private static boolean hasTable(Connection connection, String name) throws SQLException {
        try (var tables = connection.getMetaData().getTables(connection.getCatalog(), connection.getSchema(), "%", new String[]{"TABLE"})) {
            while (tables.next()) if (name.equalsIgnoreCase(tables.getString("TABLE_NAME"))) return true;
        }
        return false;
    }

    private static boolean hasColumn(Connection connection, String table, String name) throws SQLException {
        try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), connection.getSchema(), "%", "%")) {
            while (columns.next()) if (table.equalsIgnoreCase(columns.getString("TABLE_NAME"))
                    && name.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) return true;
        }
        return false;
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }

    private static void update(Connection connection, String sql, String... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setString(i + 1, values[i]);
            statement.executeUpdate();
        }
    }
}
