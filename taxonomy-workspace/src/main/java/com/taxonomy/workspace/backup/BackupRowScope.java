package com.taxonomy.workspace.backup;

import com.taxonomy.backup.*;
import com.taxonomy.exchange.backup.PortableRows.Query;
import com.taxonomy.workspace.model.RepositoryTenantIdentity;
import java.util.*;

/** SQL predicates are assembled only from source-owned column names; all selected IDs are parameters. */
public final class BackupRowScope {
    private final SnapshotContext snapshot;
    public BackupRowScope(SnapshotContext snapshot) { this.snapshot = Objects.requireNonNull(snapshot); }
    public boolean selectedVersion() { return snapshot.authorization().request().profile() == BackupProfile.SELECTED_VERSION; }
    public boolean history() { return snapshot.authorization().request().profile().includesHistory(); }
    public boolean installation() { return snapshot.authorization().request().profile().isInstallation(); }
    public Query repositories(String repositoryColumn, String workspaceColumn) {
        column(repositoryColumn); column(workspaceColumn);
        if (installation()) return new Query("1=1", List.of());
        var clauses = new ArrayList<String>(); var parameters = new ArrayList<Object>();
        for (var key : keys()) {
            String clause = "(" + repositoryColumn + "=? and "; parameters.add(key.repositoryId());
            if (key.workspaceId() == null) clause += workspaceColumn + " is null)";
            else { clause += workspaceColumn + "=?)"; parameters.add(key.workspaceId()); }
            clauses.add(clause);
        }
        return predicate(clauses, parameters);
    }
    public Query repositoryIds(String column) {
        column(column);
        if (installation()) return new Query("1=1", List.of());
        var ids = keys().stream().map(BackupRepositoryKey::repositoryId).distinct().sorted().toList();
        return new Query(ids.isEmpty() ? "1=0" : column + " in (" + String.join(",", Collections.nCopies(ids.size(), "?")) + ")", ids);
    }
    public Query tenants(String column) {
        column(column);
        if (installation()) return new Query("1=1", List.of());
        var clauses = new ArrayList<String>(); var parameters = new ArrayList<Object>();
        for (var key : keys()) {
            String identity = new RepositoryTenantIdentity(key.repositoryId(), key.workspaceId() == null ? "CENTRAL" : "WORKSPACE:" + key.workspaceId(), "draft").scopeKey();
            String prefix = identity.substring(0, identity.length() - "5:draft".length());
            clauses.add(column + " like ? escape '!'");
            parameters.add(prefix.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        return predicate(clauses, parameters);
    }
    public boolean includesTenant(String tenant) {
        if (installation()) return true;
        var parsed = RepositoryTenantIdentity.parse(tenant);
        String workspace = parsed.workspaceScope().equals("CENTRAL") ? null : parsed.workspaceScope().substring("WORKSPACE:".length());
        return snapshot.repositories().containsKey(new BackupRepositoryKey(parsed.repositoryId(), workspace));
    }
    private List<BackupRepositoryKey> keys() {
        var keys = snapshot.repositories().keySet().stream().sorted(Comparator.comparing(BackupRepositoryKey::repositoryId)
                .thenComparing(BackupRepositoryKey::workspaceId, Comparator.nullsFirst(Comparator.naturalOrder()))).toList();
        // Bounded predicates also fit the supported database parameter/IN limits. Larger captures use installation scope.
        if (keys.size() > 200) throw new IllegalArgumentException("Too many selected repositories for one portable capture");
        return keys;
    }
    private static Query predicate(List<String> clauses, List<Object> parameters) {
        return new Query(clauses.isEmpty() ? "1=0" : "(" + String.join(" or ", clauses) + ")", parameters);
    }
    private static void column(String column) {
        if (column == null || !column.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)?")) throw new IllegalArgumentException("Invalid source column");
    }
}
