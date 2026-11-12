package com.taxonomy.catalog.definition;

import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.dsl.model.CatalogueDefinition;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.IOException;

/** A descriptor belongs to the bytes of one recorded workbook, including after an import or restart. */
@Service
public class RetainedCatalogueDefinitions implements com.taxonomy.dsl.model.CatalogueDefinitionResolver {
    private final CatalogueSourceJournal sources;
    private String lastWorkbook;
    private CatalogueDefinition lastDefinition;

    public RetainedCatalogueDefinitions(CatalogueSourceJournal sources) { this.sources = sources; }

    @Override
    public CatalogueDefinition forModel(com.taxonomy.dsl.model.CanonicalArchitectureModel model) {
        String revision = model.getCatalogueRevision();
        if (revision == null) return CatalogueDefinition.standard();
        return forGeneration(sources.findRevision(revision)
                .orElseThrow(() -> new IllegalStateException("The project's catalogue revision is unavailable")));
    }

    public CatalogueDefinition current() {
        return com.taxonomy.catalog.snapshot.FrozenCatalogueContext.definition()
                .orElseGet(() -> forGeneration(sources.current()));
    }

    public synchronized CatalogueDefinition forGeneration(CatalogueSourceJournal.Snapshot generation) {
        if (generation == null || generation.workbook().use() == CatalogueSourceJournal.Use.NOT_RETAINED)
            return CatalogueDefinition.standard();
        var ref = generation.workbook();
        if (ref.use() != CatalogueSourceJournal.Use.APPLIED)
            throw new IllegalStateException("Catalogue definition has no applied workbook");
        if (ref.sha256().equals(lastWorkbook)) return lastDefinition;
        try (var input = sources.retainedWorkbook(ref).openStream(); var book = new XSSFWorkbook(input)) {
            var definition = WorkbookCatalogueDefinition.read(book);
            lastDefinition = definition;
            lastWorkbook = ref.sha256();
            return definition;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read the retained catalogue definition", failure);
        }
    }
}
