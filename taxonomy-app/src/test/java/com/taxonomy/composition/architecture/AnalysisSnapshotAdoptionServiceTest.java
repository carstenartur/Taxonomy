package com.taxonomy.composition.architecture;

import com.taxonomy.dto.*;
import com.taxonomy.editor.*;
import com.taxonomy.editor.ArchitectureCommandPort.*;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.service.*;
import com.taxonomy.workspace.model.*;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisSnapshotAdoptionServiceTest {
    private final PortfolioAnalysisPersistenceService snapshots = mock(PortfolioAnalysisPersistenceService.class);
    private final ProjectPortfolioService projects = mock(ProjectPortfolioService.class);
    private final UserWorkspaceRepository workspaces = mock(UserWorkspaceRepository.class);
    private final SystemRepositoryService repositories = mock(SystemRepositoryService.class);
    private final RepositoryMembershipService membership = mock(RepositoryMembershipService.class);
    private final RepositoryContext target = RepositoryContext.workspace("repo-a", "copy-a", "draft", "alice");
    private final WorkspaceContext source = new WorkspaceContext("alice", null, "draft", "repo-a");
    private EditorPersistenceFixture fixture;
    private AnalysisSnapshotAdoptionService service;
    private AnalysisSnapshotAdoptionService.SourceRef ref;
    private SnapshotDetail snapshot;
    @BeforeEach void setup() {
        fixture = new EditorPersistenceFixture("jdbc:hsqldb:mem:adoption-" + UUID.randomUUID());
        service = new AnalysisSnapshotAdoptionService(fixture.service, snapshots, projects, workspaces, repositories, membership);
        ref = new AnalysisSnapshotAdoptionService.SourceRef("repo-a", "__shared__", "draft", 1L, 2L, 3L, "snapshot-old");
        UserWorkspace copy = workspace("copy-a", "alice");
        when(workspaces.findByWorkspaceId("copy-a")).thenReturn(Optional.of(copy));
        SystemRepository repository = mock(SystemRepository.class);
        when(repositories.getRepository("repo-a")).thenReturn(repository);
        when(membership.canRead(repository, "alice")).thenReturn(true);
        var node = new RequirementElementView(); node.setNodeCode("CP-1000"); node.setTaxonomySheet("CP"); node.setTitle("Frozen capability");
        var graph = new RequirementArchitectureView(); graph.setIncludedElements(List.of(node)); graph.setIncludedRelationships(List.of());
        var analysis = new AnalysisResult(); analysis.setArchitectureView(graph);
        snapshot = new SnapshotDetail(new SnapshotSummary("snapshot-old",1L,2L,"REQ-2",3L,1,"job", AnalysisStatus.SUCCESS,
                "mock","model", "catalogue-hash", "prompt-hash",null,"draft",null,Instant.parse("2026-10-28T12:00:00Z"),1,0,null),
                analysis,null,null,null,List.of(),List.of());
        when(snapshots.getSnapshot(1L, "snapshot-old", "alice", source)).thenReturn(snapshot);
        when(projects.listRequirementVersions(1L,2L,"alice",source)).thenReturn(List.of(
                new RequirementVersionView(4L,2,"Edited requirement", "new-hash",null,"alice",Instant.now(),null),
                new RequirementVersionView(3L,1,"Old requirement text", "old-hash",null,"alice",Instant.now(),null)));
    }
    @AfterEach void close() { fixture.close(); }
    @Test void frozenSourcePreviewIsReadOnlyAcceptanceUsesOneRevisionAndRepeatOpensExistingView() throws Exception {
        var request = request(ref, null);
        var preview = service.preview(target, request);
        assertThat(fixture.journal.read(target)).isNull();
        assertThat(preview.summary().addedElements()).containsExactly("CP-1000");
        var acceptedRequest = new AnalysisSnapshotAdoptionService.Request(request.context(), request.metadata(), ref, preview.fingerprint());
        var accepted = service.adopt(target, acceptedRequest);
        assertThat(accepted.accepted().context().revision()).isEqualTo(1);
        assertThat(service.adopt(target, acceptedRequest).accepted().replayed()).isTrue();
        assertThat(fixture.service.read(target,null).dsl()).contains("Old requirement text").doesNotContain("Edited requirement");
        assertThat(fixture.journal.read(target).operations()).hasSize(1);
        var again = request(ref,null); var repeated = service.preview(target,again);
        assertThat(repeated.existing()).isTrue();
        assertThat(service.adopt(target,new AnalysisSnapshotAdoptionService.Request(again.context(),again.metadata(),ref,repeated.fingerprint())).existing()).isTrue();
        assertThat(fixture.journal.read(target).operations()).hasSize(1);
    }
    @Test void typeCollisionAppearsInPreviewWithoutChangingTheWorkspace() throws Exception {
        fixture.repositories.resolveRepository(target).commitDsl("draft","element CP-1000 type Component {\n title: \"Edited collision\";\n}\n","alice","Fixture");
        var result = service.preview(target,request(ref,null));
        assertThat(result.summary().conflicts()).hasSize(1);
        assertThat(result.preview()).isNull();
        assertThat(fixture.journal.read(target)).isNull();
    }
    @Test void malformedAndOversizeFrozenSourcesRejectAtomicallyAndReviewOverlayCannotApproveRelations() throws Exception {
        var other = new RequirementElementView(); other.setNodeCode("UA-1000"); other.setTaxonomySheet("UA"); other.setTitle("Recorded application");
        var graph = snapshot.analysis().getArchitectureView();
        graph.setIncludedElements(List.of(graph.getIncludedElements().getFirst(),other));
        var relation = new RequirementRelationshipView(); relation.setSourceCode("CP-1000"); relation.setTargetCode("UA-1000"); relation.setRelationType("RELATED_TO");
        graph.setIncludedRelationships(List.of(relation));
        var confirmedOverlay = new RelationMappingView(9L,"snapshot-old","CP-1000","UA-1000","RELATED_TO","LLM","impact",0.9,0.9,
                "Mutable reviewed overlay",com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus.CONFIRMED,"alice",Instant.now(),"Approved by reviewer");
        when(snapshots.getSnapshot(1L,"snapshot-old","alice",source)).thenReturn(new SnapshotDetail(snapshot.summary(),snapshot.analysis(),
                null,null,null,List.of(),List.of(confirmedOverlay)));
        var request = request(ref,null); var preview = service.preview(target,request);
        var accepted = service.adopt(target,new AnalysisSnapshotAdoptionService.Request(request.context(),request.metadata(),ref,preview.fingerprint()));
        assertThat(new com.taxonomy.dsl.command.ArchitectureDslCommands().model(accepted.accepted().change().dsl()).getRelations().getFirst().getStatus()).isEqualTo("proposed");
        // An immutable source returning a different graph under the same tuple must conflict.
        other.setTitle("Changed source graph");
        var conflict = service.preview(target,request(ref,null));
        assertThat(conflict.summary().conflicts()).hasSize(1);
        assertThat(fixture.journal.read(target).operations()).hasSize(1);
        graph.setIncludedElements(java.util.Collections.nCopies(2001,other));
        assertCode(() -> service.preview(target,request(ref,null)),"ITEM_LIMIT");
        assertThat(fixture.journal.read(target).operations()).hasSize(1);
    }

    @Test void changedPreviewFingerprintOrSourceIdentityCannotWrite() throws Exception {
        var request = request(ref,"wrong");
        assertCode(() -> service.adopt(target,request), "PREVIEW_CHANGED");
        var wrongVersion = new AnalysisSnapshotAdoptionService.SourceRef("repo-a","__shared__","draft",1L,2L,99L,"snapshot-old");
        assertCode(() -> service.preview(target,request(wrongVersion,null)), "SOURCE_IDENTITY_MISMATCH");
        var wrongBranch = new AnalysisSnapshotAdoptionService.SourceRef("repo-a","__shared__","other",1L,2L,3L,"snapshot-old");
        when(snapshots.getSnapshot(1L,"snapshot-old","alice",new WorkspaceContext("alice",null,"other","repo-a"))).thenReturn(snapshot);
        assertCode(() -> service.preview(target,request(wrongBranch,null)), "SOURCE_IDENTITY_MISMATCH");
        assertThat(fixture.journal.read(target)).isNull();
    }
    @Test void privateSourceOwnershipAndRepositoryMembershipAreAuthoritative() throws Exception {
        when(workspaces.findByWorkspaceId("bob-copy")).thenReturn(Optional.of(workspace("bob-copy","bob")));
        var other = new AnalysisSnapshotAdoptionService.SourceRef("repo-a","bob-copy","draft",1L,2L,3L,"snapshot-old");
        assertCode(() -> service.preview(target,request(other,null)), "NOT_FOUND");
        verifyNoInteractions(snapshots);
        when(membership.canRead(any(),eq("alice"))).thenReturn(false);
        assertCode(() -> service.preview(target,request(ref,null)), "NOT_FOUND");
        assertThat(fixture.journal.read(target)).isNull();
    }
    @Test void archivedUnreadyCentralAndUnrelatedTargetsCannotAdopt() throws Exception {
        var copy = workspace("copy-a","alice"); copy.setArchived(true);
        when(workspaces.findByWorkspaceId("copy-a")).thenReturn(Optional.of(copy));
        assertCode(() -> service.preview(target,request(ref,null)), "NOT_FOUND");
        copy.setArchived(false); copy.setProvisioningStatus(WorkspaceProvisioningStatus.FAILED);
        assertCode(() -> service.preview(target,request(ref,null)), "READ_ONLY");
        copy.setProvisioningStatus(WorkspaceProvisioningStatus.READY); copy.setSourceRepositoryId("unrelated");
        assertCode(() -> service.preview(target,request(ref,null)), "NOT_FOUND");
        verifyNoInteractions(snapshots);
    }
    private AnalysisSnapshotAdoptionService.Request request(AnalysisSnapshotAdoptionService.SourceRef sourceRef,String fingerprint) throws Exception {
        return new AnalysisSnapshotAdoptionService.Request(fixture.service.read(target,null).context(),
                new Metadata(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),"Adopt reviewed frozen source"),sourceRef,fingerprint);
    }
    private UserWorkspace workspace(String id,String owner) {
        var workspace = new UserWorkspace(); workspace.setWorkspaceId(id); workspace.setUsername(owner);
        workspace.setSourceRepositoryId("repo-a"); workspace.setSourceBranch("draft"); workspace.setCurrentBranch("draft");
        return workspace;
    }
    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }
}
