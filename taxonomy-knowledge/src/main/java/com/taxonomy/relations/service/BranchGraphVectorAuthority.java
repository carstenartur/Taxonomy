package com.taxonomy.relations.service;

import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.catalog.snapshot.CatalogueSnapshotService;
import com.taxonomy.dsl.model.CatalogueDefinition;
import com.taxonomy.error.SearchUnavailableException;
import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.workspace.service.RepositoryContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.Set;

@Service
class BranchGraphVectorAuthority {
    record Capture(BranchGraphVectorSource source, CatalogueDefinition definition,
                   Set<RelationProjectionReadService.RelationIdentity> relations, String modelIdentity) { }

    private final RelationProjectionReadService relations;
    private final RelationBranchProjectionReadinessService readiness;
    private final CatalogueSourceJournal catalogue;
    private final CatalogueSnapshotService snapshots;
    private final LocalEmbeddingService embeddings;
    private final ObjectMapper json;

    BranchGraphVectorAuthority(RelationProjectionReadService relations, RelationBranchProjectionReadinessService readiness,
                               CatalogueSourceJournal catalogue, CatalogueSnapshotService snapshots,
                               LocalEmbeddingService embeddings, ObjectMapper json) {
        this.relations = relations;
        this.readiness = readiness;
        this.catalogue = catalogue;
        this.snapshots = snapshots;
        this.embeddings = embeddings;
        this.json = json;
    }

    void requireHead(RepositoryContext context, String expected) {
        var actual = readiness.readCurrentHead(context);
        if (!java.util.Objects.equals(expected, actual)) throw new RelationProjectionOperationsService.RebuildHeadConflictException(expected, actual);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void verifyPublication(RepositoryContext context, BranchGraphVectorSource expected) {
        var locked = catalogue.lockForCapture();
        if (locked == null || !expected.catalogueRevision().equals(locked.id())
                || !expected.equals(capture(context).source())) throw new SearchUnavailableException();
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    Capture capture(RepositoryContext context) {
        var projection = relations.readIdentitySnapshot(context);
        var official = catalogue.current();
        if (projection.readModel() != RelationProjectionReadService.ReadModel.PROJECTION
                || projection.readinessState() != RelationBranchProjectionReadinessService.ReadinessState.READY
                || projection.identities().isEmpty() || projection.commitId() == null || official == null
                || !projection.commitId().equals(readiness.readCurrentHead(context))) throw new SearchUnavailableException();
        try {
            var definition = snapshots.currentDefinition();
            var model = embeddings.embeddingIdentity();
            var source = BranchGraphVectorSource.from(context, projection.commitId(), official.id(),
                    StableIdentityHash.sha256(json.writeValueAsString(definition.spec())), model.indexKey(),
                    projection.identities());
            return new Capture(source, definition, projection.identities(), json.writeValueAsString(model));
        } catch (Exception unavailable) {
            throw new SearchUnavailableException();
        }
    }
}
