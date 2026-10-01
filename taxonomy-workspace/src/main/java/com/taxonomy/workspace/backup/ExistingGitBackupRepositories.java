package com.taxonomy.workspace.backup;

import com.taxonomy.backup.*;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.*;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.hibernate.SessionFactory;
import javax.sql.DataSource;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.taxonomy.workspace.backup.GitTreeCapture.failure;
import static com.taxonomy.workspace.backup.GitTreeCapture.sanitized;

/** Explicit catalogue routing and existing-only Git handles for fenced capture. Never provisions or seeds storage. */
public final class ExistingGitBackupRepositories {
    private final DataSource database;
    private final SecuredHibernateRepositoryFactory<Access> repositories;

    public ExistingGitBackupRepositories(DataSource database, SessionFactory sessions) {
        this.database = Objects.requireNonNull(database);
        repositories = new SecuredHibernateRepositoryFactory<>(Objects.requireNonNull(sessions), (access, request) -> {
            if (!access.active().get() || !access.storage().equals(request.repositoryName())
                    || request.operation() != RepositoryAccessOperation.DISCOVER && request.operation() != RepositoryAccessOperation.READ)
                throw new RepositoryAccessDeniedException(request, "BACKUP_READ_ONLY", access.decision(), 1);
        });
    }

    /** Requires the coordinator's fence and current authorization checks; past decision evidence alone grants no access. */
    public ReadSession open(AuthorizedBackupRequest authorization, BackupRepositoryKey key, BackupCheckpoint checkpoint) throws IOException {
        try {
            Objects.requireNonNull(authorization); Objects.requireNonNull(key); checkpoint.check();
            var request = authorization.request();
            if (!(request.scope() instanceof BackupScope.Installation) && !request.scope().selectedRepositories().contains(key))
                throw failure("Git repository is outside the authorized capture scope");
            var route = route(key, request.profile() == BackupProfile.SELECTED_VERSION, checkpoint);
            var access = new Access(new RepositoryName(route.storage()), authorization.decisionId(), new AtomicBoolean(true));
            // Unlike the default storage factory, the secured public facade opens existing metadata only.
            var storage = repositories.open(access.storage(), access);
            try {
                checkpoint.check(); return new ReadSession(key, request, route.branch(), storage, access);
            } catch (IOException | RuntimeException problem) {
                access.active().set(false);
                try { storage.close(); } catch (RuntimeException cleanup) { problem.addSuppressed(cleanup); }
                throw problem;
            }
        } catch (IOException | RuntimeException problem) { throw sanitized(problem); }
    }

    private Route route(BackupRepositoryKey key, boolean selectedVersion, BackupCheckpoint checkpoint) throws IOException {
        boolean central = key.workspaceId() == null;
        String sql = central
                ? "select storage_repository_name,default_branch from system_repository where repository_id=?"
                : "select w.current_branch from user_workspace w join system_repository r on r.repository_id=w.source_repository_id where r.repository_id=? and w.workspace_id=?";
        try (var connection = database.getConnection()) {
            connection.setReadOnly(true); connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement(sql)) {
                statement.setMaxRows(2); statement.setQueryTimeout(60); statement.setString(1, key.repositoryId());
                if (!central) statement.setString(2, key.workspaceId());
                checkpoint.check();
                try (var row = statement.executeQuery()) {
                    if (!row.next()) throw failure("Captured repository routing is missing or inconsistent");
                    String storage = central ? row.getString(1) : DslGitRepositoryFactory.workspaceRepositoryName(key.workspaceId());
                    String branch = selectedVersion ? null : row.getString(central ? 2 : 1);
                    if (storage == null || storage.isBlank() || !storage.equals(storage.strip()) || storage.length() > 512
                            || row.next()) throw failure("Captured repository routing is missing or inconsistent");
                    if (!selectedVersion && (branch == null || branch.length() > 255 || !Repository.isValidRefName(Constants.R_HEADS + branch)))
                        throw failure("Captured repository has no valid current branch");
                    checkpoint.check(); return new Route(storage, branch);
                }
            } finally { connection.rollback(); }
        } catch (SQLException problem) { throw sanitized(problem); }
    }

    public static final class ReadSession implements AutoCloseable {
        private final BackupRepositoryKey key;
        private final BackupRequest request;
        private final String branch;
        private final AuthorizedRepositorySession<Access> storage;
        private final Access access;
        private ReadSession(BackupRepositoryKey key, BackupRequest request, String branch, AuthorizedRepositorySession<Access> storage, Access access) {
            this.key = key; this.request = request; this.branch = branch; this.storage = storage; this.access = access;
        }
        public BackupRepositoryKey key() { return key; }
        /** Selected-version capture has no dependency on the live branch. */
        public String branch() { return branch; }
        public Optional<GitTreeCapture> captureStand(GitTreeCapture.Limits limits, BackupCheckpoint checkpoint) throws IOException {
            try {
                requireOpen();
                if (request.profile().includesHistory()) throw failure("Git history requires a complete history adapter");
                check(checkpoint); String commit;
                if (request.time() instanceof BackupTime.SelectedVersion selected) commit = selected.commitsByRepository().get(key);
                else {
                    var ref = storage.repository().getRefDatabase().exactRef(Constants.R_HEADS + branch);
                    commit = ref == null || ref.getObjectId() == null ? null : ref.getObjectId().name();
                }
                check(checkpoint);
                return commit == null ? Optional.empty() : Optional.of(GitTreeCapture.capture(storage.repository(), commit, limits, checkpoint, this::requireOpen));
            } catch (IOException | RuntimeException problem) { throw sanitized(problem); }
        }
        private void requireOpen() throws IOException {
            if (!access.active().get()) throw failure("Git capture session is closed");
        }
        private void check(BackupCheckpoint checkpoint) throws IOException {
            requireOpen(); checkpoint.check(); requireOpen();
        }
        @Override public void close() throws IOException {
            if (!access.active().getAndSet(false)) return;
            try { storage.close(); } catch (RuntimeException problem) { throw sanitized(problem); }
        }
    }
    private record Route(String storage, String branch) { }
    private record Access(RepositoryName storage, String decision, AtomicBoolean active) { }
}
