package com.taxonomy.catalog.snapshot;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.catalog.service.EmbeddingModelIdentity;
import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.catalog.service.NodeEmbeddingText;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.ReadinessState;
import com.taxonomy.relations.service.RelationProjectionReadService;
import com.taxonomy.relations.service.RelationProjectionReadService.IdentitySnapshot;
import com.taxonomy.relations.service.RelationProjectionReadService.ReadModel;
import com.taxonomy.relations.service.RelationProjectionReadService.RelationIdentity;
import com.taxonomy.workspace.service.RepositoryContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Admission-only enrichment from proven exact-source relations and an already atomically captured catalogue. */
@Service
public class FrozenCatalogueEmbeddingService {
    private final RelationProjectionReadService relations;
    private final LocalEmbeddingService embeddings;

    public FrozenCatalogueEmbeddingService(RelationProjectionReadService relations, LocalEmbeddingService embeddings) {
        this.relations = Objects.requireNonNull(relations);
        this.embeddings = Objects.requireNonNull(embeddings);
    }

    /**
     * Endpoint names may cross roots, so admission supplies one atomic all-root scalar capture.
     * Only required roots are returned for persistence; workers never load endpoint roots or relation projections.
     * No legacy/global projection or scalar-only text fallback is permitted.
     */
    public List<RootCatalogueSnapshot> decorate(RepositoryContext context, CatalogueSourceIdentity source,
                                                List<RootCatalogueSnapshot> capturedRoots,
                                                Set<String> requiredRoots) {
        return decorate(context, source, capturedRoots, requiredRoots, null);
    }

    /** An index builder can also require the exact detached edge set used to build its relation field. */
    public List<RootCatalogueSnapshot> decorate(RepositoryContext context, CatalogueSourceIdentity source,
                                                List<RootCatalogueSnapshot> capturedRoots, Set<String> requiredRoots,
                                                Set<RelationIdentity> expectedRelations) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Frozen embedding preparation requires no database transaction");
        }
        if (!context.repositoryId().equals(source.repositoryId())
                || !Objects.equals(context.workspaceId(), source.workspaceId())
                || !context.branch().equals(source.branch())) {
            throw new IllegalArgumentException("Embedding context does not match the captured source authority");
        }
        if (capturedRoots == null || capturedRoots.isEmpty()) throw new IllegalArgumentException("Captured roots are empty");
        var spec = capturedRoots.getFirst().catalogueDefinition();
        var allRoots = spec == null ? com.taxonomy.dsl.model.CatalogueDefinition.standard().rootCodes()
                : spec.definition().rootCodes();
        if (requiredRoots == null || requiredRoots.isEmpty() || !allRoots.containsAll(requiredRoots)) {
            throw new IllegalArgumentException("Required embedding roots are invalid");
        }
        // Validates source, root membership, hierarchy and one retained catalogue generation.
        new FrozenCatalogueView(source, allRoots, capturedRoots);
        IdentitySnapshot before = requireExact(relations.readIdentitySnapshot(context), source);
        if (expectedRelations != null && !expectedRelations.equals(before.identities())) {
            throw new IllegalStateException("Relation projection differs from the selected vector generation");
        }
        Map<String, TaxonomyNode> nodes = new LinkedHashMap<>();
        for (var root : capturedRoots) {
            for (var node : root.nodes()) nodes.put(node.code(), node.detached());
        }
        before.identities().stream().sorted(Comparator.comparing(RelationIdentity::sourceCode)
                .thenComparing(identity -> identity.relationType().name()).thenComparing(RelationIdentity::targetCode))
                .forEach(identity -> enrich(nodes, identity));
        EmbeddingModelIdentity model;
        try {
            model = embeddings.embeddingIdentity();
        } catch (Exception failure) {
            throw new IllegalStateException("Frozen embedding model identity is unavailable", failure);
        }
        List<RootCatalogueSnapshot> result = capturedRoots.stream()
                .filter(root -> requiredRoots.contains(root.rootCode())).map(root -> {
                    Map<String, String> texts = new LinkedHashMap<>();
                    root.nodes().forEach(node -> texts.put(node.code(),
                            NodeEmbeddingText.buildEnrichedText(nodes.get(node.code()))));
                    return root.withEmbeddings(new RootEmbeddingSnapshot(RootEmbeddingSnapshot.SCHEMA_VERSION,
                            source, root.rootCode(), before.commitId(), RootEmbeddingSnapshot.TEXT_VERSION,
                            model, texts));
                }).toList();
        if (!before.equals(requireExact(relations.readIdentitySnapshot(context), source))) {
            throw new IllegalStateException("Relation projection changed during frozen embedding preparation");
        }
        return result;
    }

    private static IdentitySnapshot requireExact(IdentitySnapshot snapshot, CatalogueSourceIdentity source) {
        if (snapshot == null || snapshot.readModel() != ReadModel.PROJECTION
                || snapshot.readinessState() != ReadinessState.READY || source.sourceCommit() == null
                || !source.sourceCommit().equals(snapshot.commitId())) {
            throw new IllegalStateException("Frozen embeddings require a complete relation projection at the exact source commit");
        }
        return snapshot;
    }

    private static void enrich(Map<String, TaxonomyNode> nodes, RelationIdentity identity) {
        TaxonomyNode source = nodes.get(identity.sourceCode());
        TaxonomyNode target = nodes.get(identity.targetCode());
        if (source == null || target == null) {
            throw new IllegalStateException("Relation endpoint is absent from the captured catalogue generation");
        }
        var relation = new TaxonomyRelation();
        relation.setSourceNode(source);
        relation.setTargetNode(target);
        relation.setRelationType(identity.relationType());
        source.getOutgoingRelations().add(relation);
        target.getIncomingRelations().add(relation);
    }
}
