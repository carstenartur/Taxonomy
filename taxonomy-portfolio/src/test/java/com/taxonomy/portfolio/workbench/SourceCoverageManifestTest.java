package com.taxonomy.portfolio.workbench;

import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.dto.RequirementElementView;
import com.taxonomy.dto.RequirementRelationshipView;
import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.portfolio.dto.PortfolioDtos.ProjectView;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementVersionView;
import com.taxonomy.portfolio.dto.PortfolioDtos.SnapshotDetail;
import com.taxonomy.portfolio.dto.PortfolioDtos.SnapshotSummary;
import com.taxonomy.portfolio.dto.PortfolioDtos.SourceReference;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus;
import com.taxonomy.portfolio.service.PortfolioAnalysisPersistenceService;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.PortfolioJsonCodec;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.portfolio.workbench.SourceCoverageManifest.Gap;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceDslReadPort;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static com.taxonomy.portfolio.workbench.SourceCoverageManifest.GapCode.*;
import static com.taxonomy.portfolio.workbench.SourceCoverageManifest.GapState.MISSING;
import static com.taxonomy.portfolio.workbench.SourceCoverageManifest.GapState.STALE;
import static com.taxonomy.workspace.service.WorkspaceDslReadPort.CommitRelationship.ANCESTOR;
import static com.taxonomy.workspace.service.WorkspaceDslReadPort.CommitRelationship.UNRELATED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SourceCoverageManifestTest {
    private static final String ANALYSIS_COMMIT = "a".repeat(40);
    private static final String REVIEW_COMMIT = "b".repeat(40);
    private static final String HEAD = "c".repeat(40);
    private static final String SNAPSHOT = "snapshot-flood-warning";
    private static final String ROUTE = "/api/projects/15/architecture-workbench/" + SNAPSHOT + ".source-coverage.json";
    private static final String TEXT = "Display the current flood warning status.";
    private static final String ORIGINAL = "Publish the current flood warning status on a public dashboard.";
    private static final String TEXT_HASH = StableIdentityHash.sha256(TEXT);
    private static final Instant REVIEWED_AT = Instant.parse("2026-11-19T13:14:15Z");
    private static final RepositoryContext SOURCE = RepositoryContext.workspace("flood-repo", "review-space", "warning-review", "alice");
    private static final SourceReference ORIGINAL_REFERENCE = new SourceReference(31L, 47L, List.of(), "Warning bulletin", 4, ORIGINAL);

    private final PortfolioAnalysisPersistenceService snapshots = mock(PortfolioAnalysisPersistenceService.class);
    private final ProjectPortfolioService projects = mock(ProjectPortfolioService.class);
    private final WorkspaceDslReadPort repositories = mock(WorkspaceDslReadPort.class);
    private final WorkspaceDslReadPort.RepositoryRead git = mock(WorkspaceDslReadPort.RepositoryRead.class);
    private final SourceCoverageManifestService service = new SourceCoverageManifestService(snapshots, projects,
            repositories, new PortfolioJsonCodec(JsonMapper.builder().build()));

    @BeforeEach
    void authorizeSnapshotScopeAndExactVersion() {
        var context = WorkspaceResolver.workspaceContext(SOURCE);
        when(projects.getProject(15L, "alice", context)).thenReturn(new ProjectView(15L, "FLOOD", "Flood warnings", null,
                ProjectStatus.PLANNING, "alice", SOURCE.workspaceId(), null, null, null, null, REVIEWED_AT, REVIEWED_AT, 1, 0, 0));
        version(ORIGINAL_REFERENCE);
        when(repositories.openRead(SOURCE)).thenReturn(git);
    }

    @Test
    void downloadsAStableExampleAndKeepsTheAnalysisCheckpointSeparateFromTheReviewCheckpoint() throws Exception {
        retain(ANALYSIS_COMMIT, "CP-1");
        when(git.currentHead()).thenReturn(HEAD);
        when(git.relationshipsTo(HEAD, List.of(ANALYSIS_COMMIT))).thenReturn(List.of(ANCESTOR));
        when(git.relationshipsTo(HEAD, List.of(REVIEW_COMMIT))).thenReturn(List.of(ANCESTOR));
        when(git.dslAtCommit(ANALYSIS_COMMIT)).thenReturn(Optional.of(document(ORIGINAL_REFERENCE) + element("CP-1")));
        String reviewed = document(ORIGINAL_REFERENCE) + element("CP-1") + mapping("CP-1", SNAPSHOT, "CONFIRMED");
        when(git.dslAtCommit(REVIEW_COMMIT)).thenReturn(Optional.of(reviewed));

        var resolver = mock(WorkspaceResolver.class);
        when(resolver.resolveUnambiguousRepositoryForRead()).thenReturn(SOURCE);
        var mvc = MockMvcBuilders.standaloneSetup(new SourceCoverageManifestController(service, resolver)).build();
        String sample = mvc.perform(get(ROUTE).param("checkpoint", REVIEW_COMMIT))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"source-coverage.json\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.scope.repositoryId").value("flood-repo"))
                .andExpect(jsonPath("$.scope.branch").value("warning-review"))
                .andExpect(jsonPath("$.requirementVersion.versionId").value(7))
                .andExpect(jsonPath("$.requirementVersion.versionNumber").value(2))
                .andExpect(jsonPath("$.requirementVersion.contentSha256").value(TEXT_HASH))
                .andExpect(jsonPath("$.requirementVersion.semanticRequirementId").value("flood-warning"))
                .andExpect(jsonPath("$.sourcePassages[0].evidence").value("RECORDED_ORIGINAL"))
                .andExpect(jsonPath("$.sourcePassages[0].sourceVersionId").value(47))
                .andExpect(jsonPath("$.sourcePassages[0].pageNumber").value(4))
                .andExpect(jsonPath("$.sourcePassages[0].originalTextSha256").value(StableIdentityHash.sha256(ORIGINAL)))
                .andExpect(jsonPath("$.snapshot.id").value(SNAPSHOT))
                .andExpect(jsonPath("$.snapshot.analysisCommitSha").value(ANALYSIS_COMMIT))
                .andExpect(jsonPath("$.checkpoint.commitSha").value(REVIEW_COMMIT))
                .andExpect(jsonPath("$.checkpoint.documentSha256").value(StableIdentityHash.sha256(reviewed)))
                .andExpect(jsonPath("$.checkpoint.selection").value("EXPLICIT"))
                .andExpect(jsonPath("$.decisions[0].mappingKey").value("mapping:flood-warning -> CP-1"))
                .andExpect(jsonPath("$.decisions[0].reviewStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.decisions[0].architectureId").value("CP-1"))
                .andExpect(jsonPath("$.decisions[0].actor").value("alice"))
                .andExpect(jsonPath("$.gaps").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(mvc.perform(get(ROUTE).param("checkpoint", REVIEW_COMMIT))
                .andReturn().getResponse().getContentAsString()).isEqualTo(sample);

        mvc.perform(get(ROUTE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.checkpoint.commitSha").value(ANALYSIS_COMMIT))
                .andExpect(jsonPath("$.checkpoint.selection").value("ANALYSIS_RECORD"))
                .andExpect(jsonPath("$.decisions").isEmpty())
                .andExpect(jsonPath("$.gaps[0].code").value("DECISION_NOT_CHECKPOINTED"));
        verify(git, never()).dslAtCommit(HEAD);
    }

    @Test
    void recordsStableGapsForStalePassagesAndDecisionsAndForMissingSemanticElements() throws Exception {
        retain(ANALYSIS_COMMIT, "CP-4", "CP-2", "CP-1", "CP-3", "CP-5");
        var recorded = new SourceReference(31L, 47L, List.of(203L, 201L), null, null, null);
        version(new SourceReference(31L, 47L, List.of(202L, 201L), null, null, null));
        when(git.currentHead()).thenReturn(HEAD);
        when(git.relationshipsTo(HEAD, List.of(REVIEW_COMMIT))).thenReturn(List.of(ANCESTOR));
        when(git.dslAtCommit(REVIEW_COMMIT)).thenReturn(Optional.of(document(recorded)
                + element("CP-1") + mapping("CP-1", SNAPSHOT, "CONFIRMED")
                + element("CP-2") + mapping("CP-2", "old-snapshot", "CONFIRMED")
                + element("CP-3") + mapping("CP-3", SNAPSHOT, "PROPOSED")
                + mapping("CP-4", SNAPSHOT, "REJECTED") + element("CP-5")));

        var manifest = service.load(15L, SNAPSHOT, REVIEW_COMMIT, SOURCE);

        assertThat(manifest.sourcePassages()).extracting(SourceCoverageManifest.Passage::fragmentId).containsExactly(201L, 202L);
        assertThat(manifest.decisions()).extracting(SourceCoverageManifest.Decision::architectureId).containsExactly("CP-1");
        assertThat(manifest.gaps()).containsExactly(
                new Gap(STALE, SOURCE_PASSAGE_MISMATCH, "requirementVersion:FLOOD WARN 2", null),
                new Gap(STALE, DECISION_SNAPSHOT_MISMATCH, "mapping:flood-warning -> CP-2", "old-snapshot"),
                new Gap(MISSING, DECISION_EVIDENCE_INCOMPLETE, "mapping:flood-warning -> CP-3", "PROPOSED"),
                new Gap(MISSING, SEMANTIC_ELEMENT_NOT_CHECKPOINTED, "CP-4", "mapping:flood-warning -> CP-4"),
                new Gap(MISSING, DECISION_NOT_CHECKPOINTED, "mapping:flood-warning -> CP-5", null),
                new Gap(MISSING, RELATION_DECISION_NOT_CHECKPOINTED, "relation:CP-1 REALIZES CP-2", null));
    }

    @Test
    void keepsMissingSourceAndUnrelatedCheckpointExplicitWithoutReadingBranchTipContent() throws Exception {
        retain(null, "CP-1");
        version(null);
        var noCheckpoint = service.load(15L, SNAPSHOT, null, SOURCE);
        assertThat(noCheckpoint.gaps()).extracting(Gap::code).containsExactly(CHECKPOINT_NOT_RECORDED, SOURCE_PASSAGE_NOT_RECORDED);
        assertThat(noCheckpoint.snapshot().candidateArchitectureIds()).containsExactly("CP-1");
        verifyNoInteractions(repositories);

        when(git.currentHead()).thenReturn(HEAD);
        when(git.relationshipsTo(HEAD, List.of(REVIEW_COMMIT))).thenReturn(List.of(UNRELATED));
        var unrelated = service.load(15L, SNAPSHOT, REVIEW_COMMIT, SOURCE);
        assertThat(unrelated.gaps()).extracting(Gap::code).containsExactly(CHECKPOINT_UNAVAILABLE, SOURCE_PASSAGE_NOT_RECORDED);
        assertThat(unrelated.checkpoint().documentSha256()).isNull();
        verify(git, never()).dslAtCommit(anyString());
    }

    @Test
    void refusesTheWrongBranchBeforeReadingGitAndRejectsAbbreviatedCommitIds() {
        retain(ANALYSIS_COMMIT, "CP-1");
        assertThatThrownBy(() -> service.load(15L, SNAPSHOT, "abc", SOURCE)).isInstanceOf(PortfolioException.class)
                .hasMessage("checkpoint must be a full Git object ID");
        var wrongBranch = RepositoryContext.workspace(SOURCE.repositoryId(), SOURCE.workspaceId(), "other-branch", "alice");
        when(snapshots.getSnapshot(15L, SNAPSHOT, "alice", WorkspaceResolver.workspaceContext(wrongBranch)))
                .thenReturn(snapshot(ANALYSIS_COMMIT, "CP-1"));
        assertThatThrownBy(() -> service.load(15L, SNAPSHOT, REVIEW_COMMIT, wrongBranch)).isInstanceOf(PortfolioException.class)
                .hasMessage("Analysis snapshot not found in the selected source");
        verifyNoInteractions(repositories);
    }

    private void retain(String commit, String... candidates) {
        when(snapshots.getSnapshot(15L, SNAPSHOT, "alice", WorkspaceResolver.workspaceContext(SOURCE)))
                .thenReturn(snapshot(commit, candidates));
    }

    private static SnapshotDetail snapshot(String commit, String... candidates) {
        var view = new RequirementArchitectureView();
        view.setIncludedElements(Arrays.stream(candidates).map(code -> {
            var element = new RequirementElementView();
            element.setNodeCode(code);
            return element;
        }).toList());
        if (candidates.length > 1) {
            var relation = new RequirementRelationshipView();
            relation.setSourceCode("CP-1");
            relation.setRelationType("REALIZES");
            relation.setTargetCode("CP-2");
            view.setIncludedRelationships(List.of(relation));
        }
        var analysis = new AnalysisResult();
        analysis.setArchitectureView(view);
        var summary = new SnapshotSummary(SNAPSHOT, 15L, 5L, "WARN", 7L, 2, "job-flood",
                AnalysisStatus.SUCCESS, "MOCK", "fixture", "taxonomy-fingerprint", "prompt-fingerprint",
                SOURCE.workspaceId(), SOURCE.branch(), commit, REVIEWED_AT.minusSeconds(500), 100, 0, null);
        return new SnapshotDetail(summary, analysis, null, null, null, List.of(), List.of());
    }

    private void version(SourceReference reference) {
        when(projects.findRequirementVersion(15L, 5L, 7L, "alice", WorkspaceResolver.workspaceContext(SOURCE)))
                .thenReturn(Optional.of(new RequirementVersionView(7L, 2, TEXT, TEXT_HASH, null, "alice",
                        REVIEWED_AT.minusSeconds(800), reference)));
    }

    private static String document(SourceReference source) {
        String sourceFields = "  sourceArtifactId: \"31\";\n  sourceVersionId: \"47\";\n  sourceFragmentIds: \""
                + source.sourceFragmentIds() + "\";\n";
        if (source.originalText() != null) sourceFields += "  sectionReference: \"Warning bulletin\";\n  pageNumber: \"4\";\n"
                + "  originalText: \"" + ORIGINAL + "\";\n";
        return """
                requirementVersion FLOOD WARN 2 {
                  x-portfolio-managed: "true";
                  text: "%s";
                  contentHash: "%s";
                %s}
                requirement flood-warning {
                  x-portfolio-managed: "true";
                  x-project-key: "FLOOD";
                  x-requirement-key: "WARN";
                  x-version: "2";
                  text: "%s";
                  x-content-hash: "%s";
                }
                """.formatted(TEXT, TEXT_HASH, sourceFields, TEXT, TEXT_HASH);
    }

    private static String element(String id) {
        return "element " + id + " type Capability {\n  title: \"Flood warning capability\";\n}\n";
    }

    private static String mapping(String target, String snapshot, String status) {
        return """
                mapping flood-warning -> %s {
                  x-portfolio-managed: "true";
                  source: "%s";
                  x-review-status: "%s";
                  x-decision-by: "alice";
                  x-decision-at: "%s";
                }
                """.formatted(target, snapshot, status, REVIEWED_AT);
    }
}
