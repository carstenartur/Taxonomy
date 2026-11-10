package com.taxonomy.versioning.service;

import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceManager;
import com.taxonomy.workspace.signing.SignatureStatus;
import com.taxonomy.workspace.signing.SigningTestKeys;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Explicit historical navigation must not turn rejected staged objects into accepted branches. */
class ContextVariantIntegrityTest {
    private static final String ACTOR = "architect";
    private static final WorkspaceContext SCOPE =
            new WorkspaceContext(ACTOR, "private-copy", "draft", "source-repository");

    @TempDir Path keys;
    private DslGitRepositoryFactory repositories;
    private DslGitRepository workspace;
    private ContextNavigationService navigation;
    private String accepted;

    @BeforeEach
    void setUp() throws Exception {
        repositories = new DslGitRepositoryFactory(null);
        workspace = repositories.openWorkspaceRepository(SCOPE.workspaceId())
                .withSignatureService(SigningTestKeys.createService(keys), "workspace:private-copy");
        var manager = new WorkspaceManager(mock(UserWorkspaceRepository.class), 50,
                mock(SystemRepositoryService.class), repositories);
        navigation = new ContextNavigationService(repositories, mock(RepositoryStateService.class), manager, 50);
        accepted = workspace.commitDsl("draft", "accepted architecture", ACTOR, "Accepted snapshot");
    }

    @AfterEach
    void close() throws Exception {
        repositories.close();
    }

    @Test
    void variantRejectsATamperedSelectedSnapshot() throws Exception {
        String rejected = tamperedSnapshot();
        var selected = navigation.openReadOnly(ACTOR, "draft", rejected, SCOPE, null, null);

        assertThrows(IOException.class,
                () -> navigation.createVariantFromCurrent(ACTOR, "rejected-variant", SCOPE));

        assertNull(workspace.getHeadCommit("rejected-variant"));
        assertEquals(accepted, workspace.getHeadCommit("draft"));
        assertEquals(selected, navigation.getCurrentContextForScope(SCOPE));
    }

    @Test
    void signedTipCannotHideATamperedAncestorBehindStagingRefs() throws Exception {
        String rejected = tamperedSnapshot();
        String signedTip;
        try (var walk = new RevWalk(workspace.getGitRepository());
             var writer = workspace.getGitRepository().newObjectInserter()) {
            var commit = new CommitBuilder();
            commit.setTreeId(walk.parseCommit(ObjectId.fromString(accepted)).getTree());
            commit.setParentIds(ObjectId.fromString(accepted), ObjectId.fromString(rejected));
            var author = new PersonIdent(ACTOR, "architect@example.test");
            commit.setAuthor(author);
            commit.setCommitter(author);
            commit.setMessage("Signed merge over rejected history");
            workspace.signCommit(commit, "draft");
            signedTip = writer.insert(commit).name();
            writer.flush();
        }
        assertEquals(SignatureStatus.VERIFIED, workspace.verifyCommit(signedTip).status());
        assertEquals(SignatureStatus.INVALID, workspace.verifyCommit(rejected).status());
        for (String name : new String[]{"refs/taxonomy/incoming/rejected", "refs/heads/__external_sync__/rejected"}) {
            var staging = workspace.getGitRepository().updateRef(name);
            staging.setNewObjectId(ObjectId.fromString(signedTip));
            assertEquals(org.eclipse.jgit.lib.RefUpdate.Result.NEW, staging.update());
        }
        navigation.openReadOnly(ACTOR, "draft", signedTip, SCOPE, null, null);

        assertThrows(IOException.class,
                () -> navigation.createVariantFromCurrent(ACTOR, "rejected-merge-variant", SCOPE));

        assertNull(workspace.getHeadCommit("rejected-merge-variant"));
        assertEquals(accepted, workspace.getHeadCommit("draft"));
    }

    @Test
    void variantRetainsTheExactValidHistoricalSnapshotAfterItsBranchMoves() throws Exception {
        navigation.openReadOnly(ACTOR, "draft", accepted, SCOPE, null, null);
        String latest = workspace.commitDsl("draft", "later architecture", ACTOR, "Later snapshot");

        var variant = navigation.createVariantFromCurrent(ACTOR, "historical-variant", SCOPE);

        assertEquals(accepted, variant.commitId());
        assertEquals(accepted, workspace.getHeadCommit("historical-variant"));
        assertEquals(latest, workspace.getHeadCommit("draft"));
        assertEquals(SignatureStatus.VERIFIED, workspace.verifyCommit(variant.commitId()).status());
    }

    private String tamperedSnapshot() throws Exception {
        byte[] raw = workspace.getGitRepository().open(ObjectId.fromString(accepted), Constants.OBJ_COMMIT).getBytes();
        String tampered = new String(raw, StandardCharsets.UTF_8)
                .replace("\n\nAccepted snapshot", "\n\nTampered snapshot");
        assertNotEquals(new String(raw, StandardCharsets.UTF_8), tampered);
        try (var writer = workspace.getGitRepository().newObjectInserter()) {
            String rejected = writer.insert(Constants.OBJ_COMMIT, tampered.getBytes(StandardCharsets.UTF_8)).name();
            writer.flush();
            return rejected;
        }
    }
}
