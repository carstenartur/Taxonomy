package com.taxonomy.exchange.backup;

import com.taxonomy.backup.BackupCheckpoint;
import java.io.*;
import java.util.*;

/** Bounded scope collection; each module still owns its queries and exact row-ownership checks. */
public final class PrincipalScopeCapture {
    private static final int MAX_SCOPES = 100_000;
    private final PortableRows rows;
    private final int maxScopes;
    public PrincipalScopeCapture(PortableRows rows) { this(rows, MAX_SCOPES); }
    public PrincipalScopeCapture(PortableRows rows, int maxScopes) {
        this.rows = Objects.requireNonNull(rows);
        if (maxScopes < 1 || maxScopes > MAX_SCOPES) throw new IllegalArgumentException("Invalid principal scope limit");
        this.maxScopes = maxScopes;
    }
    public Set<String> capture(List<PortableRows.Query> queries, PortableRows.Mapper<Scope> mapper, BackupCheckpoint checkpoint) throws IOException {
        try {
            check(checkpoint); var scopes = new HashSet<String>();
            for (var query : queries) {
                check(checkpoint);
                rows.visit(query, row -> { check(checkpoint); return mapper.read(row); }, reference -> {
                    String scope = reference.value();
                    if (scope == null) return; // Optional owner fields carry no identity reference.
                    if (scope.isBlank() || scope.length() > 255 || scope.chars().anyMatch(Character::isISOControl))
                        throw new IOException("Invalid source principal scope");
                    if (scopes.add(scope) && scopes.size() > maxScopes) throw new IOException("Principal scope limit exceeded");
                });
            }
            check(checkpoint); return Set.copyOf(scopes);
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof InterruptedIOException || Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt(); throw new InterruptedIOException("Principal scope capture cancelled");
            }
            throw new IOException("Principal scope capture failed");
        }
    }
    private static void check(BackupCheckpoint checkpoint) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Principal scope capture cancelled");
        try { checkpoint.check(); }
        catch (InterruptedIOException failure) { Thread.currentThread().interrupt(); throw failure; }
    }
    public record Scope(String value) { }
}
