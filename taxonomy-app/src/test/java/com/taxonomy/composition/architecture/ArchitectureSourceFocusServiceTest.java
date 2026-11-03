package com.taxonomy.composition.architecture;

import com.taxonomy.analysis.cluster.*;
import com.taxonomy.analysis.recovery.*;
import com.taxonomy.analysis.session.*;
import com.taxonomy.analysis.service.*;
import com.taxonomy.dto.*;
import com.taxonomy.editor.ArchitectureEditorService;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.service.PortfolioAnalysisPersistenceService;
import com.taxonomy.workspace.model.*;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ArchitectureSourceFocusServiceTest {
    final ArchitectureEditorService editor = mock(ArchitectureEditorService.class);
    final LlmService llm = mock(LlmService.class);
    final AiPromptBudgetPolicy budget = mock(AiPromptBudgetPolicy.class);
    final AnalysisWorkingDraftService drafts = mock(AnalysisWorkingDraftService.class);
    final ClusterAnalysisObservation operations = mock(ClusterAnalysisObservation.class);
    final AnalysisContinuationService continuations = mock(AnalysisContinuationService.class);
    final PortfolioAnalysisPersistenceService snapshots = mock(PortfolioAnalysisPersistenceService.class);
    final UserWorkspaceRepository workspaces = mock(UserWorkspaceRepository.class);
    final SystemRepositoryService repositories = mock(SystemRepositoryService.class);
    final RepositoryMembershipService membership = mock(RepositoryMembershipService.class);
    final JsonMapper json = JsonMapper.builder().build();
    final RepositoryContext selected = RepositoryContext.workspace("repo", "copy", "draft", "alice");
    final WorkspaceContext scope = new WorkspaceContext("alice", "copy", "draft", "repo");
    final ArchitectureFocusSuggestionService engine = new ArchitectureFocusSuggestionService(editor,llm,budget);
    final ArchitectureSourceFocusService service = new ArchitectureSourceFocusService(engine,drafts,operations,continuations,snapshots,workspaces,repositories,membership,json);
    AnalysisResult retained;
    AnalysisDraftDtos.AnalysisDraftView draft;
    SnapshotDetail snapshot;
    ArchitectureSourceFocusDtos.Source draftSource = new ArchitectureSourceFocusDtos.Source("ANALYSIS_DRAFT","repo","copy","draft",4L,null,null,null,null);
    ArchitectureSourceFocusDtos.Source snapshotSource = new ArchitectureSourceFocusDtos.Source("ANALYSIS_SNAPSHOT","repo","__shared__","old",null,1L,2L,3L,"saved");

    @BeforeEach void setup() {
        var copy = new UserWorkspace(); copy.setWorkspaceId("copy"); copy.setUsername("alice"); copy.setSourceRepositoryId("repo"); copy.setCurrentBranch("draft");
        when(workspaces.findByWorkspaceId("copy")).thenReturn(Optional.of(copy));
        var repository = mock(SystemRepository.class); when(repositories.getRepository("repo")).thenReturn(repository);
        when(membership.canRead(repository,"alice")).thenReturn(true);
        var graph = new RequirementArchitectureView(); graph.setIncludedElements(List.of(node("CP-1000","CP","Retained order capability"),node("UA-1000","UA","Retained order application")));
        retained = new AnalysisResult(); retained.setArchitectureView(graph); retained.setAnalysisOperationId("operation"); retained.setStatus("PARTIAL");
        var payload = json.createObjectNode(); payload.put("draftState","ACTIVE");
        payload.set("analysisReference",json.createObjectNode().put("operationId","operation").put("workspaceId","copy"));
        payload.set("architectureView",json.createObjectNode().put("forged","Never use browser graph"));
        draft = new AnalysisDraftDtos.AnalysisDraftView("copy","draft",payload,4L,Instant.EPOCH);
        when(drafts.read("alice","copy")).thenReturn(Optional.of(draft));
        when(operations.request("operation","alice",scope)).thenReturn(new ClusterAnalysisObservation.RecoveryInput("operation","Retained requirement",null,null,new ClusterAnalysisView.ObservationScope("copy","repo","draft",null)));
        when(operations.result("operation","alice",scope)).thenReturn(retained);
        snapshot = new SnapshotDetail(new SnapshotSummary("saved",1L,2L,"REQ",3L,1,"job",AnalysisStatus.PARTIAL,"fixture","model","catalogue", "prompt",null,"old","commit",Instant.EPOCH,1,0,null),retained,null,null,null,List.of(),List.of());
        when(snapshots.getSnapshot(1L,"saved","alice",new WorkspaceContext("alice",null,"old","repo"))).thenReturn(snapshot);
    }
    @Test void canonicalDraftGraphIsInspectedWithoutEditorRevisionOrBrowserGraphAuthority() throws Exception {
        var before = draft.payload().deepCopy();
        var response = service.investigate(selected,request(draftSource,"CP-1000",false));
        assertThat(response.source()).isEqualTo(draftSource); assertThat(response.sourceHash()).matches("[a-f0-9]{64}");
        assertThat(response.outcome()).isEqualTo("LOCAL_CANDIDATES"); assertThat(response.suggestions()).isNotEmpty();
        assertThat(response.suggestions()).allSatisfy(s -> {
            assertThat(s.properties()).doesNotContainKey("x-focus-revision").containsEntry("x-focus-source-kind","ANALYSIS_DRAFT");
            assertThat(s.evidence()).allSatisfy(e -> assertThat(e.quote()).contains("Retained").doesNotContain("forged"));
        });
        assertThat(draft.payload()).isEqualTo(before); verifyNoInteractions(editor,llm,budget);
        verify(drafts,never()).save(any(),any(),any());
    }
    @Test void immutablePartialSnapshotUsesExactSavedPinsAndRecordedCatalogueWithoutWrites() throws Exception {
        var before = json.writeValueAsString(snapshot);
        var first = service.investigate(selected,request(snapshotSource,"CP-1000",false));
        assertThat(first.source()).isEqualTo(snapshotSource); assertThat(first.suggestions()).isNotEmpty();
        assertThat(service.investigate(selected,request(snapshotSource,"CP-1000",false)).sourceHash()).isEqualTo(first.sourceHash());
        assertThat(json.writeValueAsString(snapshot)).isEqualTo(before);
        verifyNoInteractions(editor,llm,budget,drafts);
    }
    @Test void staleVersionAndWrongBranchRejectBeforeReadingOrInferringCanonicalGraph() {
        assertCode(() -> service.investigate(selected,request(new ArchitectureSourceFocusDtos.Source("ANALYSIS_DRAFT","repo","copy","draft",3L,null,null,null,null),"CP-1000",true)),"CONTEXT_CHANGED");
        assertCode(() -> service.investigate(selected,request(new ArchitectureSourceFocusDtos.Source("ANALYSIS_DRAFT","repo","copy","other",4L,null,null,null,null),"CP-1000",true)),"SOURCE_IDENTITY_MISMATCH");
        verifyNoInteractions(operations,continuations,llm,budget,editor);
    }
    @Test void foreignOwnershipAndSnapshotPinsRejectBeforeInference() {
        var copy = new UserWorkspace(); copy.setUsername("bob"); copy.setSourceRepositoryId("repo");
        when(workspaces.findByWorkspaceId("copy")).thenReturn(Optional.of(copy));
        assertCode(() -> service.investigate(selected,request(draftSource,"CP-1000",true)),"NOT_FOUND");
        var wrong = new ArchitectureSourceFocusDtos.Source("ANALYSIS_SNAPSHOT","repo","__shared__","old",null,1L,2L,99L,"saved");
        assertCode(() -> service.investigate(selected,request(wrong,"CP-1000",true)),"SOURCE_IDENTITY_MISMATCH");
        verifyNoInteractions(llm,budget,operations,editor,drafts);
    }
    @Test void unknownElementAndUnreferencedOrForgedReferenceNeverSpendProviderCalls() {
        assertCode(() -> service.investigate(selected,request(draftSource,"guessed",true)),"NOT_FOUND");
        ((tools.jackson.databind.node.ObjectNode)draft.payload()).remove("analysisReference");
        assertCode(() -> service.investigate(selected,request(draftSource,"CP-1000",true)),"SOURCE_UNAVAILABLE");
        ((tools.jackson.databind.node.ObjectNode)draft.payload()).set("analysisReference",json.createObjectNode().put("operationId","operation").put("workspaceId","foreign"));
        assertCode(() -> service.investigate(selected,request(draftSource,"CP-1000",true)),"SOURCE_IDENTITY_MISMATCH");
        verifyNoInteractions(llm,budget,editor);
    }
    @Test void canonicalReferenceSubstitutionAndMalformedGraphRejectBeforeInference() {
        retained.setAnalysisOperationId("different-operation");
        assertCode(() -> service.investigate(selected,request(draftSource,"CP-1000",true)),"SOURCE_IDENTITY_MISMATCH");
        retained.setAnalysisOperationId("operation"); retained.getArchitectureView().getIncludedElements().getFirst().setTaxonomySheet("UA");
        assertCode(() -> service.investigate(selected,request(draftSource,"CP-1000",true)),"SOURCE_IDENTITY_MISMATCH");
        verifyNoInteractions(llm,budget,editor);
    }
    @Test void payloadAndGraphBoundsRejectBeforeInference() {
        retained.getArchitectureView().setIncludedElements(Collections.nCopies(2001,node("CP-1000","CP","Retained")));
        assertCode(() -> service.investigate(selected,request(draftSource,"CP-1000",true)),"ITEM_LIMIT");
        ((tools.jackson.databind.node.ObjectNode)draft.payload()).put("extra","x".repeat(2_000_001));
        assertCode(() -> service.investigate(selected,request(draftSource,"CP-1000",true)),"ITEM_LIMIT");
        verifyNoInteractions(llm,budget,editor);
    }
    @Test void browserGraphEditsCannotAlterCanonicalSourceHashButRetainedEvidenceCan() throws Exception {
        var first = service.investigate(selected,request(draftSource,"CP-1000",false));
        ((tools.jackson.databind.node.ObjectNode)draft.payload()).set("architectureView",json.createObjectNode().put("title","Different forged graph"));
        assertThat(service.investigate(selected,request(draftSource,"CP-1000",false)).sourceHash()).isEqualTo(first.sourceHash());
        retained.getArchitectureView().getIncludedElements().getFirst().setTitle("Changed retained capability");
        assertThat(service.investigate(selected,request(draftSource,"CP-1000",false)).sourceHash()).isNotEqualTo(first.sourceHash());
        verifyNoInteractions(editor,llm,budget);
    }
    @Test void continuationReferencesRemainCanonicalAndRetainTheirExactRecoveryVersion() throws Exception {
        ((tools.jackson.databind.node.ObjectNode)draft.payload().path("analysisReference")).put("continuationId","continuation").put("recoveryVersion",9L);
        var input = new AnalysisRequest(); input.setBusinessText("Retained continuation input");
        when(continuations.readEvidence("continuation","alice",scope)).thenReturn(new AnalysisContinuationStore.Snapshot(
                new AnalysisRecoveryView("continuation",9L,"PAUSED",1,1,null,List.of()),retained,input,"operation"));
        var result = service.investigate(selected,request(draftSource,"CP-1000",false));
        assertThat(result.suggestions()).isNotEmpty().allSatisfy(suggestion -> assertThat(suggestion.properties())
                .containsEntry("x-focus-continuation-id","continuation").containsEntry("x-focus-continuation-version","9"));
        verifyNoInteractions(operations,editor,llm,budget);
        verify(continuations,never()).read(any(),any(),any());
    }
    @Test void disagreeingContinuationAndOperationReferencesRejectBeforeInference() {
        ((tools.jackson.databind.node.ObjectNode)draft.payload().path("analysisReference")).put("continuationId","continuation").put("recoveryVersion",9L);
        when(continuations.readEvidence("continuation","alice",scope)).thenReturn(new AnalysisContinuationStore.Snapshot(
                new AnalysisRecoveryView("continuation",9L,"PAUSED",1,1,null,List.of()),retained,new AnalysisRequest(),"other-operation"));
        assertCode(() -> service.investigate(selected,request(draftSource,"CP-1000",true)),"SOURCE_IDENTITY_MISMATCH");
        verifyNoInteractions(operations,editor,llm,budget);
    }
    @Test void incompleteAnalysisAndMissingGraphsRemainUnavailableRatherThanAssessedNegatives() {
        retained.setStatus("IN_PROGRESS");
        assertCode(() -> service.investigate(selected,request(draftSource,"CP-1000",true)),"SOURCE_UNAVAILABLE");
        retained.setStatus("PARTIAL"); retained.setArchitectureView(null);
        assertCode(() -> service.investigate(selected,request(draftSource,"CP-1000",true)),"SOURCE_UNAVAILABLE");
        verifyNoInteractions(editor,llm,budget);
    }
    @Test void everySnapshotIdentityPinIsCheckedBeforeInferenceAndMembershipBeforeSnapshotRead() {
        when(snapshots.getSnapshot(anyLong(),anyString(),eq("alice"),any())).thenReturn(snapshot);
        for (var wrong : List.of(
                new ArchitectureSourceFocusDtos.Source("ANALYSIS_SNAPSHOT","repo","__shared__","old",null,9L,2L,3L,"saved"),
                new ArchitectureSourceFocusDtos.Source("ANALYSIS_SNAPSHOT","repo","__shared__","old",null,1L,9L,3L,"saved"),
                new ArchitectureSourceFocusDtos.Source("ANALYSIS_SNAPSHOT","repo","__shared__","other",null,1L,2L,3L,"saved"),
                new ArchitectureSourceFocusDtos.Source("ANALYSIS_SNAPSHOT","repo","__shared__","old",null,1L,2L,3L,"other"),
                new ArchitectureSourceFocusDtos.Source("ANALYSIS_SNAPSHOT","repo","copy","old",null,1L,2L,3L,"saved"))) {
            assertCode(() -> service.investigate(selected,request(wrong,"CP-1000",true)),"SOURCE_IDENTITY_MISMATCH");
        }
        clearInvocations(snapshots);
        when(membership.canRead(any(),eq("alice"))).thenReturn(false);
        assertCode(() -> service.investigate(selected,request(snapshotSource,"CP-1000",true)),"NOT_FOUND");
        verifyNoInteractions(snapshots,editor,llm,budget);
    }
    @Test void sourceMockProviderRemainsExplicitlyUnassessedWithoutGeneratingOrWriting() throws Exception {
        when(llm.isMockMode()).thenReturn(true);
        var result = service.investigate(selected,request(draftSource,"CP-1000",true));
        assertThat(result.outcome()).isEqualTo("UNASSESSED"); assertThat(result.modelCalls()).isZero();
        assertThat(result.method()).isEqualTo("LOCAL_TEXT_UNASSESSED:MOCK_PROVIDER");
        assertThat(result.suggestions()).isNotEmpty().allSatisfy(suggestion -> assertThat(suggestion.properties()).doesNotContainKey("x-focus-revision"));
        verify(llm,never()).callLlmRaw(anyString(),any()); verifyNoInteractions(editor,budget);
    }
    @Test void canonicalSourceUsesTheSameBudgetedGenerativeAssessmentEngineWithTypedEvidenceValidation() throws Exception {
        when(llm.supportsGenerativeCompletion()).thenReturn(true); when(llm.getActiveProviderName()).thenReturn("fixture-provider");
        when(llm.getActiveProviderId()).thenReturn(new com.taxonomy.extension.api.llm.ProviderId("FIXTURE"));
        when(budget.requireWithinBudget(anyString(),eq("FIXTURE"))).thenReturn(new com.taxonomy.analysis.dto.AiTargetDtos.AiTargetDescriptor(
                "fixture","Fixture","FIXTURE","model",com.taxonomy.analysis.dto.AiTargetDtos.AiTargetMode.REMOTE,
                com.taxonomy.analysis.dto.AiTargetDtos.AiTargetHealth.READY,true,false,false,
                new com.taxonomy.analysis.dto.AiTargetDtos.PromptBudget(120_000,120_000,30_000),"fixture",null));
        when(llm.callLlmRaw(anyString(),any())).thenReturn("{\"suggestions\":[]}");
        var response = service.investigate(selected,request(draftSource,"CP-1000",true));
        assertThat(response.outcome()).isEqualTo("ASSESSED"); assertThat(response.modelCalls()).isEqualTo(1);
        var prompt = org.mockito.ArgumentCaptor.forClass(String.class); verify(llm).callLlmRaw(prompt.capture(),any());
        assertThat(prompt.getValue()).contains("CP-1000","UA-1000","Retained order").doesNotContain("Never use browser graph");
        verifyNoInteractions(editor);
    }
    @Test void unchangedDraftVersionCannotAuthorizeANewerMutableContinuationResult() {
        ((tools.jackson.databind.node.ObjectNode)draft.payload().path("analysisReference")).put("continuationId","continuation").put("recoveryVersion",9L);
        var input = new AnalysisRequest(); input.setBusinessText("Retained input");
        when(continuations.readEvidence("continuation","alice",scope)).thenReturn(new AnalysisContinuationStore.Snapshot(
                new AnalysisRecoveryView("continuation",10L,"PAUSED",1,1,null,List.of()),retained,input,"operation"));
        assertCode(() -> service.investigate(selected,request(draftSource,"CP-1000",true)),"CONTEXT_CHANGED");
        verifyNoInteractions(editor,llm,budget,operations);
    }
    @Test void missingOrMalformedContinuationRecoveryPinsNeverReadLatestEvidenceOrSpendInference() {
        var reference = (tools.jackson.databind.node.ObjectNode)draft.payload().path("analysisReference");
        reference.put("continuationId","continuation");
        assertCode(() -> service.investigate(selected,request(draftSource,"CP-1000",true)),"SOURCE_UNAVAILABLE");
        for (var pin : List.<tools.jackson.databind.JsonNode>of(json.valueToTree(-1),json.valueToTree(1.5),json.valueToTree("9"),json.valueToTree("9999999999999999999999999"))) {
            reference.set("recoveryVersion",pin);
            assertCode(() -> service.investigate(selected,request(draftSource,"CP-1000",true)),"SOURCE_UNAVAILABLE");
        }
        verifyNoInteractions(continuations,editor,llm,budget,operations);
    }
    private ArchitectureSourceFocusDtos.Request request(ArchitectureSourceFocusDtos.Source source,String id,boolean ai) {
        return new ArchitectureSourceFocusDtos.Request(source,id,8,ai,"en");
    }
    private RequirementElementView node(String id,String root,String title) {
        var node = new RequirementElementView(); node.setNodeCode(id); node.setTaxonomySheet(root); node.setTitle(title); return node;
    }
    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem.class,e -> assertThat(e.code()).isEqualTo(code));
    }
}
