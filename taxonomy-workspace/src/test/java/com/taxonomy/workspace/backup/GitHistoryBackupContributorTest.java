package com.taxonomy.workspace.backup;

import com.taxonomy.backup.*;
import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.workspace.storage.DslGitRepository;
import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.*;

class GitHistoryBackupContributorTest {
    private static final BackupRepositoryKey KEY = new BackupRepositoryKey("repo-a", "private-a");
    private static final BackupCheckpoint CHECK = () -> { };
    private static final GitStandBackupSource.Limits WORKING = new GitStandBackupSource.Limits(new GitTreeCapture.Limits(100, 1_000_000, 2_000_000), 100_000);
    @TempDir Path temporary;

    @ParameterizedTest @EnumSource(value = GitRepresentation.class, names = {"BUNDLE", "BARE", "WORKTREE"})
    void actualNativeHistoryPreservesAncestorsDatabaseOnlyRootsAndUnprojectedSavedWork(GitRepresentation representation) throws Exception {
        try (var f = new WorkspaceGitHistoryReferencesTest.Fixture()) {
            String first = f.commit("ws-private-a", "ORIGINAL embedded-secret");
            String head = f.commit("ws-private-a", "COMMITTED embedded-secret");
            String orphan = f.orphan("ws-private-a", "DATABASE-ONLY embedded-secret");
            try (var source = new DslGitRepository(new DefaultHibernateRepositoryFactory(f.base.persistence.factory), "ws-private-a")) {
                var update = source.getGitRepository().updateRef("HEAD");
                var original = source.getGitRepository().exactRef("HEAD");
                update.setExpectedOldObjectId(original == null || original.getObjectId() == null ? ObjectId.zeroId() : original.getObjectId());
                update.setRefLogIdent(new PersonIdent("Fixture Author", "fixture@example.invalid"));
                update.setRefLogMessage("Select existing draft for history export fixture", false);
                assertThat(update.link("refs/heads/draft")).isIn(RefUpdate.Result.NEW, RefUpdate.Result.FORCED, RefUpdate.Result.NO_CHANGE);
            }
            f.base.saved(KEY, "draft", "RAW SAVED embedded-secret", 3, head, 1, null);
            f.base.sql("insert into architecture_commit_index values(1,'repo-a','private-a',?)", orphan);
            var contributor = contributor(f, GitHistoryCapture.Limits.defaults()); var auth = authorized(representation);
            var plan = contributor.inspect(auth, Set.of(KEY), CHECK);
            assertThat(plan).singleElement().satisfies(repository -> {
                assertThat(repository.captured().requiredCommits()).contains(first, head, orphan);
                assertThat(repository.exportedHead()).isEqualTo(head);
            });
            var snapshot = snapshot(auth, plan); var sink = new FilesSink(temporary.resolve("archive"));
            contributor.write(snapshot, sink);
            assertThat(contributor.documents(snapshot, CHECK)).hasSize(3);
            String archiveId = plan.getFirst().archiveId();
            Path source = sink.root.resolve("repositories/" + archiveId);
            Path nativeGit;
            if (representation == GitRepresentation.BUNDLE) {
                nativeGit = temporary.resolve("clone.git");
                run("git", "clone", "--bare", source.resolve("history.bundle").toString(), nativeGit.toString());
            } else nativeGit = representation == GitRepresentation.WORKTREE ? source.resolve(".git") : source;
            run("git", "--git-dir=" + nativeGit, "fsck", "--full");
            assertThat(run("git", "--git-dir=" + nativeGit, "show", first + ":architecture.taxdsl")).isEqualTo("ORIGINAL embedded-secret");
            assertThat(run("git", "--git-dir=" + nativeGit, "show", orphan + ":architecture.taxdsl")).isEqualTo("DATABASE-ONLY embedded-secret");
            assertThat(run("git", "--git-dir=" + nativeGit, "rev-parse", head + "^").strip()).isEqualTo(first);
            if (representation == GitRepresentation.WORKTREE)
                assertThat(Files.readString(source.resolve("architecture.taxdsl"))).isEqualTo("RAW SAVED embedded-secret");
            var records = Files.readAllLines(sink.root.resolve("data/workspace/git-commit-references.ndjson"));
            assertThat(PortableRows.json().readTree(records.getFirst()).get("kind").asText()).isEqualTo("git-commit-references");
            assertThat(records.stream().skip(1).map(line -> {
                try { return PortableRows.json().readValue(line, WorkspaceGitHistoryReferences.CommitReference.class); }
                catch (IOException error) { throw new UncheckedIOException(error); }
            })).anySatisfy(proof -> {
                assertThat(proof.sourceId()).isEqualTo(new SourceRecordId("workspace.commit-index", "1"));
                assertThat(proof.repository()).isEqualTo(KEY); assertThat(proof.commit()).isEqualTo(orphan);
            });
            var bodies = Files.readAllLines(sink.root.resolve("data/workspace/git-working-copy-proofs.ndjson"));
            assertThat(bodies).hasSize(2);
            assertThat(PortableRows.json().readTree(bodies.get(1)).get("sha256").asText()).isEqualTo(hash("RAW SAVED embedded-secret".getBytes(StandardCharsets.UTF_8)));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"refs", "saved-body", "database-reference"})
    void sourceMovementAfterInventoryCannotProduceACompleteHistoryArchive(String change) throws Exception {
        try (var f = new WorkspaceGitHistoryReferencesTest.Fixture()) {
            String head = f.commit("ws-private-a", "COMMITTED");
            f.base.saved(KEY, "draft", "SAVED", 3, head, 1, null);
            var auth = authorized(GitRepresentation.BARE); var contributor = contributor(f, GitHistoryCapture.Limits.defaults());
            var snapshot = snapshot(auth, contributor.inspect(auth, Set.of(KEY), CHECK));
            if (change.equals("refs")) f.commit("ws-private-a", "MOVED");
            if (change.equals("saved-body")) f.base.sql("update editor_workspace set dsl='1:MOVED WITHOUT REVISION'");
            if (change.equals("database-reference")) f.base.sql("update user_workspace set current_commit=?", head);
            var sink = new FilesSink(temporary.resolve("archive"));
            assertThatThrownBy(() -> contributor.write(snapshot, sink)).isInstanceOf(IOException.class);
            assertThat(sink.entries).isEmpty();
            assertThatThrownBy(() -> contributor.documents(snapshot, CHECK)).isInstanceOf(IOException.class);
        }
    }

