package com.taxonomy.architecture.workbench;

import com.taxonomy.architecture.workbench.ArchitectureWorkbenchDtos.ElementMetadata;
import com.taxonomy.architecture.workbench.ArchitectureWorkbenchDtos.Projection;
import com.taxonomy.architecture.workbench.ArchitectureWorkbenchDtos.RelationMetadata;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramScene;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.export.DiagramProjectionService;
import com.taxonomy.export.LayeredDiagramLayoutService;
import com.taxonomy.export.PersistedDiagramProjection;
import com.taxonomy.export.SvgDiagramRenderer;
import com.taxonomy.portfolio.dto.PortfolioDtos.ElementMappingView;
import com.taxonomy.portfolio.dto.PortfolioDtos.ProjectView;
import com.taxonomy.portfolio.dto.PortfolioDtos.RelationMappingView;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementVersionView;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;
import com.taxonomy.portfolio.dto.PortfolioDtos.SnapshotDetail;
import com.taxonomy.portfolio.service.PortfolioAnalysisPersistenceService;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Produces the single server-authoritative projection consumed by browser, SVG and PDF.
 *
 * <p>No LLM call is made here. The workbench replays an immutable analysis snapshot.</p>
 */
@Service
public class ArchitectureWorkbenchService {

    private final PortfolioAnalysisPersistenceService persistenceService;
    private final ProjectPortfolioService projectService;
    private final DiagramProjectionService diagramProjectionService;
    private final LayeredDiagramLayoutService layoutService;
    private final SvgDiagramRenderer svgRenderer;
    private final ArchitecturePdfRenderer pdfRenderer;

    public ArchitectureWorkbenchService(
            PortfolioAnalysisPersistenceService persistenceService,
            ProjectPortfolioService projectService,
            DiagramProjectionService diagramProjectionService,
            LayeredDiagramLayoutService layoutService,
            SvgDiagramRenderer svgRenderer,
            ArchitecturePdfRenderer pdfRenderer) {
        this.persistenceService = persistenceService;
        this.projectService = projectService;
        this.diagramProjectionService = diagramProjectionService;
        this.layoutService = layoutService;
        this.svgRenderer = svgRenderer;
        this.pdfRenderer = pdfRenderer;
    }

    @Transactional(readOnly = true)
    public Projection load(Long projectId,
                           String snapshotId,
                           String username,
                           WorkspaceContext context) {
        if (snapshotId == null || snapshotId.isBlank()) {
            throw PortfolioException.validation("snapshotId is required");
        }

        SnapshotDetail snapshot = persistenceService.getSnapshot(
                projectId, snapshotId.strip(), username, context);
        AnalysisResult analysis = snapshot.analysis();
        RequirementArchitectureView architectureView =
                analysis != null ? analysis.getArchitectureView() : null;
        if (architectureView == null
                || architectureView.getIncludedElements() == null
                || architectureView.getIncludedElements().isEmpty()) {
            throw PortfolioException.conflict(
                    "Snapshot " + snapshotId
                            + " contains no persisted architecture view. "
                            + "Re-run the requirement analysis with Architecture View enabled.");
        }

        ProjectView project = projectService.getProject(projectId, username, context);
        Long requirementId = snapshot.summary().requirementId();
        RequirementView requirement = projectService.getRequirement(
                projectId, requirementId, username, context);
        RequirementVersionView version = projectService
                .listRequirementVersions(projectId, requirementId, username, context)
                .stream()
                .filter(candidate -> candidate.id().equals(snapshot.summary().requirementVersionId()))
                .findFirst()
                .orElseThrow(() -> PortfolioException.notFound(
                        "Requirement version for snapshot was not found: "
                                + snapshot.summary().requirementVersionId()));

        String fallbackTitle = project.projectKey() + " / " + requirement.requirementKey()
                + " — " + requirement.title();
        String persistedTitle = architectureView.getViewTitle();
        String title = persistedTitle == null || persistedTitle.isBlank()
                ? fallbackTitle
                : persistedTitle.strip();
        DiagramModel diagram = PersistedDiagramProjection.project(
                diagramProjectionService, architectureView, title);
        if (diagram.nodes() == null || diagram.nodes().isEmpty()) {
            throw PortfolioException.conflict(
                    "The persisted architecture view contains no exportable architecture elements");
        }
        DiagramScene scene = layoutService.layout(diagram);

        Map<String, ElementMetadata> elements = new LinkedHashMap<>();
        for (ElementMappingView mapping : safe(snapshot.elementMappings())) {
            elements.put(mapping.nodeCode(), new ElementMetadata(
                    mapping.nodeCode(),
                    mapping.nodeTitle(),
                    mapping.taxonomyRoot(),
                    mapping.directScore(),
                    mapping.relevance(),
                    mapping.confidence(),
                    mapping.mappingOrigin() != null ? mapping.mappingOrigin().name() : null,
                    mapping.hierarchyPath(),
                    mapping.presenceReason(),
                    mapping.selectedForImpact(),
                    mapping.reviewStatus(),
                    mapping.actionStatus(),
                    mapping.actionEvidence(),
                    mapping.decisionBy(),
                    mapping.decisionAt(),
                    mapping.decisionComment()));
        }

        Map<String, RelationMetadata> relations = new LinkedHashMap<>();
        for (RelationMappingView mapping : safe(snapshot.relationMappings())) {
            RelationMetadata metadata = new RelationMetadata(
                    mapping.sourceCode(),
                    mapping.targetCode(),
                    mapping.relationType(),
                    mapping.relationOrigin(),
                    mapping.relationCategory(),
                    mapping.relevance(),
                    mapping.confidence(),
                    mapping.presenceReason(),
                    mapping.reviewStatus(),
                    mapping.decisionBy(),
                    mapping.decisionAt(),
                    mapping.decisionComment());
            relations.put(metadata.signature(), metadata);
        }

        Set<String> warnings = new LinkedHashSet<>();
        if (analysis.getWarnings() != null) warnings.addAll(analysis.getWarnings());
        if (architectureView.getNotes() != null) warnings.addAll(architectureView.getNotes());
        warnings.removeIf(value -> value == null || value.isBlank());

        return new Projection(
                projectId,
                project.projectKey(),
                project.title(),
                requirementId,
                requirement.requirementKey(),
                requirement.title(),
                version.text(),
                snapshot.summary().id(),
                snapshot.summary().status(),
                snapshot.summary().createdAt(),
                snapshot.summary().provider(),
                snapshot.summary().modelName(),
                snapshot.summary().workspaceId(),
                snapshot.summary().branchName(),
                snapshot.summary().commitSha(),
                diagram,
                scene,
                elements,
                relations,
                new ArrayList<>(warnings));
    }

    @Transactional(readOnly = true)
    public String renderSvg(Long projectId,
                            String snapshotId,
                            String username,
                            WorkspaceContext context) {
        return svgRenderer.render(load(projectId, snapshotId, username, context).scene());
    }

    @Transactional(readOnly = true)
    public byte[] renderPdf(Long projectId,
                            String snapshotId,
                            String username,
                            WorkspaceContext context) {
        return pdfRenderer.render(load(projectId, snapshotId, username, context));
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
