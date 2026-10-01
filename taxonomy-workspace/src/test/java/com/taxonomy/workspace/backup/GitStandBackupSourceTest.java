package com.taxonomy.workspace.backup;

import com.taxonomy.backup.*;
import com.taxonomy.editor.EditorPersistenceFixture;
import com.taxonomy.workspace.service.RepositoryContext;
import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import org.eclipse.jgit.lib.*;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class GitStandBackupSourceTest {
    private static final BackupRepositoryKey KEY = new BackupRepositoryKey("repo-a", "private-a");
    private static final BackupCheckpoint CHECK = () -> { };
    private static final GitStandBackupSource.Limits LIMITS = new GitStandBackupSource.Limits(new GitTreeCapture.Limits(100, 1_000_000, 2_000_000), 100_000);
    private static final String DSL = "architecture.taxdsl";

    @Test void savedDraftSupersedesTheCommittedDocumentWithoutReadingOperationHistoryOrOtherTenants() throws Exception {
        try (var f = new Fixture()) {
            String commit = f.commit(Map.of(DSL, bytes("COMMITTED-OLD embedded-secret"), "run.sh", bytes("binary\0bytes")));
            f.saved(KEY, "draft", "SAVED-CURRENT embedded-secret", 4, commit, 2, null);
            f.saved(KEY, "other", "OTHER-BRANCH", 8, commit, 1, null);
            f.saved(new BackupRepositoryKey("repo-b", "private-a"), "draft", "FOREIGN-REPOSITORY", 8, commit, 1, null);
            f.saved(new BackupRepositoryKey("repo-a", "private-b"), "draft", "FOREIGN-WORKSPACE", 8, commit, 1, null);
            f.sql("drop table editor_operation"); f.sql("drop table editor_checkpoint");
            var stats = f.persistence.factory.getStatistics(); stats.setStatisticsEnabled(true); stats.clear();
            try (var stand = open(f, current(), LIMITS)) {
                assertThat(stand.files()).extracting(GitStandBackupSource.File::path).containsExactly(DSL, "run.sh");
                assertThat(text(stand, DSL)).isEqualTo("SAVED-CURRENT ");
                assertThat(text(stand, "run.sh")).isEqualTo("binary\0bytes");
                assertThat(file(stand, "run.sh").mode()).isEqualTo(FileMode.EXECUTABLE_FILE.getBits());
                assertThat(stand.documents()).isEmpty();
                assertThat(stand.state()).isEqualTo(new SnapshotContext.RepositoryState(Map.of("refs/heads/draft", commit), "refs/heads/draft",
                        Map.of("draft", new SnapshotContext.WorkingState(4, commit, 2)), Set.of(commit)));
            }
            assertThat(stats.getEntityInsertCount() + stats.getEntityUpdateCount() + stats.getEntityDeleteCount()).isZero();
        }
    }

    @Test void selectedVersionIgnoresLiveBranchDraftsAndDoesNotQueryEditorTables() throws Exception {
        try (var f = new Fixture()) {
            String selected = f.commit(Map.of(DSL, bytes("SELECTED embedded-secret")));
            f.commit(Map.of(DSL, bytes("TODAY")));
            f.sql("drop table editor_workspace");
            f.sql("update user_workspace set current_branch=null");
            try (var stand = open(f, selected(selected), LIMITS)) {
                assertThat(text(stand, DSL)).isEqualTo("SELECTED ");
                assertThat(stand.state()).isEqualTo(new SnapshotContext.RepositoryState(Map.of(), null, Map.of(), Set.of(selected)));
                assertThat(stand.documents()).containsExactly(new BackupDocumentReference(KEY, selected, DSL, hash(bytes("SELECTED embedded-secret"))));
            }
        }
    }

    @Test void projectsEveryTaxdslFileAndRetainsOriginalProofOnlyForUnsupersededDocuments() throws Exception {
        try (var f = new Fixture()) {
            String commit = f.commit(Map.of(DSL, bytes("CURRENT embedded-secret"), "portfolio.taxdsl", bytes("PORTFOLIO embedded-secret")));
            f.saved(KEY, "draft", "CURRENT embedded-secret", 2, commit, 2, null);
            try (var stand = open(f, current(), LIMITS)) {
                assertThat(text(stand, DSL)).isEqualTo("CURRENT "); assertThat(text(stand, "portfolio.taxdsl")).isEqualTo("PORTFOLIO ");
                assertThat(stand.documents()).containsExactlyInAnyOrder(new BackupDocumentReference(KEY, commit, DSL, hash(bytes("CURRENT embedded-secret"))),
                        new BackupDocumentReference(KEY, commit, "portfolio.taxdsl", hash(bytes("PORTFOLIO embedded-secret"))));
            }
        }
    }

    @Test void anUnbornBranchStillExportsItsSavedDraftWithoutAnotherBranchesContent() throws Exception {
        try (var f = new Fixture()) {
            f.commit(Map.of(DSL, bytes("OTHER-BRANCH"))); f.sql("update user_workspace set current_branch='unborn'");
            f.saved(KEY, "unborn", "SAVED-UNBORN", 1, null, 0, null);
            try (var stand = open(f, current(), LIMITS)) {
                assertThat(text(stand, DSL)).isEqualTo("SAVED-UNBORN"); assertThat(stand.documents()).isEmpty();
                assertThat(stand.state().refs()).isEmpty(); assertThat(stand.state().requiredCommits()).isEmpty();
                assertThat(stand.state().symbolicHead()).isEqualTo("refs/heads/unborn");
            }
        }
    }

    @Test void emptyUnbornBranchDoesNotInventAnArchitectureDocument() throws Exception {
        try (var f = new Fixture()) {
            f.commit(Map.of(DSL, bytes("OTHER"))); f.sql("update user_workspace set current_branch='unborn'");
            try (var stand = open(f, current(), LIMITS)) { assertThat(stand.files()).isEmpty(); assertThat(stand.state().workingStates()).isEmpty(); }
        }
    }

    @Test void currentCentralRepositoryDoesNotConsumeAPrivateDraft() throws Exception {
        try (var f = new Fixture()) {
            String commit = f.commit("central-a", Map.of(DSL, bytes("CENTRAL")));
            f.saved(KEY, "draft", "PRIVATE", 1, commit, 0, null);
            var key = new BackupRepositoryKey("repo-a", null);
            var auth = authorized(new BackupRequest(BackupProfile.CURRENT_STATE, new BackupScope.Repositories(Map.of("repo-a", Set.of())), new BackupTime.Current(), GitRepresentation.NONE, SecretsSelection.EXCLUDE));
            try (var stand = f.source().open(auth, key, LIMITS, CHECK)) {
                assertThat(stand).isNotNull(); assertThat(text(stand, DSL)).isEqualTo("CENTRAL"); assertThat(stand.state().workingStates()).isEmpty();
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"pending", "moved", "invalid-revision", "wrong-scope", "wrong-frame", "checkpoint-content", "duplicate"})
    void inconsistentSavedEvidenceFailsClosed(String problem) throws Exception {
        try (var f = new Fixture()) {
            String commit = f.commit(Map.of(DSL, bytes("COMMITTED")));
            f.saved(KEY, "draft", "SAVED", 3, commit, 1, problem.equals("pending") ? UUID.randomUUID().toString() : null);
            switch (problem) {
                case "moved" -> f.commit(Map.of(DSL, bytes("MOVED")));
                case "invalid-revision" -> f.sql("update editor_workspace set checkpoint_revision=4");
                case "wrong-scope" -> f.sql("update editor_workspace set scope_id=?", "f".repeat(64));
                case "wrong-frame" -> f.sql("update editor_workspace set dsl='2:UNKNOWN'");
                case "checkpoint-content" -> f.sql("update editor_workspace set checkpoint_revision=3");
                case "duplicate" -> f.sql("insert into editor_workspace(scope_id,repository_id,workspace_id,branch,dsl,semantic_revision,checkpoint_commit,checkpoint_revision,pending_checkpoint,row_version) select ?,repository_id,workspace_id,branch,dsl,semantic_revision,checkpoint_commit,checkpoint_revision,pending_checkpoint,row_version from editor_workspace", "d".repeat(64));
            }
            assertThatThrownBy(() -> f.source().open(current(), KEY, LIMITS, CHECK)).isInstanceOf(IOException.class).hasNoCause();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"content", "revision", "removed", "checkpoint"})
    void savedEvidenceCannotChangeBetweenInspectionAndCopy(String change) throws Exception {
        try (var f = new Fixture()) {
            String commit = f.commit(Map.of(DSL, bytes("OLD"))); f.saved(KEY, "draft", "CURRENT", 3, commit, 1, null);
            try (var stand = open(f, current(), LIMITS)) {
                switch (change) {
                    case "content" -> f.sql("update editor_workspace set dsl='1:CHANGED'");
                    case "revision" -> f.sql("update editor_workspace set semantic_revision=4");
                    case "removed" -> f.sql("delete from editor_workspace");
                    case "checkpoint" -> f.sql("update editor_workspace set pending_checkpoint=?", UUID.randomUUID().toString());
                }
                var output = new ByteArrayOutputStream();
                assertThatThrownBy(() -> stand.copy(file(stand, DSL), output, CHECK)).isInstanceOf(IOException.class);
                assertThat(output.size()).isZero();
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"document", "projection", "total", "files", "collision", "utf8"})
    void boundsAndValidatesEffectivePayloads(String problem) throws Exception {
        try (var f = new Fixture()) {
            String commit = f.commit(Map.of(problem.equals("collision") ? "ARCHITECTURE.taxdsl" : problem.equals("files") ? "other.txt" : DSL,
                    problem.equals("utf8") ? new byte[]{(byte)0xc3, (byte)0x28} : bytes("base")));
            var limits = new GitStandBackupSource.Limits(new GitTreeCapture.Limits(problem.equals("files") ? 1 : 100, 1_000_000, problem.equals("total") ? 7 : 2_000_000), 20);
            if (Set.of("document", "total", "files", "collision").contains(problem)) f.saved(KEY, "draft", problem.equals("document") ? "ü".repeat(15) : "saved-long", 2, commit, 1, null);
            var source = problem.equals("projection") ? f.source(s -> s.repeat(10)) : f.source();
            assertThatThrownBy(() -> source.open(current(), KEY, limits, CHECK)).isInstanceOf(IOException.class);
        }
    }

    @Test void aProjectorCannotSilentlyChangeTheInspectedPayloadAndItsDiagnosticsArePrivate() throws Exception {
        try (var f = new Fixture()) {
            f.commit(Map.of(DSL, bytes("CURRENT"))); var calls = new AtomicInteger();
            try (var stand = f.source(s -> calls.incrementAndGet() == 1 ? s : "CHANGED").open(current(), KEY, LIMITS, CHECK)) {
                assertThat(stand).isNotNull(); var output = new ByteArrayOutputStream();
                assertThatThrownBy(() -> stand.copy(file(stand, DSL), output, CHECK)).isInstanceOf(IOException.class); assertThat(output.size()).isZero();
            }
            assertThatThrownBy(() -> f.source(s -> { throw new IllegalArgumentException("PRIVATE-PROVIDER-CONTENT"); }).open(current(), KEY, LIMITS, CHECK))
                    .isInstanceOf(IOException.class).hasNoCause().hasMessageNotContaining("PRIVATE-PROVIDER-CONTENT");
        }
    }

    @Test void reloadingTheSavedDocumentAfterAnotherProjectionMustStillMatchItsInitialProof() throws Exception {
        try (var f = new Fixture()) {
            String commit = f.commit(Map.of("0.taxdsl", bytes("FIRST"), DSL, bytes("OLD"))); f.saved(KEY, "draft", "CURRENT", 3, commit, 1, null);
            var source = f.source(dsl -> {
                if (dsl.equals("FIRST")) try { f.sql("update editor_workspace set dsl='1:CHANGED'"); } catch (Exception failure) { throw new IllegalStateException(failure); }
                return dsl;
            });
            assertThatThrownBy(() -> { try (var ignored = source.open(current(), KEY, LIMITS, CHECK)) { } }).isInstanceOf(IOException.class);
        }
    }

    @Test void closedOrForeignFileEvidenceCannotBeCopiedAndCallerOwnsOutput() throws Exception {
        try (var f = new Fixture()) {
            f.commit(Map.of(DSL, bytes("CURRENT"))); var stand = open(f, current(), LIMITS); var file = file(stand, DSL);
            var output = new ByteArrayOutputStream() { @Override public void close() { fail("caller owns output"); } };
            stand.copy(file, output, CHECK); assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("CURRENT");
            assertThatThrownBy(() -> stand.copy(new GitStandBackupSource.File(DSL, file.mode(), file.length(), "0".repeat(64)), output, CHECK)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> stand.files().clear()).isInstanceOf(UnsupportedOperationException.class);
            stand.close(); stand.close(); assertThatThrownBy(() -> stand.copy(file, output, CHECK)).isInstanceOf(IOException.class);
        }
    }

    @Test void generatedEntriesContainOnlyProjectedBytesAndRequireAnHonestReceipt() throws Exception {
        try (var f = new Fixture()) {
            f.commit(Map.of(DSL, bytes("CURRENT embedded-secret")));
            try (var stand = open(f, current(), LIMITS)) {
                var sink = new Sink(); var entry = stand.writeFile(file(stand, DSL), "files/current.taxdsl", sink);
                assertThat(sink.bytes.toString(StandardCharsets.UTF_8)).isEqualTo("CURRENT "); assertThat(entry.sha256()).isEqualTo(hash(bytes("CURRENT ")));
                assertThatThrownBy(() -> stand.writeFile(file(stand, DSL), "files/current.taxdsl", new Sink() {
                    @Override public BackupEntry writeGenerated(String path, EntryWriter producer) { return entry; }
                })).isInstanceOf(IOException.class);
                assertThatThrownBy(() -> stand.writeFile(file(stand, DSL), "files/current.taxdsl", new Sink() {
                    @Override public BackupEntry writeGenerated(String path, EntryWriter producer) throws IOException {
                        var receipt = super.writeGenerated(path, producer); try { producer.write(OutputStream.nullOutputStream()); } catch (IOException ignored) { } return receipt;
                    }
                })).isInstanceOf(IOException.class);
            }
        }
    }

    @Test void copyingChecksCancellationAndSessionClosureBetweenDocumentChunks() throws Exception {
        try (var f = new Fixture()) {
            f.commit(Map.of(DSL, bytes("x".repeat(30_000))));
            try (var stand = open(f, current(), LIMITS)) {
                var output = new ByteArrayOutputStream();
                assertThatThrownBy(() -> stand.copy(file(stand, DSL), output, () -> { if (output.size() > 0) stand.close(); })).isInstanceOf(IOException.class);
                assertThat(output.size()).isPositive().isLessThan(30_000);
            }
            try {
                assertThatThrownBy(() -> f.source().open(current(), KEY, LIMITS, () -> { throw new InterruptedIOException("private"); }))
                        .isInstanceOf(InterruptedIOException.class).hasMessageNotContaining("private");
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally { Thread.interrupted(); }
        }
    }

    @Test void jdbcCleanupCannotHideCancellationOrExposePrivateProviderDiagnostics() throws Exception {
        try (var f = new Fixture()) {
            String commit = f.commit(Map.of(DSL, bytes("OLD"))); f.saved(KEY, "draft", "CURRENT", 3, commit, 1, null);
            boolean[] querying = {false};
            DataSource failing = proxy(DataSource.class, f.database, (target, method, args) -> {
                Object result = invoke(target, method, args);
                if (!method.getName().equals("getConnection")) return result;
                return proxy(Connection.class, result, (connection, call, parameters) -> {
                    if (call.getName().equals("rollback")) throw new SQLException("PRIVATE-ROLLBACK-CREDENTIALS");
                    Object response = invoke(connection, call, parameters);
                    if (!call.getName().equals("prepareStatement")) return response;
                    return proxy(PreparedStatement.class, response, (statement, query, values) -> {
                        Object rows = invoke(statement, query, values); if (query.getName().equals("executeQuery")) querying[0] = true; return rows;
                    });
                });
            });
            var source = new GitStandBackupSource(failing, f.repositories, dsl -> dsl);
            try {
                assertThatThrownBy(() -> source.open(current(), KEY, LIMITS, () -> { if (querying[0]) throw new InterruptedIOException("PRIVATE-CANCEL"); }))
                        .isInstanceOf(InterruptedIOException.class).hasNoCause().hasMessageNotContaining("PRIVATE");
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally { Thread.interrupted(); }
        }
    }

    @FunctionalInterface private interface Invocation { Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable; }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, Object target, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (ignored, method, args) -> invocation.invoke(target, method, args));
    }
    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private static GitStandBackupSource.Stand open(Fixture f, AuthorizedBackupRequest auth, GitStandBackupSource.Limits limits) throws IOException {
        var stand = f.source().open(auth, KEY, limits, CHECK); assertThat(stand).as("a complete current/selected file view must be captured").isNotNull(); return stand;
    }
    private static GitStandBackupSource.File file(GitStandBackupSource.Stand stand, String path) { return stand.files().stream().filter(f -> f.path().equals(path)).findFirst().orElseThrow(); }
    private static String text(GitStandBackupSource.Stand stand, String path) throws IOException { var out = new ByteArrayOutputStream(); stand.copy(file(stand, path), out, CHECK); return out.toString(StandardCharsets.UTF_8); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String hash(byte[] value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); } catch (Exception impossible) { throw new AssertionError(impossible); } }
    private static AuthorizedBackupRequest current() { return authorized(new BackupRequest(BackupProfile.CURRENT_STATE, new BackupScope.Workspace(KEY.repositoryId(), KEY.workspaceId()), new BackupTime.Current(), GitRepresentation.NONE, SecretsSelection.EXCLUDE)); }
    private static AuthorizedBackupRequest selected(String commit) { return authorized(new BackupRequest(BackupProfile.SELECTED_VERSION, new BackupScope.Workspace(KEY.repositoryId(), KEY.workspaceId()), new BackupTime.SelectedVersion(Map.of(KEY, commit)), GitRepresentation.NONE, SecretsSelection.EXCLUDE)); }
    private static AuthorizedBackupRequest authorized(BackupRequest request) { return new AuthorizedBackupRequest(request, PrincipalId.create(), "checked", Instant.now(), EnumSet.allOf(BackupCapability.class)); }
    private static class Sink implements ComponentSink {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        @Override public BackupEntry write(String path, InputStream input) throws IOException { input.transferTo(bytes); return new BackupEntry(path, bytes.size(), hash(bytes.toByteArray())); }
        @Override public BackupEntry writeGenerated(String path, EntryWriter producer) throws IOException { producer.write(bytes); return new BackupEntry(path, bytes.size(), hash(bytes.toByteArray())); }
    }
    private static final class Fixture implements AutoCloseable {
        final JDBCDataSource database = new JDBCDataSource(); final EditorPersistenceFixture persistence; final ExistingGitBackupRepositories repositories;
        Fixture() throws Exception {
            String url = "jdbc:hsqldb:mem:git-stand-" + UUID.randomUUID() + ";hsqldb.tx=mvcc"; database.setUrl(url); database.setUser("SA"); database.setPassword(""); persistence = new EditorPersistenceFixture(url);
            sql("create table system_repository(repository_id varchar(255) primary key,storage_repository_name varchar(255),default_branch varchar(255))");
            sql("create table user_workspace(workspace_id varchar(255) primary key,source_repository_id varchar(255),current_branch varchar(255))");
            sql("insert into system_repository values('repo-a','central-a','draft')"); sql("insert into user_workspace values('private-a','repo-a','draft')");
            repositories = new ExistingGitBackupRepositories(database, persistence.factory);
        }
        GitStandBackupSource source() { return source(s -> s.replace("embedded-secret", "")); }
        GitStandBackupSource source(BackupDocumentProjector projector) { return new GitStandBackupSource(database, repositories, projector); }
        String commit(Map<String, byte[]> files) throws Exception { return commit("ws-private-a", files); }
        String commit(String storage, Map<String, byte[]> files) throws Exception {
            try (var source = new com.taxonomy.workspace.storage.DslGitRepository(new DefaultHibernateRepositoryFactory(persistence.factory), storage); var insert = source.getGitRepository().newObjectInserter()) {
                var repository = source.getGitRepository();
                var tree = new TreeFormatter();
                for (var e : new TreeMap<>(files).entrySet()) tree.append(e.getKey(), e.getKey().equals("run.sh") ? FileMode.EXECUTABLE_FILE : FileMode.REGULAR_FILE, insert.insert(Constants.OBJ_BLOB, e.getValue()));
                var commit = new CommitBuilder(); commit.setTreeId(insert.insert(tree)); var prior = repository.exactRef("refs/heads/draft"); if (prior != null) commit.setParentId(prior.getObjectId());
                var actor = new PersonIdent("source", "source@example.test"); commit.setAuthor(actor); commit.setCommitter(actor); commit.setMessage("private commit metadata");
                var id = insert.insert(commit); insert.flush(); var ref = repository.updateRef("refs/heads/draft"); ref.setNewObjectId(id); ref.setForceUpdate(true);
                assertThat(ref.update()).isIn(RefUpdate.Result.NEW, RefUpdate.Result.FAST_FORWARD, RefUpdate.Result.FORCED, RefUpdate.Result.NO_CHANGE); return id.name();
            }
        }
        void saved(BackupRepositoryKey key, String branch, String dsl, long revision, String commit, long checkpointRevision, String pending) throws Exception {
            String scope = RepositoryContext.workspace(key.repositoryId(), key.workspaceId(), branch, "actor").repositoryWorkspaceScopeKey();
            sql("insert into editor_workspace(scope_id,repository_id,workspace_id,branch,dsl,semantic_revision,checkpoint_commit,checkpoint_revision,pending_checkpoint,row_version) values(?,?,?,?,?,?,?,?,?,0)",scope,key.repositoryId(),key.workspaceId(),branch,"1:"+dsl,revision,commit,checkpointRevision,pending);
        }
        void sql(String sql, Object... params) throws Exception { try (var c = database.getConnection(); var s = c.prepareStatement(sql)) { for(int i=0;i<params.length;i++) s.setObject(i+1,params[i]); s.executeUpdate(); } }
        @Override public void close() { persistence.close(); }
    }
}
