package com.taxonomy.analysis.relations;

import com.taxonomy.analysis.service.*;
import com.taxonomy.analysis.recovery.AnalysisCheckpointSession;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.dto.RelationSearchReport;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired(required = false)
    private RelationSearchBudgetSettings budgetSettings;

    @Value("${taxonomy.analysis.relations.hierarchical.enabled:true}")
    private boolean enabled = true;
    @Value("${taxonomy.analysis.relations.hierarchical.max-calls:256}")
    private int maxCalls = 256;
    @Value("${taxonomy.analysis.relations.hierarchical.max-total-calls:4096}")
    private int maxTotalCalls = 4096;
    @Value("${taxonomy.analysis.relations.hierarchical.auto-continue:true}")
    private boolean autoContinue = true;
    @Value("${taxonomy.analysis.relations.hierarchical.max-depth:8}")
    private int maxDepth = 8;
    @Value("${taxonomy.analysis.relations.hierarchical.batch-size:${TAXONOMY_RELATION_BATCH_SIZE:#{null}}}")
    private Integer batchSize;
    @Value("${taxonomy.analysis.relations.hierarchical.max-prompt-characters:${TAXONOMY_RELATION_MAX_PROMPT_CHARACTERS:#{null}}}")
    private Integer maxPromptCharacters;
    @Value("${custom.llm.max-output-tokens:4096}")
    private int maxOutputTokens = 4096;
    @Value("${taxonomy.analysis.relations.hierarchical.max-work-items:4096}")
    private int maxWorkItems = 4096;
    @Value("${taxonomy.analysis.relations.hierarchical.max-sources:128}")
    private int maxSources = 128;

    public RequirementRelationSearchService(TaxonomyService catalogue, RelationCompatibilityMatrix rules,
                                            LlmService llm, AiPromptBudgetPolicy promptBudget) {
        this.catalogue = catalogue; this.rules = rules; this.llm = llm; this.promptBudget = promptBudget;
    }
    public boolean isEnabled() { return enabled; }

    /** Capture once at admission; a later preference commit only affects the next execution. */
    public RelationSearchBudgetSettings.Snapshot executionBudget() {
        return budgetSettings == null
                ? new RelationSearchBudgetSettings.Snapshot(maxCalls, maxTotalCalls, autoContinue, maxSources, maxWorkItems)
                : budgetSettings.snapshot();
    }

    @PostConstruct
    void validate() { options(false, executionBudget()); }
    private RequirementRelationSearch.Options options(boolean custom, RelationSearchBudgetSettings.Snapshot budget) {
        int batch = batchSize == null ? custom ? 3 : 10 : batchSize;
        int prompt = maxPromptCharacters == null ? custom ? 16_000 : 120_000 : maxPromptCharacters;
        return new RequirementRelationSearch.Options(new Limits(budget.maxCalls(), maxDepth, batch, budget.maxWorkItems()),
                budget.maxSources(), prompt);
    }

    private RequirementRelationSearch.Options runtimeOptions() {
        return runtimeOptions(executionBudget());
    }

    private RequirementRelationSearch.Options runtimeOptions(RelationSearchBudgetSettings.Snapshot budget) {
        String provider = budgetProvider();
        var configured = options("CUSTOM_OPENAI".equals(provider), budget);
        var target = promptBudget.requireWithinBudget("", provider);
        int cap = target == null ? configured.maxPromptCharacters()
                : Math.min(configured.maxPromptCharacters(), Math.min(target.promptBudget().maxInputCharacters(), target.promptBudget().maxInputBytes()));
        if (cap < 1) throw new IllegalArgumentException("Configured model context leaves no input budget after reserving the answer");
        return new RequirementRelationSearch.Options(configured.limits(), configured.maxSources(), cap);
    }

    private RequirementRelationSearch.Options executionOptions(RelationSearchBudgetSettings.Snapshot configuredBudget) {
        var policy = AnalysisCheckpointSession.relationBudget();
        if (policy == null) {
            int spent = AnalysisCheckpointSession.relationCallsSpent();
            var previous = AnalysisCheckpointSession.previousRelations();
            int cohort = previous != null && previous.budget() != null ? previous.budget().sourceCohortSize()
                    : AnalysisCheckpointSession.sourceCohortSize();
            if (cohort == 0) cohort = configuredBudget.maxSources();
            policy = new RequirementRelationSearch.BudgetPolicy(configuredBudget.maxCalls(), configuredBudget.maxTotalCalls(),
                    spent, configuredBudget.autoContinue(), configuredBudget.maxSources(), configuredBudget.maxWorkItems(), cohort);
        }
        var frozen = new RelationSearchBudgetSettings.Snapshot(policy.callsPerSlice(), policy.maxTotalCalls(),
                policy.automatic(), policy.sourcesPerSlice(), policy.workItemsPerSlice());
        var configured = runtimeOptions(frozen);
        int remaining = Math.max(0, policy.maxTotalCalls() - policy.callsBeforeExecution());
        int calls = policy.callsPerSlice() == 0 ? 0 : policy.automatic() ? remaining : Math.min(policy.callsPerSlice(), remaining);
        // Automatic sections remain inside one operation, so their shared envelope
        // must allow all funded work while the original per-section limits stay visible.
        int work = policy.automatic() ? Math.max(policy.workItemsPerSlice(), calls) : policy.workItemsPerSlice();
        var limits = configured.limits();
        return new RequirementRelationSearch.Options(new Limits(calls, limits.maxDepth(), limits.batchSize(), work),
                policy.sourcesPerSlice(), configured.maxPromptCharacters(), policy);
    }

    public RelationSearchReport search(String original, Map<String,Double> scores) {
        var budget = executionBudget();
        if (!llm.supportsGenerativeCompletion()) {
            return RequirementRelationSearch.unassessed(original, budget.maxCalls(),
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
        var options = executionOptions(budget);
        var engine = new RequirementRelationSearch(adapter, rules, this::complete, AnalysisRunControl::checkpoint, llm.getActiveProviderName());
        return options.budget().automatic() ? engine.searchWithSourceContinuation(original, scores, options)
                : engine.search(original, scores, options);
    }

    /** Source preparation must receive the admission snapshot; there is no live-catalogue fallback. */
    public RelationSearchDistribution.Plan prepare(String preparationId, String original, Map<String, Double> scores,
            List<String> sourceResultIds, RequirementRelationSearch.InputCatalogue frozenCatalogue,
            boolean includeRelations) {
        var distributed = executionOptions(executionBudget());
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
        // Execution resources can be raised without discarding validated answers.
        // Depth and packing still shape questions and retain semantic authority.
        String fingerprint = "relation-downwalk-v9/source-call-reservation=half/relation-semantics-v2/relation-source-context-v1/provider-profiles/taxonomy-cohorts/custom-defaults=3,16000/" + enabled
                + "/max-depth=" + maxDepth + "/standard-batch=" + (batchSize == null ? 10 : batchSize)
                + "/standard-prompt=" + (maxPromptCharacters == null ? 120_000 : maxPromptCharacters)
                + "/batch-override=" + batchSize + "/prompt-override=" + maxPromptCharacters
                + "/" + PromptTemplateService.FUNCTIONAL_SCOPE_POLICY;
        var language = com.taxonomy.analysis.service.AnalysisLanguageContext.current();
        return language == null ? fingerprint : fingerprint + "/"
                + com.taxonomy.analysis.service.AnalysisLanguageContext.POLICY + "/" + language.value();
    }

    public String scoringPolicyFingerprint(String provider) { return llm.recoveryPolicyFingerprint(provider); }

    /** Retain an exact saved identity only when its non-resource policy still matches. */
    public RelationRecoveryPolicy.Compatible compatibleRecoveryPolicy(String savedHash) {
        return RelationRecoveryPolicy.compatible(savedHash, recoveryPolicyFingerprint(), enabled, maxDepth,
                batchSize, maxPromptCharacters, maxCalls, maxWorkItems, maxSources);
    }

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
