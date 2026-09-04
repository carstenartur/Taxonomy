package com.taxonomy.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AnalysisResult {

    /**
     * Original node scores returned by analysis. Concrete PRODUCT entries are independent
     * suitability values and are retained separately from comparable relevance.
     */
    private Map<String, Integer> rawScores;

    /**
     * True after a new-format payload explicitly supplied {@code rawScores}. It makes JSON
     * deserialization order-independent when both legacy {@code scores} and {@code rawScores}
     * are present.
     */
    private transient boolean explicitRawScores;

    /** Version of the explicit score-semantics envelope. */
    private int scoreSemanticsVersion;

    /** Node code → typed interpretation of the original score. */
    private Map<String, AnalysisScoreDetail> scoreDetails = new LinkedHashMap<>();

    /** Node code → comparable 0–100 relevance used by generic downstream consumers. */
    private Map<String, Integer> effectiveScores = new LinkedHashMap<>();

    /** Concrete product code → original independent 0–100 suitability. */
    private Map<String, Integer> productSuitabilityScores = new LinkedHashMap<>();

    /** Bounded compatibility warnings produced while deriving semantics for legacy payloads. */
    private List<String> scoreSemanticsWarnings = new ArrayList<>();

    /** Original node-code → reason text returned by the scoring provider. */
    private Map<String, String> reasons = new LinkedHashMap<>();

    /** Effective provider used for this analysis (informational/provenance). */
    private String provider;

    private List<TaxonomyNodeDto> tree;

    /** "SUCCESS", "PARTIAL", or "ERROR" */
    private String status;

    /** Accumulated warning messages (e.g. which roots were skipped). */
    private List<String> warnings = new ArrayList<>();

    /** Human-readable error/partial message; set when status is PARTIAL or ERROR. */
    private String errorMessage;

    /** Optional architecture view built from relation-aware propagation. */
    private RequirementArchitectureView architectureView;

    /**
     * Scoring discrepancies detected during analysis. Each entry indicates
     * a case where the LLM's raw child scores exceeded the parent's budget,
     * signalling a potential taxonomy inconsistency.
     */
    private List<TaxonomyDiscrepancy> discrepancies = new ArrayList<>();

    /** Relevant product families for which no catalogued product met the suitability threshold. */
    private List<ProductCoverageGap> productCoverageGaps = new ArrayList<>();

    /** Provisional (not-yet-persisted) relation hypotheses generated from scores. */
    private List<RelationHypothesisDto> provisionalRelations = new ArrayList<>();

    /** Git commit provenance and projection/index freshness metadata. */
    private ViewContext viewContext;

    public AnalysisResult() {}

    public AnalysisResult(Map<String, Integer> rawScores, List<TaxonomyNodeDto> tree) {
        this.rawScores = rawScores;
        this.tree = tree;
        refreshScoreSemantics();
    }

    /**
     * Returns comparable relevance values. Existing generic consumers therefore cannot silently
     * rank conditional product suitability as though it were absolute hierarchical relevance.
     * Use {@link #getRawScores()} or {@link #getProductSuitabilityScores()} for scoring evidence.
     */
    public Map<String, Integer> getScores() {
        ensureScoreSemantics();
        return effectiveScores;
    }

    /**
     * Legacy JSON input. Old payloads contain only {@code scores}; new payloads also contain
     * {@code rawScores}, which takes precedence independently of property order.
     */
    public void setScores(Map<String, Integer> scores) {
        if (!explicitRawScores) {
            this.rawScores = scores;
        }
    }

    public Map<String, Integer> getRawScores() {
        return rawScores;
    }

    public void setRawScores(Map<String, Integer> rawScores) {
        this.rawScores = rawScores;
        this.explicitRawScores = true;
    }

    public int getScoreSemanticsVersion() {
        ensureScoreSemantics();
        return scoreSemanticsVersion;
    }
    public void setScoreSemanticsVersion(int scoreSemanticsVersion) {
        this.scoreSemanticsVersion = scoreSemanticsVersion;
    }

    public Map<String, AnalysisScoreDetail> getScoreDetails() {
        ensureScoreSemantics();
        return scoreDetails;
    }
    public void setScoreDetails(Map<String, AnalysisScoreDetail> scoreDetails) {
        this.scoreDetails = scoreDetails != null
                ? new LinkedHashMap<>(scoreDetails) : new LinkedHashMap<>();
    }

    public Map<String, Integer> getEffectiveScores() {
        ensureScoreSemantics();
        return effectiveScores;
    }
    public void setEffectiveScores(Map<String, Integer> effectiveScores) {
        this.effectiveScores = effectiveScores != null
                ? new LinkedHashMap<>(effectiveScores) : new LinkedHashMap<>();
    }

    public Map<String, Integer> getProductSuitabilityScores() {
        ensureScoreSemantics();
        return productSuitabilityScores;
    }
    public void setProductSuitabilityScores(Map<String, Integer> productSuitabilityScores) {
        this.productSuitabilityScores = productSuitabilityScores != null
                ? new LinkedHashMap<>(productSuitabilityScores) : new LinkedHashMap<>();
    }

    public List<String> getScoreSemanticsWarnings() {
        ensureScoreSemantics();
        return scoreSemanticsWarnings;
    }
    public void setScoreSemanticsWarnings(List<String> scoreSemanticsWarnings) {
        this.scoreSemanticsWarnings = scoreSemanticsWarnings != null
                ? new ArrayList<>(scoreSemanticsWarnings) : new ArrayList<>();
    }

    /** Rebuilds all derived score views from the original scores and frozen taxonomy tree. */
    public final void refreshScoreSemantics() {
        AnalysisScoreSemantics.Derived derived = AnalysisScoreSemantics.derive(rawScores, tree);
        scoreSemanticsVersion = AnalysisScoreSemantics.CURRENT_VERSION;
        scoreDetails = new LinkedHashMap<>(derived.scoreDetails());
        effectiveScores = new LinkedHashMap<>(derived.effectiveScores());
        productSuitabilityScores = new LinkedHashMap<>(derived.productSuitabilityScores());
        scoreSemanticsWarnings = new ArrayList<>(derived.warnings());
    }

    private void ensureScoreSemantics() {
        if (rawScores == null || rawScores.isEmpty()) {
            if (scoreDetails == null) scoreDetails = new LinkedHashMap<>();
            if (effectiveScores == null) effectiveScores = new LinkedHashMap<>();
            if (productSuitabilityScores == null) productSuitabilityScores = new LinkedHashMap<>();
            if (scoreSemanticsWarnings == null) scoreSemanticsWarnings = new ArrayList<>();
            return;
        }
        if (!hasCompleteScoreSemantics()) {
            refreshScoreSemantics();
        }
    }

    private boolean hasCompleteScoreSemantics() {
        if (scoreSemanticsVersion != AnalysisScoreSemantics.CURRENT_VERSION
                || scoreDetails == null || effectiveScores == null
                || !scoreDetails.keySet().containsAll(rawScores.keySet())
                || !effectiveScores.keySet().containsAll(rawScores.keySet())) {
            return false;
        }
        for (Map.Entry<String, Integer> entry : rawScores.entrySet()) {
            AnalysisScoreDetail detail = scoreDetails.get(entry.getKey());
            Integer effective = effectiveScores.get(entry.getKey());
            if (entry.getValue() == null || detail == null || effective == null
                    || detail.rawScore() != Math.max(0, Math.min(100, entry.getValue()))
                    || detail.effectiveRelevance() != effective) {
                return false;
            }
            if (detail.isProductSuitability()
                    && (productSuitabilityScores == null
                    || !productSuitabilityScores.containsKey(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    public Map<String, String> getReasons() { return reasons; }
    public void setReasons(Map<String, String> reasons) {
        this.reasons = reasons != null ? reasons : new LinkedHashMap<>();
    }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public List<TaxonomyNodeDto> getTree() { return tree; }
    public void setTree(List<TaxonomyNodeDto> tree) { this.tree = tree; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public List<String> getWarnings() { return warnings; }
    public void setWarnings(List<String> warnings) {
        this.warnings = warnings != null ? warnings : new ArrayList<>();
    }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public RequirementArchitectureView getArchitectureView() { return architectureView; }
    public void setArchitectureView(RequirementArchitectureView architectureView) { this.architectureView = architectureView; }

    public List<TaxonomyDiscrepancy> getDiscrepancies() { return discrepancies; }
    public void setDiscrepancies(List<TaxonomyDiscrepancy> discrepancies) {
        this.discrepancies = discrepancies != null ? discrepancies : new ArrayList<>();
    }

    public List<ProductCoverageGap> getProductCoverageGaps() { return productCoverageGaps; }
    public void setProductCoverageGaps(List<ProductCoverageGap> productCoverageGaps) {
        this.productCoverageGaps = productCoverageGaps != null
                ? productCoverageGaps : new ArrayList<>();
    }

    public List<RelationHypothesisDto> getProvisionalRelations() { return provisionalRelations; }
    public void setProvisionalRelations(List<RelationHypothesisDto> provisionalRelations) {
        this.provisionalRelations = provisionalRelations != null
                ? provisionalRelations : new ArrayList<>();
    }

    public ViewContext getViewContext() { return viewContext; }
    public void setViewContext(ViewContext viewContext) { this.viewContext = viewContext; }
}
