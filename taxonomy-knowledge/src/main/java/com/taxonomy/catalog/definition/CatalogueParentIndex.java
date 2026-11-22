package com.taxonomy.catalog.definition;

import com.taxonomy.catalog.provenance.CatalogueSourceBytes;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.catalog.service.CatalogueOverlayService;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.dsl.model.CatalogueDefinition;
import com.taxonomy.dsl.model.CatalogueDefinitionResolver.Node;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Restores omitted catalogue ancestry without importing any reference node into the architecture. */
@Component
public class CatalogueParentIndex {
    private final CatalogueSourceJournal sources;
    private final CatalogueOverlayService overlays;
    record Entry(Node node, String descriptionEn, String descriptionDe) {}
    private record IndexedNode(Entry entry, int level) {
        Node node() { return entry.node(); }
    }
    private String cachedRevision;
    private Map<String, IndexedNode> cached;

    public CatalogueParentIndex(CatalogueSourceJournal sources, CatalogueOverlayService overlays) {
        this.sources = sources;
        this.overlays = overlays;
    }

    public synchronized Map<String, String> parentsFor(CanonicalArchitectureModel model) {
        var elementIds = model.allElementIds();
        boolean needsLookup = model.getElements().stream().anyMatch(element -> {
            String parent = element.getExtensions().get("x-catalogue-parent");
            return element.getTaxonomy() != null && !element.getTaxonomy().equals(parent) && !elementIds.contains(parent);
        });
        if (!needsLookup) return Map.of();
        return parentsFor(model, model.getCatalogueRevision(), null);
    }

    /** Read an exact catalogue even if only one assigned ancestor exists in the architecture. */
    public synchronized Map<String, String> parentsFor(CanonicalArchitectureModel model, String revision,
                                                       CatalogueDefinition definition) {
        var nodes = index(revision);
        Map<String, String> parents = new HashMap<>();
        nodes.forEach((id, entry) -> {
            if (entry.node().parentCode() != null) parents.put(id, entry.node().parentCode());
        });
        for (var element : model.getElements()) {
            var entry = nodes.get(element.getId());
            if (entry == null) continue;
            var candidate = entry.node();
            // Analysis adoption carries the catalogue ID and DSL type but may omit the taxonomy field.
            boolean sameRoot = candidate.root().equals(element.getTaxonomy())
                    || element.getTaxonomy() == null && definition != null
                    && element.getType() != null && element.getType().equals(definition.typeFor(candidate.root()));
            if (!sameRoot) parents.remove(element.getId());
        }
        return parents;
    }

    public synchronized List<Node> catalogueNodes(String revision) {
        return index(revision).values().stream().sorted(Comparator.comparingInt(IndexedNode::level)
                .thenComparing(entry -> entry.node().code())).map(IndexedNode::node).toList();
    }

    public synchronized boolean contains(String revision, String code) {
        return index(revision).containsKey(code);
    }

    synchronized Map<String, Entry> sourceEntries(String revision) {
        Map<String, Entry> entries = new HashMap<>();
        index(revision).forEach((code, indexed) -> entries.put(code, indexed.entry()));
        return entries;
    }

    private Map<String, IndexedNode> index(String revision) {
        if (cached == null || !java.util.Objects.equals(revision, cachedRevision)) {
            cached = read(revision);
            cachedRevision = revision;
        }
        return cached;
    }

    private Map<String, IndexedNode> read(String revision) {
        try {
            if (revision == null) {
                // Pre-pin C3 exports contain codes and roots but no parent links.
                var workbook = new ClassPathResource("data/C3_Taxonomy_Catalogue_25AUG2025.xlsx");
                var overlay = new ClassPathResource("data/nato-taxonomy.json");
                try (var input = workbook.getInputStream(); var patches = overlay.getInputStream()) {
                    return read(input, CatalogueSourceBytes.read(patches));
                }
            }
            var retained = sources.findRevision(revision).orElseThrow();
            if (retained.workbook().use() != CatalogueSourceJournal.Use.APPLIED) return Map.of();
            if (retained.overlay().use() != CatalogueSourceJournal.Use.APPLIED
                    && retained.overlay().use() != CatalogueSourceJournal.Use.NOT_USED) return Map.of();
            var overlay = retained.overlay().use() == CatalogueSourceJournal.Use.APPLIED
                    ? sources.retainedInput(retained.overlay()) : null;
            try (var input = sources.retainedWorkbook(retained.workbook()).openStream()) {
                return read(input, overlay);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the catalogue hierarchy for the selected model", e);
        }
    }

    private Map<String, IndexedNode> read(InputStream input, CatalogueSourceBytes overlay) throws IOException {
        try (var workbook = new XSSFWorkbook(input)) {
            var hierarchy = TaxonomyService.readWorkbookNodes(workbook);
            overlays.applyRetainedHierarchy(hierarchy.nodes(), hierarchy.uuidToCode(), overlay);
            Map<String, IndexedNode> result = new HashMap<>();
            hierarchy.nodes().forEach((id, node) -> result.put(id, new IndexedNode(
                    new Entry(new Node(id, node.getTaxonomyRoot(), node.getParentCode(), node.getNameEn(), node.getNameDe(),
                            node.getSortOrder(), node.getUuid()), node.getDescriptionEn(), node.getDescriptionDe()), node.getLevel())));
            return Map.copyOf(result);
        }
    }
}
