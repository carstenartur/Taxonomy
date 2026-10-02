package com.taxonomy.interop.backup;

import com.taxonomy.backup.*;
import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.exchange.backup.PortableRows.Query;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import static com.taxonomy.exchange.backup.PortableRows.text;

/** Routing and relational closure only. The caller still owns the stable capture lease and Git/content closure. */
final class IntegrationBackupSelection {
    private final Map<String, BackupIntegrationScope> scopes;
    private final boolean installation;
    private final PortableRows rows;
    private final ComponentSink sink;

    IntegrationBackupSelection(SnapshotContext snapshot, BackupIntegrationScope.Selector selector,
                               PortableRows rows, ComponentSink sink) throws IOException {
        this.rows = rows; this.sink = sink; installation = snapshot.authorization().request().profile().isInstallation();
        var selected = List.copyOf(selector.select(snapshot, sink::checkpoint));
        if (selected.size() > BackupLimits.MAX_ITEMS) throw new IOException("Integration scope selection limit exceeded");
        var found = new TreeMap<String, BackupIntegrationScope>();
        for (var scope : selected) {
            sink.checkpoint();
            if (!snapshot.repositories().containsKey(scope.repository()) || found.putIfAbsent(scope.scopeId(), scope) != null)
                throw new IOException("Invalid or duplicate integration scope selection");
        }
        scopes = Collections.unmodifiableMap(found);
    }

    void verify() throws IOException {
        // Installation ownership cannot be reconstructed from a one-way hash. Every connection needs server proof.
        for (var query : queries("e.scope_id,e.repository_id", "interop_connection e", "1=1")) {
            sink.checkpoint();
            rows.visit(query, r -> new Owner(text(r, "scope_id"), text(r, "repository_id")), owner -> {
                sink.checkpoint(); var scope = scopes.get(owner.scopeId());
                if (scope == null || !scope.repository().repositoryId().equals(owner.repositoryId()))
                    throw new IOException("Integration connection has no matching captured workspace");
            });
        }
        for (String table : List.of("interop_identity", "interop_checkpoint", "interop_operation", "interop_event",
                "interop_publication", "interop_publish_item", "interop_publish_attempt")) {
            // Include either end of the edge: a child with a changed hash must not disappear from its selected parent.
            for (var query : queries("e.id", table + " e left join interop_connection c on c.id=e.connection_id",
                    "(c.id is null or c.scope_id<>e.scope_id)", List.of("e.scope_id", "c.scope_id"))) requireEmpty(query);
        }
        for (String pointer : List.of("checkpoint_id", "common_checkpoint_id", "active_operation_id")) {
            String parent = pointer.equals("active_operation_id") ? "interop_operation" : "interop_checkpoint";
            reject("interop_connection e", "e." + pointer + " is not null and not exists (select 1 from " + parent
                    + " p where p.id=e." + pointer + " and p.scope_id=e.scope_id and p.connection_id=e.id)");
        }
        for (String table : List.of("interop_identity", "interop_checkpoint", "interop_event"))
            parent(table, "operation_id", "interop_operation", "");
        parent("interop_publication", "id", "interop_operation", "");
        parent("interop_publication", "predecessor_id", "interop_publication", "");
        parent("interop_publication", "common_checkpoint_id", "interop_checkpoint", "");
        parent("interop_publish_item", "operation_id", "interop_publication", "");
        parent("interop_publish_attempt", "item_id", "interop_publish_item", " and p.operation_id=e.operation_id");
        reject("interop_operation e", "e.direction in ('PUSH','SYNCHRONIZE') and not exists (select 1 from interop_publication p"
                + " where p.id=e.id and p.scope_id=e.scope_id and p.connection_id=e.connection_id)");
        // A contradictory terminal marker must not silently drop unfinished work from a current capture.
        reject("interop_publication e join interop_operation o on o.id=e.id",
                "o.direction not in ('PUSH','SYNCHRONIZE') or (e.phase in ('COMPLETED','CANCELLED') and o.status<>e.phase)"
                        + " or (o.status in ('COMPLETED','CANCELLED') and e.phase<>o.status)");
    }

    private void parent(String table, String column, String parent, String extra) throws IOException {
        reject(table + " e", "e." + column + " is not null and not exists (select 1 from " + parent
                + " p where p.id=e." + column + " and p.scope_id=e.scope_id and p.connection_id=e.connection_id" + extra + ")");
    }
    private void reject(String from, String predicate) throws IOException {
        for (var query : queries("e.id", from, predicate)) requireEmpty(query);
    }
    private void requireEmpty(Query query) throws IOException { sink.checkpoint(); rows.requireEmpty(query); }

    BackupIntegrationScope scope(ResultSet row) throws SQLException, IOException {
        var scope = scopes.get(text(row, "scope_id"));
        if (scope == null) throw new IOException("Integration record has no captured workspace");
        return scope;
    }

    List<Query> queries(String columns, String from, String predicate) {
        return queries(columns, from, predicate, List.of("e.scope_id"));
    }
    private List<Query> queries(String columns, String from, String predicate, List<String> scopeColumns) {
        String prefix = "select " + columns + " from " + from + " where ";
        if (installation) return List.of(new Query(prefix + "(" + predicate + ") order by e.id", List.of()));
        var result = new ArrayList<Query>(); var ids = new ArrayList<>(scopes.keySet());
        for (int start = 0; start < ids.size(); start += 200) {
            var batch = ids.subList(start, Math.min(start + 200, ids.size()));
            String slots = String.join(",", Collections.nCopies(batch.size(), "?"));
            var parameters = new ArrayList<String>();
            var predicates = new ArrayList<String>();
            for (String column : scopeColumns) { predicates.add(column + " in (" + slots + ")"); parameters.addAll(batch); }
            result.add(new Query(prefix + "(" + String.join(" or ", predicates) + ") and (" + predicate + ") order by e.id", parameters));
        }
        return List.copyOf(result);
    }
    private record Owner(String scopeId, String repositoryId) { }
}
