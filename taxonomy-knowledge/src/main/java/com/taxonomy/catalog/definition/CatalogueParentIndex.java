package com.taxonomy.catalog.definition;

import com.taxonomy.catalog.provenance.CatalogueSourceBytes;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.catalog.service.CatalogueOverlayService;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Restores omitted catalogue ancestry without importing any reference node into the architecture. */
@Component
public class CatalogueParentIndex {
    private final CatalogueSourceJournal sources;
    private final CatalogueOverlayService overlays;
    private record Parent(String root, String code) {}
    private String cachedRevision;
    private Map<String, Parent> cached;

    public CatalogueParentIndex(CatalogueSourceJournal sources, CatalogueOverlayService overlays) {
        this.sources = sources;
        this.overlays = overlays;
    }

    public synchronized Map<String, String> parentsFor(CanonicalArchitectureModel model) {
        if (model.getElements().stream().noneMatch(e -> e.getTaxonomy() != null)) return Map.of();
        String revision = model.getCatalogueRevision();
        if (cached == null || !java.util.Objects.equals(revision, cachedRevision)) {
            var loaded = read(revision);
            cached = loaded;
            cachedRevision = revision;
        }
        Map<String, String> parents = new HashMap<>();
        cached.forEach((id, parent) -> parents.put(id, parent.code()));
        for (var element : model.getElements()) {
            var candidate = cached.get(element.getId());
            if (candidate != null && !candidate.root().equals(element.getTaxonomy())) parents.remove(element.getId());
        }
        return parents;
    }

    private Map<String, Parent> read(String revision) {
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

    private Map<String, Parent> read(InputStream input, CatalogueSourceBytes overlay) throws IOException {
        try (var workbook = new XSSFWorkbook(input)) {
            var hierarchy = TaxonomyService.readWorkbookNodes(workbook);
            overlays.applyRetainedHierarchy(hierarchy.nodes(), hierarchy.uuidToCode(), overlay);
            Map<String, Parent> result = new HashMap<>();
            hierarchy.nodes().forEach((id, node) -> {
                if (node.getParentCode() != null) result.put(id, new Parent(node.getTaxonomyRoot(), node.getParentCode()));
            });
            return Map.copyOf(result);
        }
    }
}
