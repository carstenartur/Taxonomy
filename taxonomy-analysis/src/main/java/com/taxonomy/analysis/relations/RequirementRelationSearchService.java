package com.taxonomy.analysis.relations;

import com.taxonomy.analysis.service.*;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.dto.RelationSearchReport;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.taxonomy.dto.RelationSearchModel.*;

/** Spring adapter; scalar catalogue reads end before any remote model evaluation. */
@Service
public class RequirementRelationSearchService {
    private final TaxonomyService catalogue;
    private final RelationCompatibilityMatrix rules;
    private final LlmService llm;
    private final AiPromptBudgetPolicy promptBudget;

    @Value("${taxonomy.analysis.relations.hierarchical.enabled:true}")
    private boolean enabled = true;
    @Value("${taxonomy.analysis.relations.hierarchical.max-calls:24}")
    private int maxCalls = 24;
    @Value("${taxonomy.analysis.relations.hierarchical.max-depth:8}")
    private int maxDepth = 8;
    @Value("${taxonomy.analysis.relations.hierarchical.batch-size:10}")
    private int batchSize = 10;
    @Value("${taxonomy.analysis.relations.hierarchical.max-work-items:512}")
    private int maxWorkItems = 512;
    @Value("${taxonomy.analysis.relations.hierarchical.max-sources:32}")
    private int maxSources = 32;

    public RequirementRelationSearchService(TaxonomyService catalogue, RelationCompatibilityMatrix rules,
                                            LlmService llm, AiPromptBudgetPolicy promptBudget) {
        this.catalogue = catalogue; this.rules = rules; this.llm = llm; this.promptBudget = promptBudget;
    }
    public boolean isEnabled() { return enabled; }
    @PostConstruct
    void validate() { options(); }
    private RequirementRelationSearch.Options options() {
        return new RequirementRelationSearch.Options(new Limits(maxCalls, maxDepth, batchSize, maxWorkItems), maxSources);
    }

    public RelationSearchReport search(String original, Map<String,Integer> scores) {
        if (!llm.supportsGenerativeCompletion()) {
            return RequirementRelationSearch.unassessed(original, options().limits().maxCalls(),
                    "GENERATION_UNSUPPORTED: " + llm.getActiveProviderId().value()
                            + " provides embeddings, not requirement-scoped relation assessments; "
                            + "all relationships remain unassessed.");
        }
        var adapter = new RequirementRelationSearch.InputCatalogue() {
            private final Map<String, List<Node>> children = new HashMap<>();
            public Node find(String id) { return scalar(catalogue.getNodeByCode(id)); }
            public List<Node> roots() { return scalars(catalogue.getRootNodes()); }
            public List<Node> children(Node node) {
                // Retain only successfully materialized immutable scalars, including
                // empty lists. This adapter and its cache belong to one search.
                return children.computeIfAbsent(node.id(), id -> scalars(catalogue.getChildrenOf(id)));
            }
        };
        return new RequirementRelationSearch(adapter, rules, this::complete, AnalysisRunControl::checkpoint, llm.getActiveProviderName())
                .search(original, scores, options());
    }

    /** Source preparation must receive the admission snapshot; there is no live-catalogue fallback. */
    public RelationSearchDistribution.Plan prepare(String preparationId, String original, Map<String, Integer> scores,
            List<String> sourceResultIds, RequirementRelationSearch.InputCatalogue frozenCatalogue,
            boolean includeRelations) {
        // Version-two transport identities admit ordinals 0..511. Retain the
        // complete intent plan, but bound distributed admission independently of
        // a larger local continuation setting; excess work remains WORK_LIMIT.
        var configured = options();
        var limits = configured.limits();
        var distributed = new RequirementRelationSearch.Options(new Limits(limits.maxCalls(), limits.maxDepth(),
                limits.batchSize(), Math.min(512, limits.maxWorkItems())), configured.maxSources());
        var engine = new RequirementRelationSearch(Objects.requireNonNull(frozenCatalogue), rules, this::complete,
                AnalysisRunControl::checkpoint, llm.getActiveProviderName());
        if (includeRelations && !llm.supportsGenerativeCompletion()) {
            var disabled = engine.prepare(preparationId, original, scores, sourceResultIds, distributed, false);
            String reason = "GENERATION_UNSUPPORTED: " + llm.getActiveProviderId().value()
                    + " provides embeddings, not requirement-scoped relation assessments; "
                    + "all relationships remain unassessed.";
            return new RelationSearchDistribution.Plan(disabled.schemaVersion(), disabled.preparationId(),
                    disabled.originalSha256(), disabled.sourceResultIds(), disabled.options(), List.of(), List.of(),
                    List.of(), List.of(), 0, disabled.durationMillis(), List.of(reason), reason, true);
        }
        return engine.prepare(preparationId, original, scores, sourceResultIds, distributed, includeRelations);
    }

    /** Target workers load only the exact frozen target shard and the persisted preparation. */
    public RelationSearchDistribution.WorkResult evaluate(String original, RelationSearchDistribution.Plan plan,
            RelationSearchDistribution.Work work, RequirementRelationSearch.InputCatalogue frozenTargetCatalogue) {
        if (!llm.supportsGenerativeCompletion()) throw new IllegalStateException("GENERATION_UNSUPPORTED");
        return new RequirementRelationSearch(Objects.requireNonNull(frozenTargetCatalogue), rules, this::complete,
                AnalysisRunControl::checkpoint, llm.getActiveProviderName()).evaluate(original, plan, work);
    }

    public String recoveryPolicyFingerprint() {
        return "relation-downwalk-v2/complete-plan/resumable-exchanges/" + enabled + "/" + options();
    }

    private String complete(String prompt) {
        String provider = llm.getActiveProviderName();
        // Presentation labels (notably Custom OpenAI-compatible and Local ONNX)
        // are not stable provider keys. Preserve them in logs, not in budget lookup.
        promptBudget.requireWithinBudget(prompt, llm.getActiveProviderId().value());
        return AnalysisRunControl.call(provider, "RELATION_SEARCH", () -> {
            LlmCallDetail detail = new LlmCallDetail();
            detail.setPrompt(prompt); detail.setProvider(provider);
            String raw = llm.callLlmRaw(prompt);
            detail.setRawResponse(raw);
            if (raw == null || raw.isBlank()) detail.setError("Missing relation-search response");
            return detail;
        }).getRawResponse();
    }

    private List<Node> scalars(List<TaxonomyNode> nodes) {
        Map<String, String> descriptions = catalogue.getAssessmentDescriptions(nodes);
        return nodes.stream().map(node -> scalar(node,
                descriptions.getOrDefault(node.getCode(), node.getDescriptionEn()))).toList();
    }

    private Node scalar(TaxonomyNode node) {
        return node == null ? null : scalars(List.of(node)).getFirst();
    }

    private static Node scalar(TaxonomyNode node, String description) {
        return new Node(node.getCode(), node.getTaxonomyRoot(), node.getNameEn(), description,
                node.getParentCode() == null || node.getParentCode().isBlank());
    }
}
