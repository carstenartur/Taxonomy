package com.taxonomy.versioning.controller;

import com.taxonomy.dsl.export.DslMaterializeService;
import com.taxonomy.dsl.storage.DslBranch;
import com.taxonomy.model.HypothesisStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationHypothesis;
import com.taxonomy.versioning.service.DslOperationsFacade;
import com.taxonomy.versioning.service.HypothesisService;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DslApiControllerBranchCoverageTest {

    @Mock private DslOperationsFacade dslOps;
    @Mock private HypothesisService hypothesisService;
    @Mock private WorkspaceResolver workspaceResolver;
    @Mock private RepositoryStateService repositoryStateService;
    @Mock private Authentication authentication;

    private DslApiController controller;
    private final WorkspaceContext context = new WorkspaceContext("alice", "ws-1", "draft");

    @BeforeEach
    void setUp() {
        controller = new DslApiController(dslOps, hypothesisService, workspaceResolver, repositoryStateService);
        lenient().when(workspaceResolver.resolveCurrentUsername()).thenReturn("alice");
        lenient().when(workspaceResolver.resolveCurrentContext()).thenReturn(context);
    }

    @Test
    void materializeIncrementalCoversSuccessWithDocumentBranchAndBadRequest() {
        DslMaterializeService.MaterializeResult result =
                new DslMaterializeService.MaterializeResult(true, List.of(), List.of("warning"), 2, 1, 42L);
        com.taxonomy.architecture.model.ArchitectureDslDocument document =
                new com.taxonomy.architecture.model.ArchitectureDslDocument();
        document.setBranch("review");
        when(dslOps.materializeIncremental(1L, 2L)).thenReturn(result);
        when(dslOps.findDocumentById(2L)).thenReturn(Optional.of(document));

        var success = controller.materializeIncremental(1L, 2L);
        assertThat(success.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(success.getBody()).containsEntry("relationsCreated", 2).containsEntry("hypothesesCreated", 1);
        verify(dslOps).getViewContext("review");

        reset(dslOps);
        when(dslOps.materializeIncremental(1L, 2L)).thenThrow(new IllegalArgumentException("missing document"));
        var failure = controller.materializeIncremental(1L, 2L);
        assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(failure.getBody()).containsEntry("error", "missing document");
    }

    @Test
    void branchesCoverListCreateMissingSourceAndIoFailures() throws Exception {
        when(dslOps.listBranches()).thenReturn(List.of(new DslBranch("draft", "abc", Instant.EPOCH)));
        var listed = controller.listBranches();
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listed.getBody()).singleElement().satisfies(branch ->
                assertThat(branch).containsEntry("name", "draft").containsEntry("headCommitId", "abc"));

        when(dslOps.createBranch("review", "draft")).thenReturn("abc");
        assertThat(controller.createBranch("review", "draft").getBody())
                .containsEntry("branch", "review").containsEntry("forkedFrom", "draft");

        when(dslOps.createBranch("missing", "absent")).thenReturn((String) null);
        assertThat(controller.createBranch("missing", "absent").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        when(dslOps.createBranch("broken", "draft")).thenThrow(new IOException("disk"));
        assertThat(controller.createBranch("broken", "draft").getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        reset(dslOps);
        when(dslOps.listBranches()).thenThrow(new IOException("read"));
        assertThat(controller.listBranches().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void cherryPickMergeAndRevertCoverSuccessConflictAndIoFailures() throws Exception {
        when(dslOps.cherryPick("c1", "review")).thenReturn("c2");
        assertThat(controller.cherryPick("c1", "review").getBody())
                .containsEntry("commitId", "c2").containsEntry("cherryPickedFrom", "c1");
        when(dslOps.cherryPick("conflict", "review")).thenReturn((String) null);
        assertThat(controller.cherryPick("conflict", "review").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        when(dslOps.cherryPick("io", "review")).thenThrow(new IOException("io failure"));
        assertThat(controller.cherryPick("io", "review").getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        when(dslOps.merge("review", "accepted")).thenReturn("m1");
        assertThat(controller.merge("review", "accepted").getBody())
                .containsEntry("commitId", "m1").containsEntry("intoBranch", "accepted");
        when(dslOps.merge("conflict", "accepted")).thenReturn((String) null);
        assertThat(controller.merge("conflict", "accepted").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        when(dslOps.merge("io", "accepted")).thenThrow(new IOException("merge io"));
        assertThat(controller.merge("io", "accepted").getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        when(dslOps.revert("c1", "draft")).thenReturn("r1");
        assertThat(controller.revert("c1", "draft").getBody())
                .containsEntry("commitId", "r1").containsEntry("revertedCommit", "c1");
        when(dslOps.revert("conflict", "draft")).thenReturn((String) null);
        assertThat(controller.revert("conflict", "draft").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        when(dslOps.revert("io", "draft")).thenThrow(new IOException("revert io"));
        assertThat(controller.revert("io", "draft").getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void undoRestoreAndDeleteCoverAllResultBranches() throws Exception {
        when(dslOps.undoLast("draft")).thenReturn("parent");
        assertThat(controller.undoLast("draft").getBody()).containsEntry("commitId", "parent");
        when(dslOps.undoLast("empty")).thenReturn((String) null);
        assertThat(controller.undoLast("empty").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        when(dslOps.undoLast("io")).thenThrow(new IOException("undo io"));
        assertThat(controller.undoLast("io").getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        when(dslOps.restore("old", "draft")).thenReturn("new");
        assertThat(controller.restore("old", "draft").getBody())
                .containsEntry("commitId", "new").containsEntry("restoredFrom", "old");
        when(dslOps.restore("missing", "draft")).thenReturn((String) null);
        assertThat(controller.restore("missing", "draft").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        when(dslOps.restore("broken", "draft")).thenThrow(new IllegalStateException("restore failure"));
        assertThat(controller.restore("broken", "draft").getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        when(dslOps.deleteBranch("feature")).thenReturn(true);
        assertThat(controller.deleteBranch("feature").getBody())
                .containsEntry("deleted", "feature").containsEntry("success", true);
        when(dslOps.deleteBranch("missing")).thenReturn(false);
        assertThat(controller.deleteBranch("missing").getStatusCode().value()).isEqualTo(404);
        when(dslOps.deleteBranch("draft")).thenThrow(new IllegalArgumentException("protected"));
        assertThat(controller.deleteBranch("draft").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        when(dslOps.deleteBranch("io")).thenThrow(new IOException("delete io"));
        assertThat(controller.deleteBranch("io").getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void manualConflictResolutionCoversSuccessAndFailure() throws Exception {
        when(dslOps.resolveCurrentUsername()).thenReturn("alice");
        when(dslOps.commitDsl("accepted", "resolved", "alice",
                "Resolve merge conflict: review → accepted")).thenReturn("merge-resolution");
        assertThat(controller.mergeResolve("review", "accepted", "resolved").getBody())
                .containsEntry("commitId", "merge-resolution").containsEntry("resolution", "manual");

        when(dslOps.commitDsl("accepted", "broken", "alice",
                "Resolve merge conflict: review → accepted")).thenThrow(new IOException("commit failed"));
        assertThat(controller.mergeResolve("review", "accepted", "broken").getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        when(authentication.getName()).thenReturn("bob");
        when(dslOps.commitDsl("review", "resolved", "bob", "Resolve cherry-pick conflict: abcdefg"))
                .thenReturn("cherry-resolution");
        assertThat(controller.cherryPickResolve("abcdefghi", "review", "resolved", authentication).getBody())
                .containsEntry("commitId", "cherry-resolution").containsEntry("resolution", "manual");

        when(dslOps.commitDsl("review", "broken", "system", "Resolve cherry-pick conflict: short"))
                .thenThrow(new IOException("commit failed"));
        assertThat(controller.cherryPickResolve("short", "review", "broken", null).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void gitReadsCoverSuccessNotFoundIoFailureAndSharedFallback() throws Exception {
        when(dslOps.getDslAtHead("draft", context)).thenReturn("element A type Capability {}");
        var head = controller.getGitHead("draft");
        assertThat(head.getBody()).containsEntry("branch", "draft").containsEntry("length", 28);
        verify(dslOps).getViewContext("alice", "draft", context);

        when(dslOps.getDslAtHead("empty", context)).thenReturn((String) null);
        assertThat(controller.getGitHead("empty").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        when(dslOps.getDslAtHead("io", context)).thenThrow(new IOException("read io"));
        assertThat(controller.getGitHead("io").getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        when(dslOps.getDslAtCommit("abc")).thenReturn("dsl");
        assertThat(controller.getGitCommit("abc").getBody()).containsEntry("length", 3);
        when(dslOps.getDslAtCommit("missing")).thenReturn((String) null);
        assertThat(controller.getGitCommit("missing").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        when(dslOps.getDslAtCommit("bad")).thenThrow(new IllegalArgumentException("bad commit"));
        assertThat(controller.getGitCommit("bad").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        doThrow(new IllegalStateException("provisioning failed"))
                .when(repositoryStateService).ensureWorkspaceState("alice");
        when(dslOps.getDslAtHead("shared", WorkspaceContext.SHARED)).thenReturn("shared dsl");
        controller.getGitHead("shared");
        verify(dslOps).getDslAtHead("shared", WorkspaceContext.SHARED);
    }

    @Test
    void hypothesesCoverSuccessAndAllExpectedErrors() {
        RelationHypothesis provisional = hypothesis(7L, HypothesisStatus.PROVISIONAL, false);
        RelationHypothesis accepted = hypothesis(7L, HypothesisStatus.ACCEPTED, false);
        RelationHypothesis rejected = hypothesis(8L, HypothesisStatus.REJECTED, false);
        RelationHypothesis applied = hypothesis(9L, HypothesisStatus.PROVISIONAL, true);

        when(hypothesisService.findAll(context)).thenReturn(List.of(provisional));
        when(hypothesisService.findByStatus(HypothesisStatus.PROVISIONAL, context)).thenReturn(List.of(provisional));
        assertThat(controller.listHypotheses(null).getBody()).containsExactly(provisional);
        assertThat(controller.listHypotheses(HypothesisStatus.PROVISIONAL).getBody()).containsExactly(provisional);

        when(hypothesisService.accept(7L, context)).thenReturn(accepted);
        assertThat(controller.acceptHypothesis(7L).getBody())
                .containsEntry("status", "ACCEPTED").containsEntry("relationType", "RELATED_TO");
        when(hypothesisService.accept(404L, context)).thenThrow(new IllegalArgumentException("missing"));
        assertThat(controller.acceptHypothesis(404L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        when(hypothesisService.accept(10L, context)).thenThrow(new IllegalStateException("already final"));
        assertThat(controller.acceptHypothesis(10L).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        when(hypothesisService.reject(8L, context)).thenReturn(rejected);
        assertThat(controller.rejectHypothesis(8L).getBody()).containsEntry("status", "REJECTED");
        when(hypothesisService.reject(404L, context)).thenThrow(new IllegalArgumentException("missing"));
        assertThat(controller.rejectHypothesis(404L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        when(hypothesisService.reject(10L, context)).thenThrow(new IllegalStateException("already final"));
        assertThat(controller.rejectHypothesis(10L).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        when(hypothesisService.applyForSession(9L, context)).thenReturn(applied);
        assertThat(controller.applyHypothesisForSession(9L).getBody())
                .containsEntry("appliedInCurrentAnalysis", true);
        when(hypothesisService.applyForSession(404L, context)).thenThrow(new IllegalArgumentException("missing"));
        assertThat(controller.applyHypothesisForSession(404L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        when(hypothesisService.findEvidence(7L, context)).thenReturn(List.of());
        assertThat(controller.getHypothesisEvidence(7L).getStatusCode()).isEqualTo(HttpStatus.OK);
        when(hypothesisService.findEvidence(404L, context)).thenThrow(new IllegalArgumentException("missing"));
        assertThat(controller.getHypothesisEvidence(404L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void simpleReadEndpointsCoverEmptyAndNonEmptyResults() throws Exception {
        when(dslOps.indexBranch("draft")).thenReturn(4);
        assertThat(controller.indexHistory("draft").getBody())
                .containsEntry("branch", "draft").containsEntry("indexed", 4);
        when(dslOps.searchHistory("term", 20)).thenReturn(List.of());
        assertThat(controller.searchHistory("term", 20).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.searchVersioned("term", "draft", 20).getBody()).isEmpty();
        when(dslOps.findByElement("E1")).thenReturn(List.of());
        when(dslOps.findByRelation("E1 RELATED_TO E2")).thenReturn(List.of());
        assertThat(controller.findHistoryByElement("E1").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.findHistoryByRelation("E1 RELATED_TO E2").getStatusCode()).isEqualTo(HttpStatus.OK);
        when(dslOps.aggregateElementHistory("E1")).thenReturn(null);
        assertThat(controller.elementHistoryAggregation("E1").getBody())
                .isEqualTo(Map.of("elementId", "E1", "message", "No history found for element E1"));
        when(dslOps.listDocuments()).thenReturn(List.of());
        assertThat(controller.listDocuments().getBody()).isEmpty();
        assertThat(controller.getMergeConflictDetails("a", "b").getBody())
                .isEqualTo(Map.of("conflict", false, "message", "No conflict detected"));
        assertThat(controller.getCherryPickConflictDetails("c", "review").getBody())
                .isEqualTo(Map.of("conflict", false, "message", "No conflict detected"));
        assertThat(controller.previewMerge("a", "b").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.previewCherryPick("c", "review").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.checkOperation("draft", "COMMIT").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private static RelationHypothesis hypothesis(Long id, HypothesisStatus status, boolean applied) {
        RelationHypothesis hypothesis = new RelationHypothesis();
        hypothesis.setId(id);
        hypothesis.setSourceNodeId("A");
        hypothesis.setTargetNodeId("B");
        hypothesis.setRelationType(RelationType.RELATED_TO);
        hypothesis.setStatus(status);
        hypothesis.setAppliedInCurrentAnalysis(applied);
        return hypothesis;
    }
}
