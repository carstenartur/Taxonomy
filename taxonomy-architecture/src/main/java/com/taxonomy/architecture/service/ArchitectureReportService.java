package com.taxonomy.architecture.service;

import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.dto.*;
import com.taxonomy.export.DiagramProjectionService;
import com.taxonomy.export.DiagramViewMetadata;
import com.taxonomy.export.MermaidExportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import com.taxonomy.dto.ArchitectureRecommendation;
import com.taxonomy.dto.ArchitectureReport;
import com.taxonomy.dto.DetectedPattern;
import com.taxonomy.dto.GapAnalysisView;
import com.taxonomy.dto.IncompletePattern;
import com.taxonomy.dto.MissingRelation;
import com.taxonomy.dto.PatternDetectionView;
import com.taxonomy.dto.RecommendedElement;
import com.taxonomy.dto.RelationProposalDto;
import com.taxonomy.dto.RequirementAnchor;
import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.dto.SuggestedRelation;
import com.taxonomy.relations.service.RelationProposalService;

/**
 * Aggregates data from existing services and produces an {@link ArchitectureReport},
 * which can then be rendered as Markdown, HTML, or DOCX.
 */
@Service
public class ArchitectureReportService {

    private static final Logger log = LoggerFactory.getLogger(ArchitectureReportService.class);
    private static final int DEFAULT_MIN_SCORE = 20;

    private final RequirementArchitectureViewService architectureViewService;
    private final ArchitectureGapService gapService;
    private final ArchitecturePatternService patternService;
    private final ArchitectureRecommendationService recommendationService;
    private final DiagramProjectionService diagramProjectionService;
    private final MermaidExportService mermaidExportService;
    private final RelationProposalService proposalService;
    private final ArchitectureReportMetadataPort metadataPort;

    public ArchitectureReportService(RequirementArchitectureViewService architectureViewService,
                                      ArchitectureGapService gapService,
                                      ArchitecturePatternService patternService,
                                      ArchitectureRecommendationService recommendationService,
                                      DiagramProjectionService diagramProjectionService,
                                      MermaidExportService mermaidExportService,
                                      RelationProposalService proposalService,
                                      ArchitectureReportMetadataPort metadataPort) {
        this.architectureViewService = architectureViewService;
        this.gapService = gapService;
        this.patternService = patternService;
        this.recommendationService = recommendationService;
        this.diagramProjectionService = diagramProjectionService;
        this.mermaidExportService = mermaidExportService;
        this.proposalService = proposalService;
        this.metadataPort = metadataPort;
    }

    /**
     * Generates a full architecture report by calling all existing analysis services.
     *
     * @param scores       nodeCode → score map (0–100)
     * @param businessText the business requirement text
     * @param minScore     minimum score threshold (0 → default 20)
     * @return populated {@link ArchitectureReport}
     */
    @Transactional(readOnly = true)
    public ArchitectureReport generateReport(Map<String, Integer> scores,
                                              String businessText, int minScore) {
        ArchitectureReport report = new ArchitectureReport();
        report.setBusinessText(businessText);
        report.setScores(scores != null ? scores : Map.of());
        report.setGeneratedAt(Instant.now());

        int threshold = minScore > 0 ? minScore : DEFAULT_MIN_SCORE;
        Map<String, Integer> safeScores = scores != null ? scores : Map.of();

        // 1. Architecture View
        RequirementArchitectureView archView = architectureViewService.build(
                safeScores, businessText, 20);
        DiagramViewMetadata meta = metadataPort.resolve();
        archView.setViewTitle(meta.viewTitle());
        archView.setViewDescription(meta.viewDescription());
        archView.setContainmentEnabled(meta.containmentEnabled());
        archView.setActiveRules(meta.activeRules());
        report.setArchitectureView(archView);

        // 2. Gap Analysis
        GapAnalysisView gaps = gapService.analyze(safeScores, businessText, threshold);
        report.setGapAnalysis(gaps);

        // 3. Pattern Detection
        PatternDetectionView patterns = patternService.detectForScores(safeScores, threshold);
        report.setPatternDetection(patterns);

        // 4. Recommendation
        ArchitectureRecommendation recommendation = recommendationService.recommend(
                safeScores, businessText, threshold);
        report.setRecommendation(recommendation);

        // 5. Pending Proposals
        List<RelationProposalDto> pending = proposalService.getPendingProposals();
        report.setPendingProposals(pending);

        // 6. Mermaid Diagram
        String title = businessText != null && businessText.length() > 60
                ? businessText.substring(0, 57) + "..."
                : (businessText != null ? businessText : "Report");
        DiagramModel diagram = diagramProjectionService.project(archView, title);
        String mermaid = mermaidExportService.export(diagram);
        report.setMermaidDiagram(mermaid);

        log.info("Report generated: {} anchors, {} gaps, {} patterns, {} recommendations",
                archView.getTotalAnchors(),
                gaps.getTotalGaps(),
                patterns.getMatchedPatterns().size(),
                recommendation.getConfirmedElements().size());

        return report;
    }

}
