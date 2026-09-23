from pathlib import Path
import shutil

HERE = Path(__file__).resolve().parent

def replace(path, old, new):
    p=Path(path); text=p.read_text()
    assert text.count(old)==1, (path, old[:100], text.count(old))
    p.write_text(text.replace(old,new))

shutil.copyfile(HERE/'EvidenceProjectionContinuationTest.java', 'taxonomy-analysis/src/test/java/com/taxonomy/analysis/relations/EvidenceProjectionContinuationTest.java')
p=Path('.github/scripts/relation-search-confidence.test.mjs')
p.write_text(p.read_text()+(HERE/'score-ui-tests.mjs').read_text())

replace('taxonomy-domain/src/main/java/com/taxonomy/dto/RequirementElementView.java',
'    private int directLlmScore;', '''    private int directLlmScore;

    /**
     * Original typed assessment for requirement-scoped projections. Null means no
     * assessment was supplied, not a score of zero. Legacy numeric fields remain
     * compatible with existing indexes and diagram layout; this detail is authoritative.
     */
    private AnalysisScoreDetail scoreDetail;

    public AnalysisScoreDetail getScoreDetail() { return scoreDetail; }
    public void setScoreDetail(AnalysisScoreDetail scoreDetail) { this.scoreDetail = scoreDetail; }''')

replace('taxonomy-architecture/src/main/java/com/taxonomy/architecture/service/RequirementArchitectureViewService.java',
'''        return pipeline.projectEvidence(report, scores, maxNodes);
    }''', '''        return buildFromEvidence(scores, Map.of(), maxNodes, report);
    }

    /** Keeps original assessment provenance separate from comparable effective relevance. */
    public RequirementArchitectureView buildFromEvidence(Map<String,Integer> effectiveScores,
            Map<String,com.taxonomy.dto.AnalysisScoreDetail> scoreDetails, int maxNodes,
            com.taxonomy.dto.RelationSearchReport report) {
        return pipeline.projectEvidence(report, effectiveScores, scoreDetails, maxNodes);
    }''')

replace('taxonomy-architecture/src/main/java/com/taxonomy/architecture/pipeline/ArchitectureViewPipeline.java',
'''        ArchitectureViewContext context = new ArchitectureViewContext(scores, "", maxNodes, java.util.List.of());
        EvidenceRelationProjection.apply(context, report);''', '''        return projectEvidence(report, scores, java.util.Map.of(), maxNodes);
    }

    /** Untyped relevance alone must never be relabelled as an original model score. */
    public RequirementArchitectureView projectEvidence(com.taxonomy.dto.RelationSearchReport report,
            java.util.Map<String,Integer> effectiveScores,
            java.util.Map<String,com.taxonomy.dto.AnalysisScoreDetail> scoreDetails, int maxNodes) {
        ArchitectureViewContext context = new ArchitectureViewContext(effectiveScores, "", maxNodes, java.util.List.of());
        EvidenceRelationProjection.apply(context, report, scoreDetails);''')

replace('taxonomy-analysis/src/main/java/com/taxonomy/analysis/usecase/AnalyzeRequirementUseCase.java',
'architectureViewService.buildFromEvidence(result.getScores(), command.maxArchitectureNodes(),',
'architectureViewService.buildFromEvidence(result.getScores(), result.getScoreDetails(), command.maxArchitectureNodes(),')

replace('taxonomy-architecture/src/test/java/com/taxonomy/architecture/pipeline/EvidenceRelationProjectionContract.java',
'EvidenceRelationProjection.apply(c, report); return c;',
'EvidenceRelationProjection.apply(c, report, new AnalysisResult(Map.of("reader", 1), List.of()).getScoreDetails()); return c;')

replace('taxonomy-app/src/main/resources/static/js/portfolio/requirement-detail.js',
'    function renderMappings(mappings) {', '''    function snapshotScore(mapping, field) {
        const analysis = state.snapshotDetail?.analysis;
        if (!analysis?.relationSearchReport) {
            return field === 'rawScore' ? `${mapping.directScore}%` : `${Math.round(mapping.relevance * 100)}%`;
        }
        // The index uses numeric defaults. Only immutable analysis evidence can
        // establish whether this node was assessed and what its original score was.
        const value = analysis.scoreDetails?.[mapping.nodeCode]?.[field];
        return Number.isFinite(value) && value >= 0 && value <= 100 ? `${value}%` : '—';
    }

    function renderMappings(mappings) {''')
replace('taxonomy-app/src/main/resources/static/js/portfolio/requirement-detail.js',
'<td>${mapping.directScore}%</td><td>${Math.round(mapping.relevance * 100)}%</td>',
"<td>${snapshotScore(mapping, 'rawScore')}</td><td>${snapshotScore(mapping, 'effectiveRelevance')}</td>")

shutil.copyfile(HERE/'EvidenceRelationProjection.java','taxonomy-architecture/src/main/java/com/taxonomy/architecture/pipeline/EvidenceRelationProjection.java')
