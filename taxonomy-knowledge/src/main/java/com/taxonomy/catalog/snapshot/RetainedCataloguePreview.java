package com.taxonomy.catalog.snapshot;

import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.dto.CatalogueAnalysisPreview.Association;
import com.taxonomy.dto.CatalogueAnalysisPreview.Node;
import com.taxonomy.dto.CatalogueAnalysisPreview.Ranking;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Reopens the retained archive for every caller. Only derived, requirement-independent data are shared. */
@Service
public class RetainedCataloguePreview {
    private final CatalogueSnapshotService catalogues;
    private final SemanticCataloguePreview semantic;
    private final LinkedHashMap<CatalogueSourceJournal.Snapshot, CataloguePreviewIndex> indexes =
            new LinkedHashMap<>(4, 0.75f, true);

    public RetainedCataloguePreview(CatalogueSnapshotService catalogues, SemanticCataloguePreview semantic) {
        this.catalogues = catalogues;
        this.semantic = semantic;
    }

    public Selection open(CatalogueSourceIdentity source, String revision) {
        // A warm index must still pass the archive's availability and integrity checks.
        var roots = catalogues.captureRevision(source, revision, Set.of());
        if (roots.isEmpty() || !revision.equals(roots.getFirst().catalogueProvenance().id())) {
            throw new IllegalStateException("The retained catalogue does not match the selected revision");
        }
        return new Selection(index(roots), roots);
    }

    private synchronized CataloguePreviewIndex index(List<RootCatalogueSnapshot> roots) {
        var generation = roots.getFirst().catalogueProvenance();
        var result = indexes.get(generation);
        if (result == null) {
            result = new CataloguePreviewIndex(roots);
            if (indexes.size() == 2) indexes.pollFirstEntry();
            indexes.put(generation, result);
        }
        return result;
    }

    public final class Selection {
        private final CataloguePreviewIndex index;
        private final List<RootCatalogueSnapshot> snapshot;

        private Selection(CataloguePreviewIndex index, List<RootCatalogueSnapshot> snapshot) {
            this.index = index;
            this.snapshot = snapshot;
        }

        public void prepare() {
            semantic.prepare(index, snapshot);
        }

        public Ranking query(String businessText, Set<String> roots) {
            var semanticResult = semantic.query(index, snapshot, businessText, roots);
            var result = semanticResult.orElseGet(() -> index.query(businessText, roots));
            var nodes = new ArrayList<Node>();
            result.ancestors().forEach(match -> nodes.add(node(match, null)));
            result.matches().forEach(match -> nodes.add(node(match, match.score())));
            var associations = result.associations().stream()
                    .map(pair -> new Association(pair.sourceCode(), pair.targetCode(), pair.sharedTerms())).toList();
            return new Ranking(semanticResult.isPresent() ? "CATALOGUE_ONNX" : "CATALOGUE_LEXICAL",
                    List.copyOf(nodes), associations);
        }

        private static Node node(CataloguePreviewIndex.Match match, Double score) {
            return new Node(match.code(), match.parentCode(), match.root(), match.nameEn(), match.nameDe(), score);
        }
    }
}
