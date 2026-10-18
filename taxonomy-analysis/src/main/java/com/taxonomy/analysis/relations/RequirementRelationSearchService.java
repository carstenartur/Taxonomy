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
    @Value("${taxonomy.analysis.relations.hierarchical.batch-size:${TAXONOMY_RELATION_BATCH_SIZE:#{null}}}")
    private Integer batchSize;
    @Value("${taxonomy.analysis.relations.hierarchical.max-prompt-characters:${TAXONOMY_RELATION_MAX_PROMPT_CHARACTERS:#{null}}}")
    private Integer maxPromptCharacters;
    @Value("${custom.llm.max-output-tokens:4096}")
    private int maxOutputTokens = 4096;
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
    void validate() { options(false); }
    private RequirementRelationSearch.Options options(boolean custom) {
        int batch = batchSize == null ? custom ? 3 : 10 : batchSize;
        int prompt = maxPromptCharacters == null ? custom ? 16_000 : 120_000 : maxPromptCharacters;
        return new RequirementRelationSearch.Options(new Limits(maxCalls, maxDepth, batch, maxWorkItems), maxSources, prompt);
    }

    private RequirementRelationSearch.Options runtimeOptions() {
        String provider = budgetProvider();
        var configured = options("CUSTOM_OPENAI".equals(provider));
        var target = promptBudget.requireWithinBudget("", provider);
        int cap = target == null ? configured.maxPromptCharacters()
                : Math.min(configured.maxPromptCharacters(), Math.min(target.promptBudget().maxInputCharacters(), target.promptBudget().maxInputBytes()));
        if (cap < 1) throw new IllegalArgumentException("Configured model context leaves no input budget after reserving the answer");
        return new RequirementRelationSearch.Options(configured.limits(), configured.maxSources(), cap);
    }

    public RelationSearchReport search(String original, Map<String,Double> scores) {
        if (!llm.supportsGenerativeCompletion()) {
            return RequirementRelationSearch.unassessed(original, maxCalls,
                    "GENERATION_UNSUPPORTED: " + llm.getActiveProviderId().value()
                            + " provides embeddings, not requirement-scoped relation assessments; "
                            + "all relationships remain unassessed.");
        }
        var adapter = new RequirementRelationSearch.InputCatalogue() {
            private final Map<String, List<Node>> children = new HashMap<>();
            private final Map<String, Node> sources = new HashMap<>();
            private List<Node> roots;
            public Node find(String id) {
                // Include absent IDs: a later cohort cannot change this request's input.
                if (!sources.containsKey(id)) sources.put(id, scalar(catalogue.getNodeByCode(id)));
                return sources.get(id);
            }
            public List<Node> roots() {
                if (roots == null) roots = scalars(catalogue.getRootNodes());
                return roots;
            }
            public List<Node> children(Node node) {
                // Retain only successfully materialized immutable scalars, including
                // empty lists. This adapter and its cache belong to one search.
                return children.computeIfAbsent(node.id(), id -> scalars(catalogue.getChildrenOf(id)));
            }
        };
        return new RequirementRelationSearch(adapter, rules, this::complete, AnalysisRunControl::checkpoint, llm.getActiveProviderName())
                .searchWithSourceContinuation(original, scores, runtimeOptions());
    }

    /** Source preparation must receive the admission snapshot; there is no live-catalogue fallback. */
    public RelationSearchDistribution.Plan prepare(String preparationId, String original, Map<String, Double> scores,
            List<String> sourceResultIds, RequirementRelationSearch.InputCatalogue frozenCatalogue,
            boolean includeRelations) {
        // Version-two transport identities admit ordinals 0..511. Retain the
        // complete intent plan, but bound distributed admission independently of
        // a larger local continuation setting; excess work remains WORK_LIMIT.
        var configured = runtimeOptions();
        var limits = configured.limits();
        var distributed = new RequirementRelationSearch.Options(new Limits(limits.maxCalls(), limits.maxDepth(),
                limits.batchSize(), Math.min(512, limits.maxWorkItems())), configured.maxSources(), configured.maxPromptCharacters());
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
        // Request/provider scope owns model identity separately. Keep the policy identity
        // stable when a background worker restores that scope after admission.
        return "relation-downwalk-v8/source-call-reservation=half/relation-semantics-v2/relation-source-context-v1/provider-profiles/taxonomy-cohorts/custom-defaults=3,16000/" + enabled
                + "/" + options(false) + "/batch-override=" + batchSize + "/prompt-override=" + maxPromptCharacters;
    }

    public String scoringPolicyFingerprint(String provider) { return llm.recoveryPolicyFingerprint(provider); }

    private String complete(String prompt) {
        String provider = llm.getActiveProviderName();
        // Presentation labels (notably Custom OpenAI-compatible and Local ONNX)
        // are not stable provider keys. Preserve them in logs, not in budget lookup.
        String budgetProvider = budgetProvider();
        promptBudget.requireWithinBudget(prompt, budgetProvider);
        return AnalysisRunControl.call(provider, "RELATION_SEARCH", () -> {
            LlmCallDetail detail = new LlmCallDetail();
            detail.setPrompt(prompt); detail.setProvider(provider);
            int outputLimit = "CUSTOM_OPENAI".equals(budgetProvider) ? maxOutputTokens : 4096;
            String raw = llm.callLlmRaw(prompt, RelationSearchProtocol.completionOptions(prompt, outputLimit));
            detail.setRawResponse(raw);
            if (raw == null || raw.isBlank()) detail.setError("Missing relation-search response");
            return detail;
        }).getRawResponse();
    }

    private String budgetProvider() {
        return llm.isMockMode() ? "MOCK" : llm.getActiveProviderId().value();
    }

    private List<Node> scalars(List<TaxonomyNode> nodes) {
        var descriptions = catalogue.getRelationAssessmentDescriptions(nodes);
        return nodes.stream().map(node -> scalar(node,
                Objects.requireNonNull(descriptions.get(node.getCode()), "Captured relation catalogue context"))).toList();
    }

    private Node scalar(TaxonomyNode node) {
        return node == null ? null : scalars(List.of(node)).getFirst();
    }

    private static Node scalar(TaxonomyNode node, TaxonomyService.RelationAssessmentDescriptions descriptions) {
        return new Node(node.getCode(), node.getTaxonomyRoot(), node.getNameEn(), descriptions.full(),
                node.getParentCode() == null || node.getParentCode().isBlank(), descriptions.assessedSource());
    }
}
