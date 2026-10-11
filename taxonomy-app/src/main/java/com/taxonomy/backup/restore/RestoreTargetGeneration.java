package com.taxonomy.backup.restore;

import com.taxonomy.backup.*;
import org.hibernate.SessionFactory;
import javax.sql.DataSource;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;

/** Identity-verified wiring for staging. This is neither owner readiness nor activation approval. */
public final class RestoreTargetGeneration {
    private final UUID generationId;
    private final DataSource active;
    private final DataSource staged;
    private final SessionFactory factory;
    private final TargetDatabaseIdentity activeIdentity;
    private final TargetDatabaseIdentity targetIdentity;
    private final Map<BackupRepositoryKey, RestoreRepositoryAllocation> allocations;

    private RestoreTargetGeneration(UUID generationId, DataSource active, DataSource staged, SessionFactory factory,
                                    TargetDatabaseIdentity activeIdentity, TargetDatabaseIdentity targetIdentity,
                                    Map<BackupRepositoryKey, RestoreRepositoryAllocation> allocations) {
        this.generationId = generationId; this.active = active; this.staged = staged; this.factory = factory;
        this.activeIdentity = activeIdentity; this.targetIdentity = targetIdentity; this.allocations = allocations;
    }

    public static RestoreTargetGeneration verified(UUID generationId, DataSource active, DataSource staged,
            SessionFactory factory, Map<BackupRepositoryKey, RestoreRepositoryAllocation> allocations,
            BackupCheckpoint checkpoint) throws IOException {
        Objects.requireNonNull(generationId); Objects.requireNonNull(factory);
        var immutable = Map.copyOf(allocations);
        if (immutable.size() > BackupLimits.MAX_ITEMS) throw new IllegalArgumentException("Too many repository allocations");
        var targets = new HashSet<BackupRepositoryKey>(); var storage = new HashSet<String>();
        for (var allocation : immutable.values())
            if (!targets.add(allocation.targetKey()) || !storage.add(allocation.physicalStorageName()))
                throw new IllegalArgumentException("Colliding repository allocations");
        var sourceIdentity = TargetDatabaseIdentity.inspect(active, checkpoint);
        var targetIdentity = TargetDatabaseIdentity.proveIsolated(active, staged, checkpoint);
        requireFactory(factory, targetIdentity, checkpoint);
        return new RestoreTargetGeneration(generationId, active, staged, factory, sourceIdentity, targetIdentity, immutable);
    }

    /** Rechecks physical bindings; durable generation and content proofs are separately required by readiness. */
    public void requireUnchanged(BackupCheckpoint checkpoint) throws IOException {
        if (!activeIdentity.equals(TargetDatabaseIdentity.inspect(active, checkpoint))
                || !targetIdentity.equals(TargetDatabaseIdentity.inspect(staged, checkpoint)))
            throw new IOException("Restore generation database binding changed");
        requireFactory(factory, targetIdentity, checkpoint);
    }

    /** Proves the connection actually used by an owner, including the current active destination. */
    public void requireConnection(java.sql.Connection connection, BackupCheckpoint checkpoint) throws IOException {
        checkpoint.check();
        TargetDatabaseIdentity actual;
        try { actual = TargetDatabaseIdentity.inspectConnection(connection); }
        catch (SQLException invalid) { throw new IOException("Restore database binding is unavailable"); }
        var currentActive = TargetDatabaseIdentity.inspect(active, checkpoint);
        if (!targetIdentity.equals(actual) || !activeIdentity.equals(currentActive)
                || actual.destinationFingerprint().equals(currentActive.destinationFingerprint()))
            throw new IOException("Restore database binding changed or is not isolated");
        checkpoint.check();
    }

    private static void requireFactory(SessionFactory factory, TargetDatabaseIdentity expected,
                                       BackupCheckpoint checkpoint) throws IOException {
        checkpoint.check(); TargetDatabaseIdentity actual;
        try (var session = factory.openSession()) {
            actual = session.doReturningWork(connection -> {
                try { return TargetDatabaseIdentity.inspectConnection(connection); }
                catch (IOException invalid) { throw new SQLException("Restore factory identity is unavailable"); }
            });
        } catch (RuntimeException invalid) { throw new IOException("Restore factory identity is unavailable or unproven"); }
        checkpoint.check();
        if (!expected.equals(actual)) throw new IOException("Restore factory is not bound to the isolated target generation");
    }

    public UUID generationId() { return generationId; }
    public DataSource dataSource() { return staged; }
    public SessionFactory sessionFactory() { return factory; }
    public TargetDatabaseIdentity databaseIdentity() { return targetIdentity; }
    public Map<BackupRepositoryKey, RestoreRepositoryAllocation> allocations() { return allocations; }
}
