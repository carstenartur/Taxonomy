package com.taxonomy.relations.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.catalog.service.CatalogueOverlayService;
import com.taxonomy.catalog.service.EmbeddingModelIdentity;
import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.catalog.snapshot.*;
import com.taxonomy.dsl.model.CatalogueDefinition;
import com.taxonomy.model.RelationType;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BranchGraphVectorBuildTest {
    @Test void buildsBothFieldsFromTheSameFrozenBranchAndReopensThePublishedBytes() throws Exception {
        var context = RepositoryContext.workspace("repository", "copy", "draft", "actor");
        String commit = "1".repeat(40);
        var selection = new CatalogueSourceIdentity(context.repositoryId(), context.workspaceId(), context.branch(), commit);
        var model = new EmbeddingModelIdentity("a".repeat(64), "b".repeat(64), Map.of(), "query: ", EmbeddingModelIdentity.INFERENCE_VERSION);
        var edge = new RelationProjectionReadService.RelationIdentity("CP", RelationType.SUPPORTS, "IP");
        var definition = CatalogueDefinition.standard();
        String revision = "00000000-0000-0000-0000-000000000001";
        var source = BranchGraphVectorSource.from(context, commit, revision, "c".repeat(64), model.indexKey(), Set.of(edge));
        var json = JsonMapper.builder().build();
        String modelIdentity = json.writeValueAsString(model);
        var authority = mock(BranchGraphVectorAuthority.class);
        when(authority.capture(context)).thenReturn(new BranchGraphVectorAuthority.Capture(source, definition, Set.of(edge), modelIdentity));
        var relations = mock(RelationProjectionReadService.class);
        when(relations.readIdentitySnapshot(context)).thenReturn(new RelationProjectionReadService.IdentitySnapshot(
                RelationProjectionReadService.ReadModel.PROJECTION, RelationBranchProjectionReadinessService.ReadinessState.READY, commit, Set.of(edge)));
        var embeddings = mock(LocalEmbeddingService.class);
        when(embeddings.embeddingIdentity()).thenReturn(model);
        var submitted = new ArrayList<String>();
        when(embeddings.embedDocument(anyString())).thenAnswer(call -> {
            submitted.add(call.getArgument(0)); return BranchGraphVectorSearchTest.vector();
        });
        var official = new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.NOT_RETAINED, null, 0);
        var overlay = new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.NOT_USED, null, 0);
        var edition = new CatalogueSourceJournal.Snapshot(revision, Instant.EPOCH, official, overlay, official);
        var snapshots = mock(CatalogueSnapshotService.class);
        var roots = new ArrayList<RootCatalogueSnapshot>();
        for (var root : definition.rootCodes()) {
            var node = new TaxonomyNode();
            node.setCode(root); node.setTaxonomyRoot(root); node.setNameEn("Frozen " + root); node.setLevel(0);
            var metadata = new CatalogueOverlayService.NodeMetadata("CATEGORY", List.of(), 1, false, null);
            roots.add(new RootCatalogueSnapshot(RootCatalogueSnapshot.SCHEMA_VERSION, selection, root,
                    List.of(RootCatalogueSnapshot.Node.capture(node, metadata, false)),
                    new CatalogueOverlayService.OverlayMetadata(false,"frozen","fixture","v1","digest",1), edition));
        }
        when(snapshots.captureRoots(selection, definition.rootCodes())).thenReturn(roots);
        var store = mock(BranchGraphVectorStore.class);
        when(store.begin(context, source, modelIdentity)).thenReturn("generation");
        var retained = new AtomicReference<BranchGraphVectorIndex.Archive>();
        doAnswer(call -> {
            call.getArgument(4, Runnable.class).run();
            retained.set(call.getArgument(3));
            return null;
        }).when(store).publish(eq(context),eq(source),eq("generation"),any(),any());
        when(store.status(context, source)).thenReturn(new BranchGraphVectorStore.Status(BranchGraphVectorStore.State.READY,
                "generation", commit, model.indexKey(), roots.size(), 1));
        when(store.read(context,source)).thenAnswer(call -> new BranchGraphVectorStore.Generation("generation", retained.get()));
        var vectors = new BranchGraphVectors(store, authority, snapshots, new FrozenCatalogueEmbeddingService(relations, embeddings), embeddings, json);
        assertThat(vectors.rebuild(context,commit).state()).isEqualTo("READY");
        assertThat(retained.get().nodeCount()).isEqualTo(roots.size());
        assertThat(retained.get().relationCount()).isOne();
        assertThat(submitted).hasSize(roots.size() + 1).contains("Frozen CP supports Frozen IP");
        assertThat(submitted).anyMatch(text -> text.contains("Outgoing:") && text.contains("Frozen IP"));
        assertThat(submitted).anyMatch(text -> text.contains("Incoming:") && text.contains("Frozen CP"));
        verify(authority).verifyPublication(context,source);
    }
}
