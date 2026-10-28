package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.AnalysisOperationContext;
import com.taxonomy.analysis.dag.TaxonomyShardRoot;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.analysis.usecase.PartialArchitectureProjection;
import com.taxonomy.architecture.service.AnalysisCopilotService;
import com.taxonomy.architecture.service.ArchitectureReportMetadataPort;
import com.taxonomy.architecture.service.RequirementArchitectureViewService;
import com.taxonomy.catalog.snapshot.CatalogueSnapshotService;
import com.taxonomy.catalog.snapshot.RootCatalogueSnapshot;
import com.taxonomy.dto.*;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Completes the durable rendering stage before the operation becomes terminal. */
public final class FrozenClusterAnalysisFinalizer implements Consumer<AnalysisOperationContext> {
    private static final int MAX_SNAPSHOT_NODES = 100_000;
    private static final int MAX_PATH_DEPTH = 256;
    private final ClusterAnalysisStore store;
    private final CatalogueSnapshotService snapshots;
    private final RequirementArchitectureViewService architecture;
    private final ArchitectureReportMetadataPort metadata;
    private final ObjectMapper mapper;
    private final AnalysisCopilotService copilot;

    public FrozenClusterAnalysisFinalizer(ClusterAnalysisStore store, CatalogueSnapshotService snapshots,
            RequirementArchitectureViewService architecture, ArchitectureReportMetadataPort metadata, ObjectMapper mapper) {
        this(store, snapshots, architecture, metadata, mapper, new AnalysisCopilotService());
    }

    public FrozenClusterAnalysisFinalizer(ClusterAnalysisStore store, CatalogueSnapshotService snapshots,
            RequirementArchitectureViewService architecture, ArchitectureReportMetadataPort metadata, ObjectMapper mapper,
            AnalysisCopilotService copilot) {
        this.store = Objects.requireNonNull(store); this.snapshots = Objects.requireNonNull(snapshots);
        this.architecture = Objects.requireNonNull(architecture); this.metadata = Objects.requireNonNull(metadata);
        this.mapper = Objects.requireNonNull(mapper);
        this.copilot = Objects.requireNonNull(copilot);
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
            if (result.getTree() == null || result.getTree().isEmpty()) {
                result.setTree(snapshotProjectionTree(roots, result));
            }
            RequirementArchitectureView view = result.getRelationSearchReport() != null
                    ? architecture.buildFromEvidence(result.getScores(), result.getScoreDetails(),
                        command.maxArchitectureNodes(), result.getRelationSearchReport())
                    : scoredView(command, result);
            view = PartialArchitectureProjection.complete(result, view, command.maxArchitectureNodes());
            result.setArchitectureView(view); // Retain verified edges even if only presentation metadata fails.
            metadata.applyTo(view);
            view.setAnalysisCoverage(result.getAnalysisCoverage());
            view.setAnalysisStatus(result.getStatus());
            result.setArchitectureView(view);
        } catch (DataAccessException databaseFailure) {
            throw databaseFailure;
        } catch (RuntimeException renderingFailure) {
            com.taxonomy.analysis.service.AnalysisExecutionFailures.rethrow(renderingFailure);
            result.setStatus("PARTIAL");
            String warning = "ARCHITECTURE_VIEW_INCOMPLETE: Frozen architecture rendering could not be completed.";
            var warnings = new ArrayList<>(result.getWarnings());
            warnings.add(warning); result.setWarnings(warnings);
            if (result.getErrorMessage() == null || result.getErrorMessage().isBlank()) result.setErrorMessage(warning);
            result.setArchitectureView(PartialArchitectureProjection.complete(result, result.getArchitectureView(), command.maxArchitectureNodes()));
        }
        if (result.getArchitectureView() != null && command.businessText() != null)
            result.getArchitectureView().setOriginalRequirementText(command.businessText());
        result.setCopilot(copilot.analyze(result, command.businessText()));
        // The store rechecks authority, FINALIZING state and cancellation under its short lock.
        store.finalizeResult(context, result);
    }

    private static List<TaxonomyNodeDto> snapshotProjectionTree(
            List<RootCatalogueSnapshot> roots, AnalysisResult result) {
        var recorded = new java.util.LinkedHashMap<String, RootCatalogueSnapshot.Node>();
        boolean limited = false;
        for (var root : roots) {
            for (var node : root.nodes()) {
                if (recorded.size() >= MAX_SNAPSHOT_NODES) {
                    limited = true;
                    break;
                }
                recorded.put(node.code(), node);
            }
            if (limited) break;
        }

        var selected = new java.util.LinkedHashMap<String, RootCatalogueSnapshot.Node>();
        for (String code : result.getRawScores().keySet()) {
            var node = recorded.get(code);
            int depth = 0;
            while (node != null && depth++ < MAX_PATH_DEPTH) {
                if (selected.size() >= MAX_SNAPSHOT_NODES && !selected.containsKey(node.code())) {
                    limited = true;
                    break;
                }
                selected.putIfAbsent(node.code(), node);
                node = node.parentCode() == null ? null : recorded.get(node.parentCode());
            }
            if (node != null) limited = true;
        }

        var detached = new java.util.LinkedHashMap<String, TaxonomyNodeDto>();
        selected.values().forEach(node -> detached.put(node.code(), toDto(node)));
        var tree = new ArrayList<TaxonomyNodeDto>();
        selected.values().forEach(node -> {
            var dto = detached.get(node.code());
            var parent = node.parentCode() == null ? null : detached.get(node.parentCode());
            if (parent == null) tree.add(dto);
            else parent.getChildren().add(dto);
        });
        if (limited && !result.getWarnings().contains("FROZEN_CATALOGUE_PROJECTION_LIMIT: retained snapshot ancestry exceeded the bounded projection limit.")) {
            result.getWarnings().add("FROZEN_CATALOGUE_PROJECTION_LIMIT: retained snapshot ancestry exceeded the bounded projection limit.");
        }
        return tree;
    }

    private static TaxonomyNodeDto toDto(RootCatalogueSnapshot.Node node) {
        var dto = new TaxonomyNodeDto();
        dto.setId(node.id()); dto.setCode(node.code()); dto.setUuid(node.uuid());
        dto.setNameEn(node.nameEn()); dto.setNameDe(node.nameDe());
        dto.setDescriptionEn(node.descriptionEn()); dto.setDescriptionDe(node.descriptionDe());
        dto.setParentCode(node.parentCode()); dto.setTaxonomyRoot(node.taxonomyRoot()); dto.setLevel(node.level());
        dto.setDataset(node.dataset()); dto.setExternalId(node.externalId()); dto.setSource(node.source());
        dto.setReference(node.reference()); dto.setSortOrder(node.sortOrder()); dto.setState(node.state());
        dto.setAnalysisRole(node.metadata().analysisRole());
        dto.setSecondaryClassificationCodes(node.metadata().secondaryClassificationCodes());
        dto.setClassificationConfidence(node.metadata().confidence());
        dto.setClassificationReviewRequired(node.metadata().reviewRequired());
        dto.setClassificationJustification(node.metadata().justification());
        return dto;
    }

    /** Projects scored frozen nodes and any score-only proposals without consulting global relations. */
    private static RequirementArchitectureView scoredView(AnalyzeRequirementCommand command, AnalysisResult result) {
        var view = PartialArchitectureProjection.complete(result, null, command.maxArchitectureNodes());
        var selected = view.getIncludedElements().stream().map(RequirementElementView::getNodeCode).collect(Collectors.toSet());
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
        return view;
    }
}
