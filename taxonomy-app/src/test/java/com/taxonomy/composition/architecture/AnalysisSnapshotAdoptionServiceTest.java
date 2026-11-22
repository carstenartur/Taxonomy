package com.taxonomy.composition.architecture;

import com.taxonomy.dsl.model.CatalogueDefinition;
import com.taxonomy.dsl.model.CatalogueDefinitionResolver;
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
    @Test void centralAndAnOwnedCopyNamedSharedCanBothBePreviewedAdoptedAndReopened() throws Exception {
        String id = "__shared__";
        var copySource = new WorkspaceContext("alice", id, "draft", "repo-a");
        when(workspaces.findByWorkspaceId(id)).thenReturn(Optional.of(workspace(id, "alice")));
        var summary = new SnapshotSummary("snapshot-old",1L,2L,"REQ-2",3L,1,"job",AnalysisStatus.SUCCESS,
                "mock","model","catalogue-hash","prompt-hash",id,"draft",null,Instant.parse("2026-10-28T12:00:00Z"),1,0,null);
        when(snapshots.getSnapshot(1L, "snapshot-old", "alice", copySource))
                .thenReturn(new SnapshotDetail(summary,snapshot.analysis(),null,null,null,List.of(),List.of()));
        var versions = projects.listRequirementVersions(1L,2L,"alice",source);
        when(projects.listRequirementVersions(1L,2L,"alice",copySource)).thenReturn(versions);
        var central = new AnalysisSnapshotAdoptionService.SourceRef("repo-a",id,"draft",1L,2L,3L,"snapshot-old","");
        var literal = new AnalysisSnapshotAdoptionService.SourceRef("repo-a",id,"draft",1L,2L,3L,"snapshot-old",id);
        var legacy = request(ref,null);
        var explicitCentral = new AnalysisSnapshotAdoptionService.Request(legacy.context(),legacy.metadata(),central,null);
        var old = service.preview(target,legacy);
        assertThat(old.viewId()).isEqualTo("analysis-a1f42f1117e9463c1eed3829ba8d7aba618f1173fd7c2a8c605dfb1fd65e9eaa");
        assertThat(service.preview(target,explicitCentral).fingerprint()).isEqualTo(old.fingerprint());
        service.adopt(target,new AnalysisSnapshotAdoptionService.Request(legacy.context(),legacy.metadata(),central,old.fingerprint()));
        var next = request(literal,null);
        var copy = service.preview(target,next);
        assertThat(copy.viewId()).isNotEqualTo(old.viewId());
        assertThat(copy.existing()).isFalse();
        var accepted = service.adopt(target,new AnalysisSnapshotAdoptionService.Request(next.context(),next.metadata(),literal,copy.fingerprint()));
        var views = new com.taxonomy.dsl.command.ArchitectureDslCommands().model(accepted.accepted().change().dsl()).getViews();
        assertThat(views).filteredOn(view -> old.viewId().equals(view.getId())).singleElement()
                .satisfies(view -> assertThat(view.getExtensions()).doesNotContainKey("x-analysis-workspace-id"));
        assertThat(views).filteredOn(view -> copy.viewId().equals(view.getId())).singleElement()
                .satisfies(view -> assertThat(view.getExtensions()).containsEntry("x-analysis-workspace-id",id));
        assertThat(service.preview(target,request(literal,null)).existing()).isTrue();
        assertThatThrownBy(() -> new AnalysisSnapshotAdoptionService.SourceRef("repo-a","different","draft",1L,2L,3L,"snapshot-old",id))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void sharedContributorAdoptsIntoTheSameJournalAndRevocationClosesTheTarget() throws Exception {
        var copy=workspace("copy-a","alice"); copy.setCollaborative(true);
        when(workspaces.findByWorkspaceId("copy-a")).thenReturn(Optional.of(copy));
        var repository=repositories.getRepository("repo-a");
        when(membership.canCollaborate(repository,"bob")).thenReturn(true,true,false);
        when(membership.canRead(repository,"bob")).thenReturn(true);
        service.setActorAccess(username -> {});
        var bobSource=new WorkspaceContext("bob",null,"draft","repo-a");
        when(snapshots.getSnapshot(1L,"snapshot-old","bob",bobSource)).thenReturn(snapshot);
        var versions=projects.listRequirementVersions(1L,2L,"alice",source);
        when(projects.listRequirementVersions(1L,2L,"bob",bobSource)).thenReturn(versions);
        var bob=RepositoryContext.collaborativeWorkspace("repo-a","copy-a","draft","bob");
        var base=request(ref,null);
        var request=new AnalysisSnapshotAdoptionService.Request(fixture.service.read(bob,null).context(),base.metadata(),ref,null);
        var preview=service.preview(bob,request);
        service.adopt(bob,new AnalysisSnapshotAdoptionService.Request(request.context(),request.metadata(),ref,preview.fingerprint()));
        assertThat(fixture.service.read(bob,null).history().getFirst().actor()).isEqualTo("bob");
        assertThat(fixture.service.read(bob,null).context().revision()).isEqualTo(1);
        assertThatThrownBy(()->service.preview(bob,request)).isInstanceOfSatisfying(
                com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem.class,error->assertThat(error.code()).isEqualTo("NOT_FOUND"));
    }

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

    @Test void adoptsNonPrefixedCodeFromARetainedCustomTaxonomyAndReportsStableUnitDifferences() throws Exception {
        var resolver = mock(CatalogueDefinitionResolver.class);
        var definition = new CatalogueDefinition("custom-v1", true,
                List.of(new CatalogueDefinition.Root("Administration", "AS", "AdministrativeService")), List.of());
        when(resolver.forRevision("retained")).thenReturn(definition);
        when(resolver.forModel(any())).thenReturn(definition);
        when(resolver.analysisNodes("retained")).thenReturn(List.of(
                new CatalogueDefinitionResolver.Node("PROCESS.1", "AS", "AS", "Plan", "Planung", 1, "4ec1702a-929e-42a8-807b-cb7c82e1ab47"),
                new CatalogueDefinitionResolver.Node("PROCESS.2", "AS", "AS", "Operate", null, 2)));
        service.setCatalogueDefinitionResolver(resolver);
        fixture.service.setCatalogueDefinitionResolver(resolver);
        fixture.repositories.resolveRepository(target).commitDsl("draft", """
                organization local {
                  title: "Operations";
                }
                element PROCESS.2 type AdministrativeService {
                  title: "Existing service";
                  x-responsible-organization-id: "local";
                }
                """, "alice", "Working architecture");
        List<RequirementElementView> elements = new ArrayList<>();
        for (String code : List.of("PROCESS.1", "PROCESS.2")) {
            var element = new RequirementElementView();
            element.setNodeCode(code); element.setTaxonomySheet("AS"); element.setTitle(code);
            elements.add(element);
        }
        snapshot.analysis().getArchitectureView().setIncludedElements(elements);
        var unit = new TaxonomyResponsibilityLookup.Assignment("catalogue-team", "Operations", "AS");
        snapshot.analysis().setModelBinding(new AnalysisModelBinding(AnalysisModelBinding.State.BRIEF_UNASSIGNED, "repo-a",
                "__shared__", "draft", "a".repeat(40), null, "GIT_CHECKPOINT", "b".repeat(64), "retained", "custom-v1",
                Set.of("AS"), AnalysisScope.full(), null, Map.of(), List.of(), Set.of(), null, "retained",
                Map.of("PROCESS.1", unit, "PROCESS.2", unit)));
        var request = request(ref, null);
        var preview = service.preview(target, request);
        assertThat(preview.summary().differences()).filteredOn(diff -> diff.field().equals("responsibility"))
                .singleElement().satisfies(diff -> {
                    assertThat(diff.existingValue()).contains("local");
                    assertThat(diff.sourceValue()).contains("catalogue-team");
                });
        var adopted = service.adopt(target, new AnalysisSnapshotAdoptionService.Request(request.context(), request.metadata(), ref, preview.fingerprint()));
        assertThat(adopted.accepted().change().dsl()).contains("element PROCESS.1 type AdministrativeService",
                "x-catalogue-uuid: \"4ec1702a-929e-42a8-807b-cb7c82e1ab47\"", "x-catalogue-title-de: \"Planung\"",
                "x-captured-responsibility-unit: \"catalogue-team\"", "x-responsible-organization-id: \"local\"");
    }
    private RequirementRelationshipView frozenEdge() {
        var graph = snapshot.analysis().getArchitectureView();
        var targetNode = new RequirementElementView();
        targetNode.setNodeCode("UA-1000"); targetNode.setTaxonomySheet("UA"); targetNode.setTitle("Service");
        graph.setIncludedElements(List.of(graph.getIncludedElements().getFirst(), targetNode));
        var edge = new RequirementRelationshipView();
        edge.setSourceCode("CP-1000"); edge.setTargetCode("UA-1000"); edge.setRelationType("RELATED_TO");
        edge.setOrigin(RelationOrigin.LLM_SUPPORTED); edge.setPropagatedRelevance(0.98);
        edge.setRequirementEvidence(List.of(witness("CP-1000", "Old requirement", "Approved only")));
        graph.setIncludedRelationships(List.of(edge));
        return edge;
    }

    private RelationSearchModel.Edge witness(String assessedTarget, String quote, String condition) {
        var candidate = new RelationSearchModel.Node(assessedTarget, "CP", "Capability", "", false);
        var source = new RelationSearchModel.Node("UA-1000", "UA", "Service", "", false);
        var contribution = new RelationSearchModel.Contribution(source, "Assessed contribution", "requirement text", "When requested");
        var decision = new RelationSearchModel.Decision(assessedTarget, RelationSearchModel.Outcome.VERIFIED,
                "Candidate contribution", quote, RelationSearchModel.Necessity.REQUIRED, condition, "", "Verified rationale", "");
        return new RelationSearchModel.Edge(contribution, candidate, "RELATED_TO", RelationSearchModel.Direction.INCOMING, decision);
    }

    @Test void freezesDirectedEvidenceIntoCanonicalModelAndDoesNotTurnRelevanceIntoConfidence() throws Exception {
        frozenEdge();
        var request = request(ref, null);
        var preview = service.preview(target, request);
        var accepted = service.adopt(target, new AnalysisSnapshotAdoptionService.Request(
                request.context(), request.metadata(), ref, preview.fingerprint()));
        var model = new com.taxonomy.dsl.command.ArchitectureDslCommands().model(accepted.accepted().change().dsl());
        assertThat(model.getRelations()).singleElement().satisfies(r -> {
            assertThat(r.getProvenance()).isEqualTo("LLM_SUPPORTED");
            assertThat(r.getConfidence()).isNull();
            assertThat(r.getStatus()).isEqualTo("proposed");
        });
        assertThat(model.getEvidence()).singleElement().satisfies(e -> {
            assertThat(e.getForRelationSource()).isEqualTo("CP-1000");
            assertThat(e.getForRelationType()).isEqualTo("RELATED_TO");
            assertThat(e.getForRelationTarget()).isEqualTo("UA-1000");
            assertThat(e.getConfidence()).isNull();
            assertThat(e.getSummary()).isEqualTo("Verified rationale");
            assertThat(e.getExtensions()).containsEntry("x-analysis-view-id", preview.viewId())
                    .containsEntry("x-analysis-snapshot-id", "snapshot-old")
                    .containsEntry("x-analysis-direction", "INCOMING")
                    .containsEntry("x-analysis-outcome", "VERIFIED")
                    .containsEntry("x-analysis-assessed-id", "UA-1000")
                    .containsEntry("x-analysis-assessed-quote", "requirement text")
                    .containsEntry("x-analysis-assessed-contribution", "Assessed contribution")
                    .containsEntry("x-analysis-candidate-id", "CP-1000")
                    .containsEntry("x-analysis-candidate-quote", "Old requirement")
                    .containsEntry("x-analysis-candidate-condition", "Approved only")
                    .containsEntry("x-analysis-necessity", "REQUIRED");
        });
    }

    @Test void aChangedWitnessOnTheSameDirectedGraphInvalidatesPreviewAndANewlyAdoptedView() throws Exception {
        var edge = frozenEdge();
        var request = request(ref, null);
        var first = service.preview(target, request);
        edge.setRequirementEvidence(List.of(witness("CP-1000", "Old", "Approved only")));
        assertCode(() -> service.adopt(target, new AnalysisSnapshotAdoptionService.Request(
                request.context(), request.metadata(), ref, first.fingerprint())), "PREVIEW_CHANGED");
        var second = service.preview(target, request);
        service.adopt(target, new AnalysisSnapshotAdoptionService.Request(
                request.context(), request.metadata(), ref, second.fingerprint()));
        edge.setRequirementEvidence(List.of(witness("CP-1000", "Old", "Another condition")));
        assertThat(service.preview(target, request(ref, null)).summary().conflicts()).hasSize(1);
        assertThat(fixture.journal.read(target).operations()).hasSize(1);
    }

    @Test void refusesAMisdirectedWitnessOrAQuoteAbsentFromTheFrozenRequirement() throws Exception {
        var edge = frozenEdge();
        edge.setRequirementEvidence(List.of(witness("UA-1000", "Old", "")));
        assertCode(() -> service.preview(target, request(ref, null)), "INVALID_SNAPSHOT_EVIDENCE");
        edge.setRequirementEvidence(List.of(witness("CP-1000", "A different requirement", "")));
        assertCode(() -> service.preview(target, request(ref, null)), "INVALID_SNAPSHOT_EVIDENCE");
        assertThat(fixture.journal.read(target)).isNull();
    }

    @Test void malformedUtf16CannotAliasAPreviewAndValidSupplementaryEvidenceSurvivesGit() throws Exception {
        var edge = frozenEdge();
        edge.setPresenceReason("Proof?");
        var request = request(ref, null);
        var literal = service.preview(target, request);
        var acceptedRequest = new AnalysisSnapshotAdoptionService.Request(request.context(), request.metadata(), ref, literal.fingerprint());
        for (char unpaired : new char[]{(char) 0xd800, (char) 0xdc00}) {
            edge.setPresenceReason("Proof" + unpaired);
            assertCode(() -> service.adopt(target, acceptedRequest), "INVALID_SNAPSHOT_EVIDENCE");
            assertThat(fixture.journal.read(target)).isNull();
        }
        String exact = "Proof" + Character.toString(0x1f4da);
        edge.setPresenceReason(exact);
        var valid = service.preview(target, request);
        assertThat(valid.fingerprint()).isNotEqualTo(literal.fingerprint());
        service.adopt(target, new AnalysisSnapshotAdoptionService.Request(request.context(), request.metadata(), ref, valid.fingerprint()));
        var checkpointRequest = request(ref, null);
        var checkpoint = fixture.service.checkpoint(target, new CreateCheckpointCommand(checkpointRequest.context(), checkpointRequest.metadata()));
        String persisted = fixture.repositories.resolveRepository(target).getDslAtCommit(checkpoint.commitId());
        var model = new com.taxonomy.dsl.command.ArchitectureDslCommands().model(persisted);
        assertThat(model.getEvidence().getFirst().getExtensions()).containsEntry("x-analysis-presence", exact);
    }

    @Test void preservesAnExplicitZeroConfidenceOnANewEdgeAndItsSourceEvidence() throws Exception {
        var edge = frozenEdge(); edge.setConfidence(0d);
        var request = request(ref, null);
        var preview = service.preview(target, request);
        var adopted = service.adopt(target, new AnalysisSnapshotAdoptionService.Request(
                request.context(), request.metadata(), ref, preview.fingerprint()));
        var model = new com.taxonomy.dsl.command.ArchitectureDslCommands().model(adopted.accepted().change().dsl());
        assertThat(model.getRelations().getFirst().getConfidence()).isEqualTo(0d);
        assertThat(model.getEvidence().getFirst().getConfidence()).isEqualTo(0d);
    }

    @Test void previouslyAdoptedCommandReopensTheOriginalRevisionWithoutBackfillingRecoveredWitnesses() throws Exception {
        var edge = frozenEdge();
        var initial = request(ref, null);
        var preview = service.preview(target, initial);
        var dsl = new com.taxonomy.dsl.command.ArchitectureDslCommands();
        var model = dsl.model(preview.preview().change().dsl());
        var provenance = new TreeMap<>(model.getViews().getFirst().getExtensions());
        String graphDigest = provenance.remove("x-analysis-graph-fingerprint");
        provenance.remove("x-analysis-evidence-fingerprint");
        provenance.remove("x-analysis-relation-members");
        var elements = model.getElements().stream().map(e -> new com.taxonomy.dsl.command.ArchitectureCommand.SnapshotElement(
                e.getId(), e.getType(), e.getTitle(), e.getDescription())).toList();
        var key = new com.taxonomy.dsl.command.ArchitectureCommand.RelationKey(edge.getSourceCode(), edge.getRelationType(), edge.getTargetCode());
        var legacy = new com.taxonomy.dsl.command.ArchitectureCommand.AdoptAnalysisSnapshot(preview.viewId(),
                model.getViews().getFirst().getTitle(), "Old requirement text", graphDigest, elements, List.of(key), provenance);
        var accepted = fixture.service.execute(target, new Command(initial.context(), initial.metadata(), new SemanticCommand(legacy)));
        String before = accepted.change().dsl();
        var next = request(ref, null);
        var existing = service.preview(target, next);
        assertThat(existing.preview().change().dsl()).isEqualTo(before);
        assertThat(service.adopt(target, new AnalysisSnapshotAdoptionService.Request(next.context(), next.metadata(), ref, existing.fingerprint())).existing()).isTrue();
        assertThat(fixture.journal.read(target).operations()).hasSize(1);
        assertThat(fixture.service.read(target, null).dsl()).isEqualTo(before);
        assertThat(dsl.model(before).getEvidence()).isEmpty();
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