    @Test void objectBudgetAppliesAcrossAllExplicitRepositories() throws Exception {
        try (var f = new WorkspaceGitHistoryReferencesTest.Fixture()) {
            f.commit("central-a", "CENTRAL"); f.commit("ws-private-a", "PRIVATE");
            var limits = new GitHistoryCapture.Limits(100, 5, 1_000_000, 2_000_000, 100);
            var auth = new AuthorizedBackupRequest(new BackupRequest(BackupProfile.REPOSITORY_HISTORY,
                    new BackupScope.Repositories(Map.of("repo-a", Set.of("private-a"))), new BackupTime.History(),
                    GitRepresentation.BARE, SecretsSelection.EXCLUDE), PrincipalId.create(), "checked", Instant.now(), EnumSet.allOf(BackupCapability.class));
            assertThatThrownBy(() -> contributor(f, limits).inspect(auth, auth.request().scope().selectedRepositories(), CHECK)).isInstanceOf(IOException.class);
        }
    }

    @Test void documentVisitConsumesOneSharedBudgetAcrossExplicitRepositories() throws Exception {
        try (var f = new WorkspaceGitHistoryReferencesTest.Fixture()) {
            f.commit("central-a", "CENTRAL"); f.commit("ws-private-a", "PRIVATE");
            var auth = new AuthorizedBackupRequest(new BackupRequest(BackupProfile.REPOSITORY_HISTORY,
                    new BackupScope.Repositories(Map.of("repo-a", Set.of("private-a"))), new BackupTime.History(),
                    GitRepresentation.BARE, SecretsSelection.EXCLUDE), PrincipalId.create(), "checked", Instant.now(), EnumSet.allOf(BackupCapability.class));
            var contributor = contributor(f, GitHistoryCapture.Limits.defaults());
            var snapshot = snapshot(auth, contributor.inspect(auth, auth.request().scope().selectedRepositories(), CHECK));
            var count = new java.util.concurrent.atomic.AtomicInteger();
            assertThatThrownBy(() -> contributor.visitDocuments(snapshot, new GitHistoryCapture.DocumentLimits(1,100,100), CHECK,
                    (reference, bytes) -> count.incrementAndGet())).isInstanceOf(IOException.class);
            assertThat(count).hasValue(1);
            var bytes = new java.util.concurrent.atomic.AtomicInteger();
            assertThatThrownBy(() -> contributor.visitDocuments(snapshot, new GitHistoryCapture.DocumentLimits(2,100,13), CHECK,
                    (reference, body) -> bytes.addAndGet(body.length))).isInstanceOf(IOException.class);
            assertThat(bytes.get()).isLessThanOrEqualTo(13);
            var sources = new HashSet<BackupRepositoryKey>();
            var result = contributor.visitDocuments(snapshot, new GitHistoryCapture.DocumentLimits(2,100,14), CHECK,
                    (reference, body) -> sources.add(reference.repository()));
            assertThat(sources).containsExactlyInAnyOrderElementsOf(auth.request().scope().selectedRepositories());
            assertThat(result).isEqualTo(new GitHistoryCapture.DocumentVisit(2,14));
        }
    }

