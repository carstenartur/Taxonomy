package com.taxonomy.portfolio.service;

import com.taxonomy.portfolio.dto.PortfolioDtos;
import com.taxonomy.portfolio.model.PortfolioTypes;
import com.taxonomy.portfolio.model.RequirementAnalysisSnapshot;
import com.taxonomy.portfolio.model.RequirementElementMapping;
import com.taxonomy.portfolio.repository.RequirementAnalysisSnapshotRepository;
import com.taxonomy.portfolio.repository.RequirementElementMappingRepository;
import com.taxonomy.portfolio.repository.RequirementSolutionLinkRepository;
import com.taxonomy.portfolio.repository.SolutionProductCandidateRepository;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.taxonomy.portfolio.service.PortfolioReportService.Format;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PortfolioReportReadingTest {
    private static final WorkspaceContext CONTEXT = new WorkspaceContext("reviewer", "workspace", "draft", "repository");
    private static final String SCOPE = PortfolioScope.key(CONTEXT.username(), CONTEXT);
    private static final Instant CAPTURED = Instant.parse("2026-08-22T14:30:00Z");
    private static final String DIGEST = "0123456789abcdef".repeat(4);
    private static final String EVIDENCE = "Saved scope conflict <evidence>";
    private final JsonMapper json = JsonMapper.builder().build();
    private PortfolioReportService service;

    @BeforeEach
    void setUp() {
        var projects = mock(ProjectPortfolioService.class);
        var solutions = mock(SolutionPortfolioService.class);
        var conflicts = mock(ProjectConflictService.class);
        var mappings = mock(RequirementElementMappingRepository.class);
        var snapshots = mock(RequirementAnalysisSnapshotRepository.class);
        var project = new PortfolioDtos.ProjectView(9L, "PROJECT", "Excellent project title", "", null,
                "reviewer", "workspace", null, null, null, null, CAPTURED, CAPTURED, 2, 0, 1);
        var first = requirement(1L, "REQ-ONE", "First requirement <retained>");
        var second = requirement(2L, "REQ-TWO", "Unrelated requirement body");
        when(projects.getProject(9L, CONTEXT.username(), CONTEXT)).thenReturn(project);
        when(projects.listRequirements(9L, CONTEXT.username(), CONTEXT)).thenReturn(List.of(first, second));
        when(conflicts.list(9L, CONTEXT.username(), CONTEXT)).thenReturn(List.of(new PortfolioDtos.ConflictView(
                23L, 9L, first.id(), first.requirementKey(), second.id(), second.requirementKey(),
                PortfolioTypes.ConflictType.SECURITY, PortfolioTypes.ConflictStatus.CONFIRMED, "Conflict title",
                EVIDENCE, 0.75, "Retained reviewer note", CAPTURED, "reviewer", CAPTURED,
                PortfolioDtos.ConflictConfidenceBasis.UNCALIBRATED_RULE_WEIGHT)));
        var mappingRows = List.of(mapping(first, "BP-1000", "Shared & scored", .125, .00125),
                mapping(first, "BR-1000", "Evaluated zero", 0, 0),
                mapping(second, "BP-1000", "Shared & scored", 83, .83),
                mapping(second, "CP-1023", "Only in the other requirement", 42, .42));
        when(mappings.findCurrentMappingsForProject(9L)).thenReturn(mappingRows);
        when(mappings.findCurrentMappingsForProject(9L, SCOPE)).thenReturn(mappingRows);
        for (var item : List.of(first, second)) {
            var snapshot = mock(RequirementAnalysisSnapshot.class, RETURNS_DEEP_STUBS);
            when(snapshot.getId()).thenReturn(item.currentAnalysisSnapshotId());
            when(snapshot.getCreatedAt()).thenReturn(CAPTURED);
            when(snapshot.getRequirementVersion().getId()).thenReturn(item.currentVersionId());
            when(snapshot.getRequirementVersion().getVersionNumber()).thenReturn(3);
            when(snapshot.getProvider()).thenReturn("MOCK");
            when(snapshot.getModelName()).thenReturn("fixture-model");
            when(snapshot.getTaxonomyFingerprint()).thenReturn(DIGEST);
            when(snapshots.findById(item.currentAnalysisSnapshotId())).thenReturn(Optional.of(snapshot));
            when(snapshots.findByIdAndProjectIdAndScopeKey(item.currentAnalysisSnapshotId(), 9L, SCOPE))
                    .thenReturn(Optional.of(snapshot));
        }
        var aggregation = new PortfolioAggregationService(projects, solutions, conflicts, mappings,
                mock(RequirementSolutionLinkRepository.class), mock(SolutionProductCandidateRepository.class), snapshots, 30);
        service = new PortfolioReportService(aggregation, snapshots, json, mock(ProjectProductGapReviewService.class));
    }

    @Test
    void htmlShowsNarrativeTablesAndWordKeepsTheConflictEvidence() throws Exception {
        String html = render(null, Format.HTML);
        String narrative = html.substring(0, html.indexOf("Machine-readable baseline"));
        assertThat(narrative).contains("<table", "<th", "<td", "Shared &amp; scored", "Snapshot-REQ-ONE",
                DIGEST, CAPTURED.toString(), "Saved scope conflict &lt;evidence&gt;")
                .doesNotContain("<evidence>");
        byte[] word = service.render(9L, null, Format.DOCX, "taxonomy", CONTEXT.username(), CONTEXT).bytes();
        try (var document = new XWPFDocument(new ByteArrayInputStream(word));
                var extractor = new XWPFWordExtractor(document)) {
            assertThat(extractor.getText()).contains(EVIDENCE, DIGEST, CAPTURED.toString());
        }
    }

    @Test
    void requirementScopeUsesOnlyItsOwnMappingsEvenWhenTheTaxonomyNodeIsShared() {
        String markdown = render(1L, Format.MARKDOWN);
        assertThat(markdown).contains("Requirements: 1", "Analysed: 1", "0.125%", "0%")
                .doesNotContain("Unrelated requirement body", "CP-1023", "83%");
        var model = json.readTree(render(1L, Format.JSON));
        assertThat(model.path("metrics").path("totalRequirements").asInt()).isEqualTo(1);
        assertThat(model.path("taxonomyNodes").get(0).path("maximumDirectScore").asDouble()).isEqualTo(.125);
        assertThat(model.path("taxonomyNodes").get(0).path("averageRelevance").asDouble()).isEqualTo(.00125);
        assertThat(model.path("requirementTaxonomyMatrix").path("rows").get(0).asString()).isEqualTo("REQ-ONE");
        assertThat(model.path("requirementTaxonomyMatrix").path("rows").size()).isEqualTo(1);
        assertThat(render(1L, Format.CSV)).doesNotContain("REQ-TWO", "CP-1023");
    }

    @Test
    void matrixCsvDistinguishesAnAbsentCellFromARecordedZeroInTheSameRow() {
        assertThat(render(null, Format.CSV)).contains("row,BP-1000,BR-1000,CP-1023\n", "REQ-ONE,0.125,0.0,\n");
    }

    private String render(Long requirementId, Format format) {
        return new String(service.render(9L, requirementId, format, "taxonomy", CONTEXT.username(), CONTEXT).bytes(),
                StandardCharsets.UTF_8);
    }

    private static PortfolioDtos.RequirementView requirement(long id, String key, String title) {
        var version = new PortfolioDtos.RequirementVersionView(id + 10, 3, title, DIGEST, "reviewed", "reviewer", CAPTURED, null);
        return new PortfolioDtos.RequirementView(id, 9L, key, title, null, 1,
                null, null, PortfolioTypes.ReviewStatus.CONFIRMED, "reviewer", version.id(), "Snapshot-" + key,
                CAPTURED, CAPTURED, version);
    }

    private static RequirementElementMapping mapping(PortfolioDtos.RequirementView requirement, String id,
            String title, double score, double relevance) {
        var mapping = mock(RequirementElementMapping.class, RETURNS_DEEP_STUBS);
        when(mapping.getNodeCode()).thenReturn(id);
        when(mapping.getNodeTitle()).thenReturn(title);
        when(mapping.getTaxonomyRoot()).thenReturn(id.substring(0, 2));
        when(mapping.getSnapshot().getId()).thenReturn(requirement.currentAnalysisSnapshotId());
        when(mapping.getSnapshot().getRequirementId()).thenReturn(requirement.id());
        when(mapping.getSnapshot().getRequirement().getId()).thenReturn(requirement.id());
        when(mapping.getSnapshot().getRequirement().getRequirementKey()).thenReturn(requirement.requirementKey());
        when(mapping.getActionStatus()).thenReturn(PortfolioTypes.ActionStatus.UNDECIDED);
        when(mapping.getDirectScore()).thenReturn(score);
        when(mapping.getRelevance()).thenReturn(relevance);
        return mapping;
    }
}
