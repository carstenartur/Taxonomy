package com.taxonomy.security.service;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only inventory at the LEGACY-to-STABLE boundary. V3 reserved the owners present
 * during migration, but LEGACY servers can keep writing username-owned rows afterwards.
 * Such later rows must never silently become new STABLE identities.
 *
 * This is a startup fence, not a distributed migration coordinator: the operator must
 * stop LEGACY writers and approve the actual issuer/subject mapping (EXT-02).
 */
public final class LegacyOwnerMigrationReadiness {
    private static final int MAX_SCOPES = 100_000;
    private static final List<Source> SOURCES = List.of(
            new Source("user_workspace", "username"),
            new Source("repository_membership", "username"),
            new Source("workspace_projection", "username"),
            new Source("sync_state", "username"),
            new Source("context_history_record", "username"),
            new Source("analysis_working_draft", "username"),
            new Source("analysis_continuation", "username"),
            new Source("analysis_cluster_run", "username"),
            new Source("workspace_branch_sync", "username"),
            new Source("webdav_application_credential", "username"),
            new Source("relation_proposal", "owner_username"),
            new Source("relation_hypothesis", "owner_username"),
            new Source("taxonomy_relation", "owner_username"),
            new Source("arch_project", "owner_username"),
            new Source("project_requirement", "owner_username"),
            new Source("solution_definition", "owner_username"),
            new Source("system_repository", "owner_id"));

    private LegacyOwnerMigrationReadiness() { }

    public record Inventory(int observedScopes, int missingReservations, long unresolvedReservations,
                            int unverifiedOwnerBindings) {
        public boolean ready() {
            return missingReservations == 0 && unresolvedReservations == 0 && unverifiedOwnerBindings == 0;
        }
    }

    public static Inventory inspect(DataSource database) {
        Objects.requireNonNull(database, "database");
        return inspect(new JdbcTemplate(database));
    }

    /** Uses the caller's current transaction, including its principal registry lock. */
    static Inventory inspect(JdbcTemplate jdbc) {
        return Objects.requireNonNull(jdbc.execute(
                (ConnectionCallback<Inventory>) LegacyOwnerMigrationReadiness::read));
    }

    public static void requireReady(DataSource database) {
        if (!inspect(database).ready()) {
            throw new IllegalStateException("STABLE identity startup requires a verified migration of "
                    + "all historical ownership; keep LEGACY enabled until the operator completes EXT-02");
        }
    }

    private static Set<String> observedScopes(Connection connection) throws SQLException {
        if (!hasTable(connection, "app_principal") || !hasTable(connection, "principal_binding")
                || !hasTable(connection, "principal_access_audit")) {
            throw new IllegalStateException("Stable identity schema must be initialized before mode selection");
        }
        Set<String> scopes = new HashSet<>();
        for (var source : SOURCES) {
            if (!hasTable(connection, source.table()) || !hasColumn(connection, source.table(), source.column())) {
                continue;
            }
            String condition = "";
            if ("system_repository".equals(source.table())
                    && hasColumn(connection, "system_repository", "owner_type")) {
                condition = " and (owner_type is null or owner_type='USER')";
            }
            // Preserve exact spellings even on databases with a case-insensitive collation.
            // Both identifiers and the optional predicate are fixed schema names.
            String sql = "select " + source.column() + " from " + source.table()
                    + " where " + source.column() + " is not null" + condition;
            try (var statement = connection.createStatement()) {
                statement.setFetchSize(512);
                try (var rows = statement.executeQuery(sql)) {
                    while (rows.next()) {
                        String scope = rows.getString(1);
                        if (scope != null && scope.isBlank()) {
                            // A malformed owner is not proof of an absent historical identity.
                            // Never silently discard it at the STABLE authority boundary.
                            throw new IllegalStateException("Historical owner scope is blank; operator repair required");
                        }
                        if (scope != null) {
                            scopes.add(scope);
                            if (scopes.size() > MAX_SCOPES) {
                                throw new IllegalStateException("Historical owner inventory exceeds the safe startup bound");
                            }
                        }
                    }
                }
            }
        }
        return scopes;
    }

