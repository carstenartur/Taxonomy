package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.relations.*;
import com.taxonomy.analysis.service.*;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.catalog.snapshot.*;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.taxonomy.dto.RelationSearchModel.Node;

/** Production relation computation against persisted exact-source snapshots, never the current catalogue. */
public final class FrozenClusterRelationComputation implements ClusterRelationComputation {
    private final ClusterRelationService.Store store;
    private final CatalogueSnapshotService snapshots;
    private final TaxonomyService taxonomy;
    private final RequirementRelationSearchService relations;
    private final LlmProviderConfig providers;
    private final ObjectMapper mapper;
    private final Supplier<AnalysisMemoryGuard> guards;

    public FrozenClusterRelationComputation(ClusterRelationService.Store store, CatalogueSnapshotService snapshots,
            TaxonomyService taxonomy, RequirementRelationSearchService relations, LlmProviderConfig providers,
            ObjectMapper mapper, Supplier<AnalysisMemoryGuard> guards) {
        this.store = Objects.requireNonNull(store); this.snapshots = Objects.requireNonNull(snapshots);
        this.taxonomy = Objects.requireNonNull(taxonomy); this.relations = Objects.requireNonNull(relations);
        this.providers = Objects.requireNonNull(providers); this.mapper = Objects.requireNonNull(mapper);
        this.guards = Objects.requireNonNull(guards);
    }

    @Override public Result compute(ClusterAnalysisStore.Input input, RelationAnalysisTask task, BooleanSupplier cancelled) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Relation provider computation cannot run inside a database transaction");
        Objects.requireNonNull(input); Objects.requireNonNull(task); Objects.requireNonNull(cancelled);
        var context = input.context();
        if (!input.executable() || !context.equals(ClusterAnalysisStore.context(task.envelope()))
                || !context.requirement().matches(input.command().businessText()))
            throw new IllegalArgumentException("Relation input does not match its immutable task authority");
        var source = context.authority();
        var identity = new CatalogueSourceIdentity(source.repositoryId(), source.workspaceId(), source.branch(), source.sourceCommit());
        var ordinal = task.taskId().relationWorkOrdinal();
        var work = ordinal.isPresent() ? mapper.readValue(Objects.requireNonNull(input.taskInput()), RelationSearchDistribution.Work.class) : null;
        var plan = work == null ? null : Objects.requireNonNull(store.relationPlan(context), "persisted relation preparation");
        if (work != null && (work.ordinal() != ordinal.getAsInt()
                || !task.targetRoots().equals(List.of(work.targetRoot()))
                || !work.preparationId().equals(context.operationId() + ":relations")
                || !work.preparationId().equals(plan.preparationId())))
            throw new IllegalArgumentException("Relation work does not match its persisted grant identity");
        Set<String> required = task.targetRoots().stream().map(TaxonomyShardRoot::code).collect(Collectors.toUnmodifiableSet());
        try (var provider = providers.withRequestProvider(input.command().provider());
             var control = AnalysisRunControl.worker(context.operationId(), cancelled, Objects.requireNonNull(guards.get()))) {
            AnalysisRunControl.checkpoint();
            List<RootCatalogueSnapshot> roots = new ArrayList<>();
            for (TaxonomyShardRoot root : task.targetRoots()) {
                AnalysisRunControl.checkpoint();
                roots.add(mapper.readValue(store.shard(context, root), RootCatalogueSnapshot.class));
            }
            try (var frozen = snapshots.bind(identity, required, roots)) {
                var catalogue = catalogue(work == null);
                if (work != null) {
                    if (!relations.isEnabled()) throw new IllegalStateException("Hierarchical relation search is disabled");
                    return new Evaluation(relations.evaluate(input.command().businessText(), plan, work, catalogue));
                }
                var sources = Objects.requireNonNull(store.rootResults(context), "persisted source results");
                if (!relations.isEnabled()) {
                    // Use the local score-only algorithm with a lookup that can only resolve admitted bytes.
                    var generator = new AnalysisRelationGenerator(new RelationCompatibilityMatrix(), code -> {
                        AnalysisRunControl.checkpoint(); requireFrozen();
                        return Optional.ofNullable(taxonomy.getNodeByCode(code));
                    });
                    var hypotheses = generator.generate(sources.getScores());
                    AnalysisRunControl.checkpoint();
                    return new ScoreOnly(hypotheses);
                }
                var prepared = relations.prepare(context.operationId() + ":relations", input.command().businessText(),
                        sources.getScores(), task.prerequisiteTasks().stream().map(AnalysisTaskId::value).toList(), catalogue, true);
                if (!"SUCCESS".equals(sources.getStatus()) || !sources.getWarnings().isEmpty()) {
                    var warnings = new ArrayList<>(prepared.warnings());
                    warnings.add("SOURCE_RESULTS_PARTIAL: one or more source shards remain unassessed; no negative finding.");
                    prepared = new RelationSearchDistribution.Plan(prepared.schemaVersion(), prepared.preparationId(),
                            prepared.originalSha256(), prepared.sourceResultIds(), prepared.options(), prepared.sourceNodes(),
                            prepared.targetRoots(), prepared.sources(), prepared.items(), prepared.sourceCalls(),
                            prepared.durationMillis(), warnings, prepared.stopReason(), prepared.included());
                }
                return new Preparation(prepared);
            }
        }
    }

    private RequirementRelationSearch.InputCatalogue catalogue(boolean preparation) {
        return new RequirementRelationSearch.InputCatalogue() {
            private final Map<String, List<Node>> children = new HashMap<>();
            public Node find(String id) {
                if (!preparation) throw new IllegalStateException("Target workers cannot reload source catalogue nodes");
                requireFrozen(); return scalar(taxonomy.getNodeByCode(id));
            }
            public List<Node> roots() { requireFrozen(); return scalars(taxonomy.getRootNodes()); }
            public List<Node> children(Node node) {
                requireFrozen(); return children.computeIfAbsent(node.id(), id -> scalars(taxonomy.getChildrenOf(id)));
            }
        };
    }
    private static void requireFrozen() {
        if (FrozenCatalogueContext.current() == null) throw new IllegalStateException("Relation catalogue requires a frozen source binding");
    }
    private Node scalar(TaxonomyNode node) { return node == null ? null : scalars(List.of(node)).getFirst(); }
    private List<Node> scalars(List<TaxonomyNode> nodes) {
        var descriptions = taxonomy.getAssessmentDescriptions(nodes);
        return nodes.stream().map(node -> new Node(node.getCode(), node.getTaxonomyRoot(), node.getNameEn(),
                descriptions.getOrDefault(node.getCode(), node.getDescriptionEn()), node.getParentCode() == null
                || node.getParentCode().isBlank())).toList();
    }
}
