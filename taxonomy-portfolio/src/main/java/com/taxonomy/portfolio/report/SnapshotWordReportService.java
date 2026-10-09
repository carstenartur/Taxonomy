package com.taxonomy.portfolio.report;

import com.taxonomy.architecture.report.ArchitectureReportSelection;

import com.taxonomy.reporting.render.decision.DecisionReportLabels;
import com.taxonomy.reporting.render.document.ArchitectureFigurePlanner;

import com.taxonomy.reporting.api.decision.DecisionRationaleReport;
import com.taxonomy.reporting.api.decision.DecisionReportOptions;
import com.taxonomy.reporting.api.document.ArchitectureReportDocument;
import com.taxonomy.reporting.render.document.ArchitectureReportDocuments;

import com.taxonomy.architecture.decision.*;
import com.taxonomy.reporting.render.document.*;
import com.taxonomy.diagram.*;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.workbench.ArchitectureWorkbenchDtos.Projection;
import com.taxonomy.portfolio.workbench.ArchitectureWorkbenchService;
import com.taxonomy.workspace.service.WorkspaceContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Composes only saved decision and persisted graph evidence at identical source coordinates. */
@Service
public class SnapshotWordReportService {
    public record Source(
            DecisionRationaleReport decision, ArchitectureReportDocument architecture) {}

    private final DecisionRationaleSnapshotReportService decisions;
    private final ArchitectureWorkbenchService workbench;

    public SnapshotWordReportService(
            DecisionRationaleSnapshotReportService decisions,
            ArchitectureWorkbenchService workbench) {
        this.decisions = decisions;
        this.workbench = workbench;
    }

    @Transactional(readOnly = true)
    public Source load(
            Long projectId,
            String snapshotId,
            String username,
            WorkspaceContext context,
            Locale locale) {
        return load(projectId, snapshotId, username, context, locale, null);
    }

    @Transactional(readOnly = true)
    public Source load(Long projectId, String snapshotId, String username, WorkspaceContext context,
            Locale locale, DecisionReportOptions options) {
        return loadSource(projectId, snapshotId, username, context, locale, options, true);
    }

    /** HTML/JSON share frozen graph evidence without Word's page/rendering ceilings. */
    @Transactional(readOnly = true)
    public Source loadEvidence(Long projectId, String snapshotId, String username, WorkspaceContext context,
            Locale locale, DecisionReportOptions options) {
        return loadSource(projectId, snapshotId, username, context, locale, options, false);
    }

    private Source loadSource(Long projectId, String snapshotId, String username, WorkspaceContext context,
            Locale locale, DecisionReportOptions options, boolean wordLayout) {
        if (projectId == null) throw PortfolioException.validation("projectId is required");
        if (snapshotId == null || snapshotId.isBlank())
            throw PortfolioException.validation("snapshotId is required");
        String id = snapshotId.strip();
        Locale language = locale == null ? Locale.ENGLISH : locale;
        var decision = options == null ? decisions.generate(projectId, id, username, context, language)
                : decisions.generate(projectId, id, username, context, language, options);
        if (options != null && !options.includes(DecisionReportOptions.Section.ARCHITECTURE)) return new Source(decision, null);
        if (decision.scope().analysisScope() != null && !decision.scope().analysisScope().includesRelations())
            return new Source(decision, null);
        var projection = options == null ? workbench.load(projectId, id, username, context)
                : workbench.loadIfPresent(projectId, id, username, context).orElse(null);
        if (projection == null && options != null) return new Source(decision, null);
        try {
            var architecture = frozenDocument(projectId, id, decision, projection, language, wordLayout);
            return new Source(decision.withArchitecture(architecture), architecture);
        } catch (IllegalArgumentException exception) {
            throw PortfolioException.conflict(exception.getMessage());
        }
    }

