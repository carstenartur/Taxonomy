package com.taxonomy.workspace.service;

import com.taxonomy.dsl.merge.TaxDslMergeResult;
import com.taxonomy.versioning.service.SemanticGitMergeService;
import com.taxonomy.workspace.model.*;
import com.taxonomy.workspace.repository.*;
import com.taxonomy.workspace.signing.GitCommitSignatureService;
import com.taxonomy.workspace.signing.SignatureStatus;
import com.taxonomy.workspace.signing.SigningTestKeys;
import com.taxonomy.workspace.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GitNativeSyncSnapshotRaceTest {
    private static final WorkspaceArchitectureVersionPort DIRECT = new WorkspaceArchitectureVersionPort() {
        public <T> T version(RepositoryContext context, String rationale, GitAction<T> action) throws IOException {
            return action.run();
        }
    };
    static Stream<Arguments> races() {
        return Stream.of(false, true).flatMap(publish -> Stream.of(false, true).flatMap(source ->
                Stream.of(false, true).map(noOp -> Arguments.of(publish, source, noOp))));
    }
    @ParameterizedTest
    @MethodSource("races")
    void rejectsConcurrentSourceAndWorkspaceHeadsIncludingSemanticNoOps(boolean publish, boolean changeSource, boolean noOp) throws Exception {
        try (var fixture = new Fixture()) {
            String beforeSource = fixture.source.getHeadCommit("draft");
            String beforeLocal = fixture.local.getHeadCommit("main");
            String beforeBase = fixture.local.getHeadCommit("sync-base");
            when(fixture.merger.mergeContent(anyString(), anyString(), anyString())).thenAnswer(call -> {
                (changeSource ? fixture.source : fixture.local).commitDsl(changeSource ? "draft" : "main", "winner", "other", "Concurrent writer");
                return new TaxDslMergeResult(noOp ? (publish ? "remote" : "local") : "merged", List.of());
            });
            assertThrows(BranchHeadConflictException.class, () -> fixture.sync(publish));
            assertEquals(changeSource ? "winner" : "remote", fixture.source.getDslAtHead("draft"));
            assertEquals(changeSource ? "local" : "winner", fixture.local.getDslAtHead("main"));
            assertEquals(beforeBase, fixture.local.getHeadCommit("sync-base"));
            if (changeSource) assertEquals(beforeLocal, fixture.local.getHeadCommit("main"));
            else assertEquals(beforeSource, fixture.source.getHeadCommit("draft"));
            verify(fixture.rows, never()).save(any());
            verify(fixture.portfolio, never()).materializePortfolio(anyString(), anyString(), any());
            assertNull(fixture.workspace.getLastIntegratedCommit());
        }
    }
    @Test
    void sourceMovementAfterLocalWriteCannotBeRecordedAsIntegrated() throws Exception {
        try (var fixture = new Fixture()) {
            String captured = fixture.source.getHeadCommit("draft");
            when(fixture.merger.mergeContent(anyString(), anyString(), anyString()))
                    .thenReturn(new TaxDslMergeResult("merged", List.of()));
            doAnswer(call -> {
                fixture.source.commitDsl("draft", "later", "other", "After local write");
                return null;
            }).when(fixture.portfolio).materializePortfolio(anyString(), anyString(), any());
            fixture.sync(false);
            assertEquals(captured, fixture.workspace.getLastIntegratedCommit());
            assertEquals(captured, fixture.workspace.getLastFetchedCommit());
            assertEquals("later", fixture.source.getDslAtHead("draft"));
            assertEquals("merged", fixture.local.getDslAtHead("main"));
            var evidence = fixture.local.getProvenance(fixture.local.getHeadCommit("main"));
            assertEquals("pull", evidence.operation());
            assertEquals("source", evidence.sources().getFirst().repositoryId());
            assertEquals("draft", evidence.sources().getFirst().branch());
            assertEquals(captured, evidence.sources().getFirst().commitId());
        }
    }

    @Test
    void publicationAndWorkspaceIntegrationRecordTheirExactIndependentSources() throws Exception {
        try (var fixture = new Fixture()) {
            String localHead = fixture.local.getHeadCommit("main");
            String previousSource = fixture.source.getHeadCommit("draft");
            fixture.workspace.setLastIntegratedCommit(previousSource);
            when(fixture.merger.mergeContent(anyString(), anyString(), anyString()))
                    .thenReturn(new TaxDslMergeResult("merged", List.of()));
            fixture.sync(true);
            String sourceHead = fixture.source.getHeadCommit("draft");
            var published = fixture.source.getProvenance(sourceHead);
            assertEquals("publish", published.operation());
            assertEquals("workspace:workspace", published.sources().getFirst().repositoryId());
            assertEquals("main", published.sources().getFirst().branch());
            assertEquals(localHead, published.sources().getFirst().commitId());
            assertEquals(previousSource, published.sources().getFirst().baseCommitId());
            var integrated = fixture.local.getProvenance(fixture.local.getHeadCommit("main"));
            assertEquals("pull", integrated.operation());
            assertEquals(sourceHead, integrated.sources().getFirst().commitId());
            assertEquals(sourceHead, fixture.workspace.getLastIntegratedCommit());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void matchingSnapshotsDoNotCreateContentCommitsSolelyForProvenance(boolean publish) throws Exception {
        try (var fixture = new Fixture()) {
            fixture.local.commitDsl("main", "remote", "owner", "Already matches source");
            String sourceHead = fixture.source.getHeadCommit("draft");
            String localHead = fixture.local.getHeadCommit("main");
            when(fixture.merger.mergeContent(anyString(), anyString(), anyString()))
                    .thenReturn(new TaxDslMergeResult("remote", List.of()));
            fixture.sync(publish);
            assertEquals(sourceHead, fixture.source.getHeadCommit("draft"));
            assertEquals(localHead, fixture.local.getHeadCommit("main"));
            assertNull(fixture.source.getProvenance(sourceHead));
            assertNull(fixture.local.getProvenance(localHead));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void invalidSourceSignatureBlocksIntegrationBeforeSynchronizationBaseCreation(boolean publish) throws Exception {
        try (var fixture = new Fixture()) {
            var incoming = publish ? fixture.local : fixture.source;
            insertInvalidSignature(incoming, publish ? "main" : "draft");
            var removeBase = fixture.local.getGitRepository().updateRef("refs/heads/sync-base");
            removeBase.setForceUpdate(true);
            removeBase.delete();
            String sourceBefore = fixture.source.getHeadCommit("draft");
            String localBefore = fixture.local.getHeadCommit("main");
            assertThrows(IOException.class, () -> fixture.sync(publish));
            assertEquals(sourceBefore, fixture.source.getHeadCommit("draft"));
            assertEquals(localBefore, fixture.local.getHeadCommit("main"));
            assertNull(fixture.local.getHeadCommit("sync-base"));
            assertNull(fixture.workspace.getLastIntegratedCommit());
            verify(fixture.rows, never()).save(any());
            verify(fixture.portfolio, never()).commitPortfolio(anyString(), anyString(), anyString(), any());
            verify(fixture.portfolio, never()).materializePortfolio(anyString(), anyString(), any());
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void strictIncomingPolicyAllowsAcceptedUnsignedSynchronizationBase(boolean publish, @TempDir Path keys) throws Exception {
        try (var fixture = new Fixture()) {
            String legacyBase = fixture.local.getHeadCommit("sync-base");
            enableStrictSigning(fixture, keys);
            when(fixture.merger.mergeContent(anyString(), anyString(), anyString()))
                    .thenReturn(new TaxDslMergeResult("merged", List.of()));

            fixture.sync(publish);

            assertEquals("merged", fixture.local.getDslAtHead("main"));
            if (publish) assertEquals("merged", fixture.source.getDslAtHead("draft"));
            assertEquals("merged", fixture.local.getDslAtHead("sync-base"));
            String updatedBase = fixture.local.getHeadCommit("sync-base");
            assertNotEquals(legacyBase, updatedBase);
            assertEquals(SignatureStatus.UNSIGNED, fixture.local.verifyCommit(legacyBase).status());
            assertEquals(SignatureStatus.VERIFIED, fixture.local.verifyCommit(updatedBase).status());
            assertEquals(SignatureStatus.VERIFIED,
                    fixture.local.verifyCommit(fixture.local.getHeadCommit("main")).status());
            assertNotNull(fixture.workspace.getLastIntegratedCommit());
            verify(fixture.rows).save(fixture.workspace);
        }
    }

    static Stream<Arguments> rejectedBaseSignatures() {
        return Stream.of(false, true).flatMap(publish ->
                Stream.of(false, true).map(untrusted -> Arguments.of(publish, untrusted)));
    }

    @ParameterizedTest
    @MethodSource("rejectedBaseSignatures")
    void rejectedSynchronizationBaseCannotMutateEitherModel(boolean publish, boolean untrusted,
                                                            @TempDir Path keys) throws Exception {
        try (var fixture = new Fixture()) {
            GitCommitSignatureService trusted = enableStrictSigning(fixture, keys.resolve("trusted"));
            if (untrusted) {
                fixture.local.withSignatureService(SigningTestKeys.createIndependentService(
                        keys.resolve("untrusted"), "Other operator <other@example.test>"), "workspace:workspace");
                fixture.local.commitDsl("sync-base", "base", "other", "Untrusted synchronization base");
                fixture.local.withSignatureService(trusted, "workspace:workspace");
            } else {
                insertInvalidSignature(fixture.local, "sync-base");
            }
            String sourceBefore = fixture.source.getHeadCommit("draft");
            String localBefore = fixture.local.getHeadCommit("main");
            String baseBefore = fixture.local.getHeadCommit("sync-base");
            assertEquals(untrusted ? SignatureStatus.UNTRUSTED : SignatureStatus.INVALID,
                    fixture.local.verifyCommit(baseBefore).status());
            when(fixture.merger.mergeContent(anyString(), anyString(), anyString()))
                    .thenReturn(new TaxDslMergeResult("merged", List.of()));

            assertThrows(IOException.class, () -> fixture.sync(publish));

            assertEquals(sourceBefore, fixture.source.getHeadCommit("draft"));
            assertEquals(localBefore, fixture.local.getHeadCommit("main"));
            assertEquals(baseBefore, fixture.local.getHeadCommit("sync-base"));
            assertNull(fixture.workspace.getLastIntegratedCommit());
            verify(fixture.rows, never()).save(any());
            verify(fixture.portfolio, never()).commitPortfolio(anyString(), anyString(), anyString(), any());
            verify(fixture.portfolio, never()).materializePortfolio(anyString(), anyString(), any());
        }
    }

    private static GitCommitSignatureService enableStrictSigning(Fixture fixture, Path keys) throws Exception {
        var properties = SigningTestKeys.signingProperties(keys);
        properties.setRequireSignatures(true);
        var signer = new GitCommitSignatureService(properties);
        fixture.source.withSignatureService(signer, "source");
        fixture.local.withSignatureService(signer, "workspace:workspace");
        fixture.source.commitDsl("draft", "remote", "owner", "Trusted source checkpoint");
        fixture.local.commitDsl("main", "local", "owner", "Trusted workspace checkpoint");
        return signer;
    }

    private static void insertInvalidSignature(DslGitRepository repository, String branch) throws IOException {
        var git = repository.getGitRepository();
        var head = org.eclipse.jgit.lib.ObjectId.fromString(repository.getHeadCommit(branch));
        var existing = org.eclipse.jgit.revwalk.RevCommit.parse(git.open(head).getBytes());
        var commit = new org.eclipse.jgit.lib.CommitBuilder();
        commit.setTreeId(existing.getTree()); commit.setParentId(head);
        var actor = new org.eclipse.jgit.lib.PersonIdent("Fixture", "fixture@example.invalid");
        commit.setAuthor(actor); commit.setCommitter(actor); commit.setMessage("Invalid fixture signature");
        commit.setGpgSignature(new org.eclipse.jgit.lib.GpgSignature(
                "-----BEGIN PGP SIGNATURE-----\ninvalid\n-----END PGP SIGNATURE-----\n"
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        try (var inserter = git.newObjectInserter()) {
            var id = inserter.insert(commit); inserter.flush();
            var update = git.updateRef("refs/heads/" + branch);
            update.setExpectedOldObjectId(head); update.setNewObjectId(id);
            assertEquals(org.eclipse.jgit.lib.RefUpdate.Result.FAST_FORWARD, update.update());
        }
    }
    private static final class Fixture implements AutoCloseable {
        final DslGitRepositoryFactory factory = spy(new DslGitRepositoryFactory(null));
        final DslGitRepository source = factory.getSystemRepository();
        final DslGitRepository local = factory.openWorkspaceRepository("workspace");
        final UserWorkspace workspace = new UserWorkspace();
        final UserWorkspaceRepository rows = mock(UserWorkspaceRepository.class);
        final WorkspacePortfolioGitPort portfolio = mock(WorkspacePortfolioGitPort.class);
        final SemanticGitMergeService merger = mock(SemanticGitMergeService.class);
        final GitNativeSyncIntegrationService service;
        Fixture() throws Exception {
            source.commitDsl("draft", "remote", "owner", "Remote");
            local.commitDsl("main", "local", "owner", "Local");
            local.commitDsl("sync-base", "base", "owner", "Base");
            doReturn(source).when(factory).getCentralRepository("source");
            workspace.setWorkspaceId("workspace"); workspace.setUsername("owner");
            workspace.setSourceRepositoryId("source"); workspace.setCurrentBranch("main");
            when(rows.findByWorkspaceId("workspace")).thenReturn(Optional.of(workspace));
            when(rows.save(any())).thenAnswer(call -> call.getArgument(0));
            var catalog = mock(SystemRepositoryService.class);
            var metadata = new SystemRepository(); metadata.setRepositoryId("source"); metadata.setDefaultBranch("draft");
            when(catalog.getRepository("source")).thenReturn(metadata);
            var syncRows = mock(SyncStateRepository.class);
            when(syncRows.findByUsername("owner")).thenReturn(Optional.of(new SyncState()));
            service = new GitNativeSyncIntegrationService(syncRows, rows, catalog, factory, merger, portfolio,
                    mock(WorkspaceContextResolver.class), DIRECT,
                mock(RepositoryMembershipService.class, invocation -> true));
        }
        void sync(boolean publish) throws IOException {
            if (publish) service.publishFromWorkspaceToShared("owner", "workspace");
            else service.syncFromSharedToWorkspace("owner", "workspace");
        }
        public void close() { factory.close(); }
    }
}
