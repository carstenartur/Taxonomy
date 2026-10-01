package com.taxonomy.security.backup;

import com.taxonomy.backup.*;
import com.taxonomy.exchange.backup.PortableRows;
import javax.sql.DataSource;
import java.io.*;
import java.util.*;

/** Resolves explicit source compatibility mappings without creating login identities or grants. */
public final class PrincipalScopeBackupSelector implements BackupPrincipalSelector {
    private static final int MAX_SCOPES = 100_000, BATCH_SIZE = 200;
    private final PortableRows rows;
    private final List<BackupPrincipalScopeSelector> sources;
    private final int maxScopes;
    public PrincipalScopeBackupSelector(DataSource database, List<BackupPrincipalScopeSelector> sources) { this(database, sources, MAX_SCOPES); }
    public PrincipalScopeBackupSelector(DataSource database, List<BackupPrincipalScopeSelector> sources, int maxScopes) {
        rows = new PortableRows(database); this.sources = List.copyOf(sources);
        if (maxScopes < 1 || maxScopes > MAX_SCOPES || sources.size() > BackupLimits.MAX_ITEMS) throw new IllegalArgumentException("Invalid principal selection limits");
        this.maxScopes = maxScopes;
    }
    @Override public Set<PrincipalId> select(SnapshotContext snapshot, BackupCheckpoint checkpoint) throws IOException {
        try {
            BackupCheckpoint guarded = () -> check(checkpoint); guarded.check();
            var scopes = new TreeSet<String>();
            for (var source : sources) {
                guarded.check();
                for (String scope : source.scopes(snapshot, guarded)) {
                    guarded.check();
                    if (scope == null || scope.isBlank() || scope.length() > 255 || scope.chars().anyMatch(Character::isISOControl))
                        throw new IOException("Invalid source principal scope");
                    if (scopes.add(scope) && scopes.size() > maxScopes) throw new IOException("Principal selection limit exceeded");
                }
            }
            var ordered = List.copyOf(scopes); var found = new HashMap<String, PrincipalId>(); var owners = new HashSet<PrincipalId>();
            for (int offset = 0; offset < ordered.size(); offset += BATCH_SIZE) {
                guarded.check(); var batch = ordered.subList(offset, Math.min(offset + BATCH_SIZE, ordered.size()));
                var query = new PortableRows.Query("select principal_id,scope_key from app_principal where scope_key in ("
                        + String.join(",", Collections.nCopies(batch.size(), "?")) + ")", batch);
                rows.visit(query, row -> {
                    guarded.check(); String scope = PortableRows.text(row, "scope_key"), raw = PortableRows.text(row, "principal_id");
                    if (!batch.contains(scope)) throw new IOException("Source principal scope differs from the captured reference");
                    var id = new PrincipalId(UUID.fromString(raw));
                    if (!id.value().toString().equals(raw)) throw new IOException("Noncanonical source principal identity");
                    return new Mapping(scope, id);
                }, mapping -> {
                    if (found.putIfAbsent(mapping.scope(), mapping.id()) != null || !owners.add(mapping.id()))
                        throw new IOException("Ambiguous source principal mapping");
                });
            }
            guarded.check();
            if (!found.keySet().equals(scopes)) throw new IOException("A referenced source principal scope is unmapped");
            return Set.copyOf(owners);
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof InterruptedIOException || Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt(); throw new InterruptedIOException("Principal selection cancelled");
            }
            // Source selectors and JDBC cleanup can carry private values; none become public diagnostics.
            throw new IOException("Principal scope selection failed");
        }
    }
    private static void check(BackupCheckpoint checkpoint) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Principal selection cancelled");
        try { checkpoint.check(); }
        catch (InterruptedIOException failure) { Thread.currentThread().interrupt(); throw failure; }
    }
    private record Mapping(String scope, PrincipalId id) { }
}
