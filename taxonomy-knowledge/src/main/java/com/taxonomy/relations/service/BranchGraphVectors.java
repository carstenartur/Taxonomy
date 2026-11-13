package com.taxonomy.relations.service;

import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.catalog.service.RelationEmbeddingText;
import com.taxonomy.catalog.snapshot.CatalogueSnapshotService;
import com.taxonomy.catalog.snapshot.CatalogueSourceIdentity;
import com.taxonomy.catalog.snapshot.FrozenCatalogueEmbeddingService;
import com.taxonomy.catalog.snapshot.RootCatalogueSnapshot;
import com.taxonomy.dto.GraphSearchResult;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.error.SearchUnavailableException;
import com.taxonomy.workspace.service.RepositoryContext;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Objects;
import java.util.stream.Collectors;

/** Operator builds are explicit. Searches never manufacture a generation or fall back to another scope. */
@Service
public class BranchGraphVectors {
    public record Status(String state, String generation, String sourceCommit, String modelKey, int nodes, int relations) { }
    private final BranchGraphVectorStore store;
    private final BranchGraphVectorAuthority authority;
    private final CatalogueSnapshotService snapshots;
    private final FrozenCatalogueEmbeddingService texts;
    private final LocalEmbeddingService embeddings;
    private final ObjectMapper json;

    BranchGraphVectors(BranchGraphVectorStore store, BranchGraphVectorAuthority authority,
                       CatalogueSnapshotService snapshots, FrozenCatalogueEmbeddingService texts,
                       LocalEmbeddingService embeddings, ObjectMapper json) {
        this.store = store;
        this.authority = authority;
        this.snapshots = snapshots;
        this.texts = texts;
        this.embeddings = embeddings;
        this.json = json;
    }

    public Status inspect(RepositoryContext context) {
        BranchGraphVectorSource current = null;
        try { current = authority.capture(context).source(); }
        catch (IllegalStateException unavailable) { /* A missing authority makes any retained generation stale. */ }
        var s = store.status(context, current);
        var state = s.state();
        if (state == BranchGraphVectorStore.State.READY) {
            try {
                var retained = store.read(context, current);
                BranchGraphVectorIndex.verifyArchive(retained.index());
                var confirmed = store.status(context, authority.capture(context).source());
                if (!s.generation().equals(retained.id()) || !retained.id().equals(confirmed.generation())
                        || confirmed.state() != state) state = BranchGraphVectorStore.State.STALE;
            } catch (Exception unavailable) {
                state = BranchGraphVectorStore.State.FAILED;
            }
        }
        return new Status(state.name(), s.generation(), s.sourceCommit(), s.modelKey(), s.nodeCount(), s.relationCount());
    }

    public Status rebuild(RepositoryContext context, String expectedCommit) {
        authority.requireHead(context, expectedCommit);
        var capture = authority.capture(context);
        var source = capture.source();
        if (!source.commit().equals(expectedCommit)) throw new RelationProjectionOperationsService.RebuildHeadConflictException(expectedCommit, source.commit());
        String token = store.begin(context, source, capture.modelIdentity());
        try {
            var selection = new CatalogueSourceIdentity(context.repositoryId(), context.workspaceId(), context.branch(), expectedCommit);
            var rootCodes = capture.definition().rootCodes();
            var roots = texts.decorate(context, selection, snapshots.captureRoots(selection, rootCodes), rootCodes, capture.relations());
            var nodes = new HashMap<String, RootCatalogueSnapshot.Node>();
            try (var builder = new BranchGraphVectorIndex.Builder(source.key())) {
                for (var root : roots) {
                    var definition = root.catalogueDefinition() == null ? com.taxonomy.dsl.model.CatalogueDefinition.standard().spec()
                            : root.catalogueDefinition();
                    if (!capture.definition().spec().equals(definition) || !source.catalogueRevision().equals(root.catalogueProvenance().id())
                            || !source.modelKey().equals(root.embeddings().model().indexKey())) throw new SearchUnavailableException();
                    for (var node : root.nodes()) {
                        if (nodes.putIfAbsent(node.code(), node) != null) throw new SearchUnavailableException();
                        String text = root.embeddings().nodeTexts().get(node.code());
                        builder.node(json.writeValueAsString(GraphSearchService.toFlatDto(node.detached())), text, embeddings.embedDocument(text));
                    }
                }
                for (var relation : capture.relations().stream().sorted(BranchGraphVectorSource.ORDER).toList()) {
                    var from = Objects.requireNonNull(nodes.get(relation.sourceCode()));
                    var to = Objects.requireNonNull(nodes.get(relation.targetCode()));
                    String text = RelationEmbeddingText.build(from.nameEn(), relation.relationType(), to.nameEn(), null, java.util.Locale.ROOT);
                    builder.relation(from.code(), to.code(), from.taxonomyRoot(), relation.relationType().name(), text, embeddings.embedDocument(text));
                }
                var archive = builder.finish();
                store.publish(context, source, token, archive, () -> authority.verifyPublication(context, source));
            }
            var result = inspect(context);
            if (!"READY".equals(result.state()) || !token.equals(result.generation())) throw new SearchUnavailableException();
            return result;
        } catch (Exception failure) {
            store.fail(context, token);
            throw new SearchUnavailableException();
        }
    }

    public void purge(RepositoryContext context, String expectedCommit) {
        store.purge(context, () -> authority.requireHead(context, expectedCommit));
    }

    GraphSearchResult search(RepositoryContext context, String expectedCommit, String query, int nodeLimit, int relationLimit) throws Exception {
        var source = authority.capture(context).source();
        if (!source.commit().equals(expectedCommit)) throw new SearchUnavailableException();
        var generation = store.read(context, source);
        var hits = BranchGraphVectorIndex.search(generation.index(), embeddings.embedQuery(query), nodeLimit, relationLimit);
        var nodes = hits.nodes().stream().map(text -> json.readValue(text, TaxonomyNodeDto.class)).toList();
        var roots = hits.relations().stream().collect(Collectors.groupingBy(BranchGraphVectorIndex.RelationHit::root, Collectors.counting()));
        var types = hits.relations().stream().collect(Collectors.groupingBy(BranchGraphVectorIndex.RelationHit::type, Collectors.counting()));
        var current = authority.capture(context).source();
        var published = store.status(context, current);
        if (!source.equals(current) || published.state() != BranchGraphVectorStore.State.READY
                || !generation.id().equals(published.generation())) throw new SearchUnavailableException();
        var provenance = new GraphSearchResult.Source(source.repositoryId(), source.workspaceId(), source.branch(),
                source.commit(), RelationProjectionReadService.ReadModel.PROJECTION.name(),
                GraphSearchResult.VectorSource.BRANCH_GENERATION, source.modelKey(), generation.id(), source.key());
        return new GraphSearchResult(nodes, roots, types, GraphSearchService.buildSummary(roots, types, query), provenance);
    }
}
