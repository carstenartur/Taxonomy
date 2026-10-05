package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.AnalysisOperationContext;
import com.taxonomy.analysis.dag.TaxonomyShardRoot;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.architecture.service.ArchitectureReportMetadataPort;
import com.taxonomy.architecture.service.RequirementArchitectureViewService;
import com.taxonomy.catalog.snapshot.CatalogueSnapshotService;
import com.taxonomy.catalog.snapshot.FrozenCatalogueContext;
import com.taxonomy.catalog.snapshot.RootCatalogueSnapshot;
import com.taxonomy.dto.*;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Completes the durable rendering stage before the operation becomes terminal. */
public final class FrozenClusterAnalysisFinalizer implements Consumer<AnalysisOperationContext> {
    private final ClusterAnalysisStore store;
    private final CatalogueSnapshotService snapshots;
    private final RequirementArchitectureViewService architecture;
    private final ArchitectureReportMetadataPort metadata;
    private final ObjectMapper mapper;

    public FrozenClusterAnalysisFinalizer(ClusterAnalysisStore store, CatalogueSnapshotService snapshots,
            RequirementArchitectureViewService architecture, ArchitectureReportMetadataPort metadata, ObjectMapper mapper) {
        this.store = Objects.requireNonNull(store); this.snapshots = Objects.requireNonNull(snapshots);
        this.architecture = Objects.requireNonNull(architecture); this.metadata = Objects.requireNonNull(metadata);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override public void accept(AnalysisOperationContext context) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Architecture finalization must execute outside a database transaction");
        }
        var state = store.snapshot(context);
        if (state.state() != ClusterAnalysisState.FINALIZING) return;
        var command = store.command(context);
        var result = mapper.readValue(mapper.writeValueAsString(
                Objects.requireNonNull(state.result(), "Finalizing operation has no result")), AnalysisResult.class);
        // Read the admitted bytes outside the rendering-failure boundary. A database outage
        // must redeliver this durable stage instead of becoming a successful acknowledgement.
        List<RootCatalogueSnapshot> roots = TaxonomyShardRoot.DEFAULT_ROOTS.stream()
                .map(root -> mapper.readValue(store.shard(context, root), RootCatalogueSnapshot.class)).toList();
        var required = TaxonomyShardRoot.DEFAULT_ROOTS.stream().map(TaxonomyShardRoot::code).collect(Collectors.toSet());
        try (var catalogue = snapshots.bind(DurableClusterAnalysisExecution.source(context), required, roots)) {
            RequirementArchitectureView view = result.getRelationSearchReport() != null
                    ? architecture.buildFromEvidence(result.getScores(), result.getScoreDetails(),
                        command.maxArchitectureNodes(), result.getRelationSearchReport())
                    : scoredView(command, result);
            metadata.applyTo(view);
            view.setAnalysisCoverage(result.getAnalysisCoverage());
            view.setAnalysisStatus(result.getStatus());
            result.setArchitectureView(view);
        } catch (DataAccessException databaseFailure) {
            throw databaseFailure;
        } catch (RuntimeException renderingFailure) {
            result.setArchitectureView(null);
            result.setStatus("PARTIAL");
            String warning = "ARCHITECTURE_VIEW_INCOMPLETE: Frozen architecture rendering could not be completed.";
            var warnings = new ArrayList<>(result.getWarnings());
            warnings.add(warning); result.setWarnings(warnings);
            if (result.getErrorMessage() == null || result.getErrorMessage().isBlank()) result.setErrorMessage(warning);
        }
        // The store rechecks authority, FINALIZING state and cancellation under its short lock.
        store.finalizeResult(context, result);
    }

    /** Projects scored frozen nodes and any score-only proposals without consulting global relations. */
    private static RequirementArchitectureView scoredView(AnalyzeRequirementCommand command, AnalysisResult result) {
        var catalogue = Objects.requireNonNull(FrozenCatalogueContext.current());
        var view = new RequirementArchitectureView();
        var ranked = result.getScores().entrySet().stream().filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey())).toList();
        int limit = command.maxArchitectureNodes() > 0 ? command.maxArchitectureNodes() : ranked.size();
        var selected = ranked.stream().limit(limit).map(Map.Entry::getKey).collect(Collectors.toSet());
        for (var score : ranked.stream().limit(limit).toList()) {
            var node = catalogue.requireNode(score.getKey());
            var detail = result.getScoreDetails().get(node.code());
            var element = new RequirementElementView();
            element.setNodeCode(node.code()); element.setTitle(node.nameEn()); element.setTaxonomySheet(node.taxonomyRoot());
            element.setScoreDetail(detail); element.setDirectLlmScore(detail == null ? 0 : detail.rawScore());
            element.setRelevance(score.getValue() / 100.0); element.setOrigin(NodeOrigin.DIRECT_SCORED);
            element.setAnchor(true); element.setSelectedForImpact(true);
            element.setPresenceReason("Direct assessment in the admitted catalogue.");
            element.setIncludedBecause(element.getPresenceReason());
            var path = new LinkedList<String>();
            RootCatalogueSnapshot.Node parent = node;
            while (parent != null) {
                path.addFirst(parent.code());
                parent = parent.parentCode() == null ? null : catalogue.requireNode(parent.parentCode());
            }
            element.setHierarchyPath(String.join(" > ", path)); element.setTaxonomyDepth(path.size() - 1);
            for (int index = path.size() - 2; index >= 0; index--) {
                if (selected.contains(path.get(index))) { element.setParentNodeCode(path.get(index)); break; }
            }
            view.getIncludedElements().add(element);
            view.getAnchors().add(new RequirementAnchor(node.code(), element.getDirectLlmScore(), element.getPresenceReason()));
        }
        view.setTotalElements(view.getIncludedElements().size()); view.setTotalAnchors(view.getAnchors().size());
        if (result.getProvisionalRelations().isEmpty()) {
            view.getNotes().add("Frozen hierarchy-only projection of scored taxonomy nodes; no verified relationship evidence is available in this result.");
        } else {
            for (var hypothesis : result.getProvisionalRelations()) {
                if (!selected.contains(hypothesis.getSourceCode()) || !selected.contains(hypothesis.getTargetCode())) continue;
                var relation = new RequirementRelationshipView();
                relation.setSourceCode(hypothesis.getSourceCode()); relation.setTargetCode(hypothesis.getTargetCode());
                relation.setRelationType(hypothesis.getRelationType()); relation.setOrigin(RelationOrigin.SUGGESTED_CANDIDATE);
                relation.setConfidence(hypothesis.getConfidence()); relation.setPropagatedRelevance(hypothesis.getConfidence());
                relation.setDerivationReason(hypothesis.getReasoning());
                relation.setPresenceReason("Provisional score-only hypothesis; not verified or adopted.");
                relation.setIncludedBecause(relation.getPresenceReason());
                view.getIncludedRelationships().add(relation);
            }
            view.setTotalRelationships(view.getIncludedRelationships().size());
            view.getNotes().add("Provisional score-only hypotheses use effective scores and compatibility rules; no relationship verification was performed.");
        }
        if (ranked.size() > limit) view.getNotes().add("NODE_LIMIT: Complete score and provisional evidence remains in the analysis result.");
        return view;
    }
}
