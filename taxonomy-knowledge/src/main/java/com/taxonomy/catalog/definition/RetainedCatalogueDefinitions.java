package com.taxonomy.catalog.definition;

import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.dsl.model.CatalogueDefinition;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;
import java.util.NoSuchElementException;

/** A descriptor belongs to the bytes of one recorded workbook, including after an import or restart. */
@Service
public class RetainedCatalogueDefinitions implements com.taxonomy.dsl.model.CatalogueDefinitionResolver {
    private final CatalogueSourceJournal sources;
    private String lastWorkbook;
    private CatalogueDefinition lastDefinition;

    private final CatalogueParentIndex hierarchy;

    public RetainedCatalogueDefinitions(CatalogueSourceJournal sources) { this(sources, null); }

    @org.springframework.beans.factory.annotation.Autowired
    public RetainedCatalogueDefinitions(CatalogueSourceJournal sources, @Lazy CatalogueParentIndex hierarchy) {
        this.sources = sources;
        this.hierarchy = hierarchy;
    }

    @Override
    public java.util.Map<String, String> parentsForModel(com.taxonomy.dsl.model.CanonicalArchitectureModel model) {
        return hierarchy == null ? java.util.Map.of() : hierarchy.parentsFor(model);
    }

    public Map<String, String> analysisParents(CanonicalArchitectureModel model, String revision) {
        if (revision == null || hierarchy == null) return Map.of();
        var snapshot = sources.findRevision(revision).orElseThrow();
        if (snapshot.workbook().use() != CatalogueSourceJournal.Use.APPLIED
                || snapshot.overlay().use() != CatalogueSourceJournal.Use.APPLIED
                && snapshot.overlay().use() != CatalogueSourceJournal.Use.NOT_USED)
            throw new NoSuchElementException("The archived catalogue hierarchy was not retained");
        return hierarchy.parentsFor(model, revision, forGeneration(snapshot));
    }

    @Override
    public CatalogueDefinition forModel(com.taxonomy.dsl.model.CanonicalArchitectureModel model) {
        String revision = model.getCatalogueRevision();
        if (revision == null) return CatalogueDefinition.standard();
        return forGeneration(generation(revision));
    }

    @Override
    public CatalogueDefinition forRevision(String revision) {
        var retained = generation(revision);
        if (retained.workbook().use() != CatalogueSourceJournal.Use.APPLIED)
            throw new IllegalStateException("The recorded catalogue workbook was not retained as applied");
        return forGeneration(retained);
    }

    private CatalogueSourceJournal.Snapshot generation(String revision) {
        return sources.findRevision(revision)
                .orElseThrow(() -> new IllegalStateException("The project's catalogue revision is unavailable"));
    }

    public record AppliedGeneration(String revision, CatalogueDefinition definition) { }

    /** Resolve a project pin, or capture the current pointer once when the project has no pin. */
    public java.util.Optional<AppliedGeneration> appliedGeneration(String projectRevision) {
        var selected = projectRevision == null ? sources.current() : sources.findRevision(projectRevision).orElse(null);
        if (selected == null || selected.workbook().use() != CatalogueSourceJournal.Use.APPLIED)
            return java.util.Optional.empty();
        return java.util.Optional.of(new AppliedGeneration(selected.id(), forGeneration(selected)));
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