    private ArchitectureReportDocument frozenDocument(
            Long projectId,
            String snapshotId,
            DecisionRationaleReport decision,
            Projection p,
            Locale locale, boolean wordLayout) {
        var m = decision.metadata();
        if (m == null || p == null || p.exportProvenance() == null)
            throw new IllegalArgumentException("Missing frozen snapshot provenance");
        same("project", projectId, m.projectId());
        same("project", m.projectId(), p.projectId());
        same("snapshot", snapshotId, m.analysisSnapshotId());
        same("snapshot", m.analysisSnapshotId(), p.snapshotId());
        same("requirement", m.requirementId(), p.requirementId());
        same(
                "requirement version",
                m.requirementVersionId(),
                p.exportProvenance().requirementVersionId());
        same(
                "requirement text",
                decision.requirement(),
                p.requirementText() == null ? null : p.requirementText().strip());
        same("workspace", workspace(m.workspaceId()), workspace(p.workspaceId()));
        same("branch", normalized(m.branch()), normalized(p.branchName()));
        same("commit", normalized(m.basedOnCommit()), normalized(p.commitSha()));
        same(
                "repository",
                normalized(m.repositoryId()),
                normalized(p.exportProvenance().repositoryId()));
        same("provider", normalized(m.analysisProvider()), normalized(p.provider()));
        same("model", normalized(m.analysisModel()), normalized(p.modelName()));
        same(
                "taxonomy fingerprint",
                normalized(m.recordedTaxonomyFingerprintSha256()),
                normalized(p.exportProvenance().taxonomyFingerprint()));
        if (!m.hierarchyFromImmutableSnapshot())
            throw new IllegalArgumentException(
                    "Decision hierarchy is not frozen snapshot evidence");
        ArchitectureFigurePlanner.validateEvidence(p.diagram(), p.scene());
        var labels = new DecisionReportLabels(locale.toLanguageTag());
        // Explicit saved titles are evidence, including values that collide with a live fallback.
        // Policy keys and missing titles use a stable localized snapshot title.
        String title = labels.architectureTitle();
        String graphTitle =
                p.persistedViewTitle() != null
                                && !p.persistedViewTitle().isBlank()
                                && p.policyTitleKey() == null
                        ? p.persistedViewTitle()
                        : title;
        var graph =
                new DiagramModel(
                        graphTitle, p.diagram().nodes(), p.diagram().edges(), p.diagram().layout());
        var scene =
                new DiagramScene(
                        graphTitle,
                        p.scene().width(),
                        p.scene().height(),
                        p.scene().direction(),
                        p.scene().nodes(),
                        p.scene().edges());
        String reportScope = labels.frozenScope();
        if (!decision.scope().options().taxonomyRoots().isEmpty()
                || (decision.scope().analysisScope() != null
                    && !decision.scope().analysisScope().taxonomyRoots().isEmpty())) {
            var selection = ArchitectureReportSelection.select(graph, scene, decision.scope().selectedNodeCodes());
            graph = selection.graph(); scene = selection.scene();
            if (graph.nodes().isEmpty()) return null;
            boolean german = "de".equals(locale.getLanguage());
            reportScope += " · " + (german ? "Berichtsauswahl: " : "Report selection: ")
                    + String.join(", ", decision.scope().reportRoots())
                    + " · " + (german ? "Kontext außerhalb der Auswahl: " : "Boundary context: ")
                    + String.join(", ", selection.boundaryCodes())
                    + " · Source graph SHA-256: " + selection.sourceGraphSha256();
        }
        var gaps = new LinkedHashSet<String>();
        gaps.addAll(decision.warnings());
        gaps.addAll(p.warnings());
        decision.productCoverageGaps()
                .forEach(g -> gaps.add(g.productFamilyCode() + " · " + g.reason()));
        var evidence =
                new ArchitectureReportDocument.SnapshotEvidence(
                        m.projectId(),
                        m.requirementId(),
                        m.requirementVersionId(),
                        m.requirementVersionNumber(),
                        m.analysisSnapshotId(),
                        m.repositoryId(),
                        m.workspaceId(),
                        m.branch(),
                        m.basedOnCommit(),
                        m.analysisProvider(),
                        m.analysisModel(),
                        m.recordedTaxonomyFingerprintSha256(),
                        ArchitectureReportDocument.graphSha256(graph));
        var document =
                ArchitectureReportDocuments.from(
                        title,
                        locale.toLanguageTag(),
                        decision.requirement(),
                        reportScope,
                        decision.executiveSummary() == null
                                ? labels.noneRecorded()
                                : decision.executiveSummary().conciseConclusion(),
                        List.copyOf(gaps),
                        graph,
                        scene,
                        decision.scope().decisionTree(),
                        evidence);
        // Enforce the complete document ceiling before allocating any PNG or DOCX buffers.
        if (wordLayout) new ArchitectureFigurePlanner().plan(graph, scene);
        return document;
    }

    private static void same(String field, Object a, Object b) {
        if (!Objects.equals(a, b))
            throw new IllegalArgumentException("Frozen snapshot " + field + " disagreement");
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? "unknown" : value.strip();
    }

    private static String workspace(String value) {
        return value == null
                        || value.isBlank()
                        || "Central".equals(value)
                        || "Zentral".equals(value)
                ? "central"
                : value;
    }
}