    @Test void originalDocumentVisitorNeverMislabelsSavedDatabaseDslAsACommit() throws Exception {
        try (var f = new WorkspaceGitHistoryReferencesTest.Fixture()) {
            String commit=f.commit("ws-private-a", "ORIGINAL COMMIT");
            f.base.saved(KEY,"draft","SAVED DATABASE BODY",3,commit,1,null);
            var contributor=contributor(f,GitHistoryCapture.Limits.defaults());
            var auth=authorized(GitRepresentation.WORKTREE);
            var snapshot=snapshot(auth,contributor.inspect(auth,Set.of(KEY),CHECK));
            var result=contributor.visitDocuments(snapshot,new GitHistoryCapture.DocumentLimits(1,100,100),CHECK,(reference,body)->{
                assertThat(reference.repository()).isEqualTo(KEY);
                assertThat(reference.commit()).isEqualTo(commit);
                assertThat(new String(body,StandardCharsets.UTF_8)).isEqualTo("ORIGINAL COMMIT");
            });
            assertThat(result.documents()).isEqualTo(1);
            assertThatThrownBy(()->contributor.visitDocuments(snapshot,new GitHistoryCapture.DocumentLimits(1,100,100),CHECK,(reference,body)->{
                try { f.base.sql("update editor_workspace set dsl='1:CHANGED WITHOUT REVISION'"); }
                catch (Exception failure) { throw new IOException(failure); }
            })).isInstanceOf(IOException.class);
        }
    }

    private static GitHistoryBackupContributor contributor(WorkspaceGitHistoryReferencesTest.Fixture f, GitHistoryCapture.Limits limits) {
        return new GitHistoryBackupContributor(f.base.repositories, f.base.source(raw -> { throw new AssertionError("History must never project documents"); }),
                f.source(), limits, WORKING);
    }
    private static AuthorizedBackupRequest authorized(GitRepresentation representation) {
        return new AuthorizedBackupRequest(new BackupRequest(BackupProfile.REPOSITORY_HISTORY, new BackupScope.Workspace("repo-a", "private-a"),
                new BackupTime.History(), representation, SecretsSelection.EXCLUDE), PrincipalId.create(), "checked", Instant.now(), EnumSet.allOf(BackupCapability.class));
    }
    private static SnapshotContext snapshot(AuthorizedBackupRequest auth, List<BackupManifest.Repository> plan) {
        return new SnapshotContext(BackupId.create(), auth, Instant.EPOCH, Instant.EPOCH, 1,
                plan.stream().collect(Collectors.toMap(BackupManifest.Repository::id, BackupManifest.Repository::captured)),
                Map.of(new BackupComponentId("workspace"), 1),
                plan.stream().collect(Collectors.toMap(BackupManifest.Repository::id, BackupManifest.Repository::archiveId)),
                plan.stream().filter(repository -> repository.exportedHead() != null).collect(Collectors.toMap(BackupManifest.Repository::id, BackupManifest.Repository::exportedHead)));
    }
    private static String run(String... arguments) throws Exception {
        var process = new ProcessBuilder(arguments).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as(new String(output, StandardCharsets.UTF_8)).isZero();
        return new String(output, StandardCharsets.UTF_8);
    }
    private static String hash(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception e) { throw new AssertionError(e); } }
    private static final class FilesSink implements ComponentSink {
        final Path root; final List<BackupEntry> entries = new ArrayList<>();
        FilesSink(Path root) { this.root = root; }
        @Override public BackupEntry write(String path, InputStream input) throws IOException {
            byte[] bytes = input.readAllBytes(); var entry = new BackupEntry(path, bytes.length, hash(bytes));
            Path file = root.resolve(path); Files.createDirectories(file.getParent());
            if (path.endsWith("/")) Files.createDirectory(file); else Files.write(file, bytes, StandardOpenOption.CREATE_NEW);
            entries.add(entry); return entry;
        }
        @Override public BackupEntry writeGenerated(String path, EntryWriter writer) throws IOException {
            var output = new ByteArrayOutputStream(); writer.write(output); return write(path, new ByteArrayInputStream(output.toByteArray()));
        }
    }
}