    /** The caller holds the installation registry lock throughout discovery and reservation. */
    static List<String> missingScopes(JdbcTemplate jdbc, int limit) {
        return jdbc.execute((ConnectionCallback<List<String>>) connection -> {
            var missing = new ArrayList<String>();
            var observed = observedScopes(connection);
            try (var query = connection.prepareStatement("select scope_key from app_principal where scope_key=?")) {
                for (String scope : observed) {
                    query.setString(1, scope);
                    try (var rows = query.executeQuery()) {
                        if (rows.next()) {
                            requireExactScope(scope, rows.getString(1));
                        } else {
                            if (missing.size() == limit) {
                                throw new IllegalStateException("Reservation limit exceeded; review the historical inventory");
                            }
                            missing.add(scope);
                        }
                    }
                }
            }
            missing.sort(String::compareTo);
            return List.copyOf(missing);
        });
    }

    private static void requireExactScope(String observed, String retained) {
        if (!observed.equals(retained)) {
            throw new IllegalStateException("Ambiguous historical ownership scope; operator repair required");
        }
    }

    private static Inventory read(Connection connection) throws SQLException {
        var scopes = observedScopes(connection);
        int missing = 0;
        int unverified = 0;
        try (var owners = connection.prepareStatement("""
                select p.principal_id, p.enabled, b.binding_kind, b.enabled, p.scope_key
                from app_principal p left join principal_binding b on b.principal_id=p.principal_id
                where p.scope_key=?
                """);
             var audit = connection.prepareStatement("""
                select count(distinct decision_kind) from principal_access_audit
                where target_principal=? and decision_kind in ('LINK_LEGACY_OIDC', ?)
                """)) {
            for (String scope : scopes) {
                owners.setString(1, scope);
                boolean found = false;
                boolean verified = false;
                Set<HistoricalBinding> historicalBindings = new HashSet<>();
                try (var rows = owners.executeQuery()) {
                    while (rows.next()) {
                        requireExactScope(scope, rows.getString(5));
                        found = true;
                        boolean principalEnabled = rows.getInt(2) == 1;
                        boolean bindingEnabled = rows.getInt(4) == 1;
                        String kind = rows.getString(3);
                        if ("LOCAL".equals(kind) && principalEnabled && bindingEnabled) {
                            verified = true;
                        } else if ("OIDC".equals(kind)) {
                            String id = rows.getString(1);
                            if (scope.equals("principal-" + id.toLowerCase(Locale.ROOT))) {
                                verified = true; // new server-generated STABLE scope
                            } else if (principalEnabled == bindingEnabled) {
                                historicalBindings.add(new HistoricalBinding(id, !principalEnabled));
                            }
                        }
                    }
                }
                // Close the owner cursor first: SQL Server connections without MARS may not
                // execute a second statement while the initial ResultSet is still open.
                if (!verified) for (var binding : historicalBindings) {
                    audit.setString(1, binding.id());
                    // A disabled historical identity needs both the original link and its audited removal.
                    audit.setString(2, binding.revoked() ? "UNLINK_LEGACY_OIDC" : "LINK_LEGACY_OIDC");
                    try (var matches = audit.executeQuery()) {
                        if (matches.next() && matches.getInt(1) == (binding.revoked() ? 2 : 1)) {
                            verified = true;
                            break;
                        }
                    }
                }
                if (!found) missing++;
                else if (!verified) unverified++;
            }
        }
        long unresolved;
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("""
                     select count(*) from app_principal p where p.enabled=0
                     and not exists (select 1 from principal_binding b where b.principal_id=p.principal_id)
                     """)) {
            rows.next();
            unresolved = rows.getLong(1);
        }
        return new Inventory(scopes.size(), missing, unresolved, unverified);
    }

    private static boolean hasTable(Connection connection, String name) throws SQLException {
        try (var tables = connection.getMetaData().getTables(
                connection.getCatalog(), connection.getSchema(), "%", new String[]{"TABLE"})) {
            while (tables.next()) if (name.equalsIgnoreCase(tables.getString("TABLE_NAME"))) return true;
        }
        return false;
    }

    private static boolean hasColumn(Connection connection, String table, String name) throws SQLException {
        try (var columns = connection.getMetaData().getColumns(
                connection.getCatalog(), connection.getSchema(), "%", "%")) {
            while (columns.next()) {
                if (table.equalsIgnoreCase(columns.getString("TABLE_NAME"))
                        && name.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) return true;
            }
        }
        return false;
    }

    private record Source(String table, String column) { }
    private record HistoricalBinding(String id, boolean revoked) { }
}
