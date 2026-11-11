package com.taxonomy.architecture.service;

import com.taxonomy.dsl.mapper.AstToModelMapper;
import com.taxonomy.dsl.model.CatalogueDefinitionResolver;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.relations.service.RelationProjectionReadService;
import com.taxonomy.relations.service.RelationProjectionReadService.Snapshot;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceDslReadPort;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/** One detached model and relation proof, read at one exact selected commit per query. */
@Service
public class GraphSnapshotReadService {
    private final RelationProjectionReadService relations;
    private final WorkspaceDslReadPort reads;

    private final CatalogueDefinitionResolver catalogues;

    public GraphSnapshotReadService(RelationProjectionReadService relations, WorkspaceDslReadPort reads) {
        this(relations, reads, CatalogueDefinitionResolver.standard());
    }
    @org.springframework.beans.factory.annotation.Autowired
    public GraphSnapshotReadService(RelationProjectionReadService relations, WorkspaceDslReadPort reads, CatalogueDefinitionResolver catalogues) {
        this.catalogues = catalogues;
        this.relations = relations;
        this.reads = reads;
    }

    public SelectedGraphSnapshot read(RepositoryContext context, String commitId) {
        Snapshot proof = relations.readSnapshot(context, commitId);
        var repository = reads.openRead(context);
        try {
            String dsl = repository.dslAtCommit(proof.commitId())
                    .orElseThrow(() -> new IllegalStateException("Selected commit has no canonical DSL evidence"));
            var model = new AstToModelMapper().map(new TaxDslParser().parse(dsl, "architecture.taxdsl"));
            var catalogue = catalogues.forModel(model);
            Map<String, SnapshotNode> nodes = new LinkedHashMap<>();
            model.getElements().forEach(element -> {
                String root = catalogue.rootFor(element.getType());
                if (nodes.putIfAbsent(element.getId(), new SnapshotNode(element.getId(), element.getTitle(),
                        root == null ? "" : root, element.getExtensions())) != null)
                    throw new IllegalStateException("Duplicate element in selected graph snapshot");
            });
            Set<String> requirements = model.getRequirements().stream().map(requirement -> requirement.getId()).collect(Collectors.toSet());
            List<SnapshotMapping> mappings = model.getMappings().stream()
                    .filter(mapping -> requirements.contains(mapping.getRequirementId()))
                    .map(mapping -> new SnapshotMapping(mapping.getRequirementId(), mapping.getElementId(),
                            mapping.getSource(), mapping.getScore())).toList();
            return new SelectedGraphSnapshot(proof, nodes, mappings, repository);
        } catch (IOException error) {
            throw new IllegalStateException("Selected graph snapshot is not readable", error);
        }
    }

    public void verifyHead(SelectedGraphSnapshot snapshot) {
        try { snapshot.repository().verifyExpectedHead(snapshot.proof().commitId()); }
        catch (IOException error) { throw new IllegalStateException("Selected graph head is no longer proven", error); }
    }

    public record SnapshotNode(String id, String title, String taxonomyRoot, Map<String,String> extensions) {
        public SnapshotNode { extensions = Map.copyOf(extensions); }
    }
    public record SnapshotMapping(String requirementId, String elementId, String source, Double score) { }
    public record SelectedGraphSnapshot(Snapshot proof, Map<String, SnapshotNode> nodes,
                                        List<SnapshotMapping> mappings, WorkspaceDslReadPort.RepositoryRead repository) {
        public SelectedGraphSnapshot {
            Objects.requireNonNull(proof); Objects.requireNonNull(repository);
            nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
            mappings = List.copyOf(mappings);
        }
    }
}
