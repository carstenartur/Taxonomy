package com.taxonomy.catalog.definition;

import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.catalog.definition.CatalogueParentIndex.Entry;
import com.taxonomy.dsl.model.ArchitectureElement;
import com.taxonomy.dsl.model.CatalogueDefinition;
import com.taxonomy.dsl.model.CatalogueDefinitionResolver;
import com.taxonomy.dsl.model.CatalogueReference;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/** A descriptor belongs to the bytes of one recorded workbook, including after an import or restart. */
@Service
public class RetainedCatalogueDefinitions implements CatalogueDefinitionResolver {
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
        if (hierarchy == null) return Map.of();
        if (!model.getCatalogueResponsibilities().isEmpty()) {
            return appliedGeneration(model.responsibilityCatalogueRevision()).map(generation ->
                    analysisParents(model, generation.revision())).orElseGet(Map::of);
        }
        return hierarchy.parentsFor(model);
    }

    public Map<String, String> analysisParents(CanonicalArchitectureModel model, String revision) {
        if (revision == null || hierarchy == null) return Map.of();
        return hierarchy.parentsFor(model, revision, forGeneration(archivedHierarchy(revision)));
    }

    private CatalogueSourceJournal.Snapshot archivedHierarchy(String revision) {
        var snapshot = sources.findRevision(revision).orElseThrow();
        if (snapshot.workbook().use() != CatalogueSourceJournal.Use.APPLIED
                || snapshot.overlay().use() != CatalogueSourceJournal.Use.APPLIED
                && snapshot.overlay().use() != CatalogueSourceJournal.Use.NOT_USED)
            throw new NoSuchElementException("The archived catalogue hierarchy was not retained");
        return snapshot;
    }

    @Override
    public List<Node> analysisNodes(String revision) {
        if (revision == null || hierarchy == null) return List.of();
        archivedHierarchy(revision);
        return hierarchy.catalogueNodes(revision);
    }

    @Override
    public Map<String, String> translatedTitles(CanonicalArchitectureModel model, String language) {
        var embedded = CatalogueDefinitionResolver.super.translatedTitles(model, language);
        if (!"de".equals(language) || embedded.size() == model.getElements().size()) return embedded;
        var result = new HashMap<>(embedded);
        addMatchingTitles(model, sourceIndex(model.getCatalogueRevision()), false, result);
        // An adopted source may come from a different edition than the target workspace, or predate its pin.
        if (model.getElements().stream().anyMatch(e -> !result.containsKey(e.getId())
                && e.getExtensions().containsKey(CatalogueReference.UUID_PROPERTY))) {
            addMatchingTitles(model, bundledIndex(), true, result);
        }
        return result;
    }

    private static void addMatchingTitles(CanonicalArchitectureModel model, SourceIndex index,
                                          boolean requireUuid, Map<String, String> result) {
        for (var element : model.getElements()) {
            var entry = index.entries().get(element.getId());
            if (entry != null && entry.node().uuid() != null && hasText(entry.node().nameDe())
                    && entry.node().nameEn().equals(element.getTitle()) && matches(element, index, entry, requireUuid)) {
                result.putIfAbsent(element.getId(), entry.node().nameDe());
            }
        }
    }

    @Override
    public Map<String, SourceText> sourceTexts(CanonicalArchitectureModel model, String language) {
        if (model.getElements().isEmpty()) return Map.of();
        String modelRevision = model.getCatalogueRevision();
        boolean requireUuid = modelRevision == null;
        if (requireUuid && model.getElements().stream().noneMatch(
                element -> element.getExtensions().containsKey(CatalogueReference.UUID_PROPERTY))) return Map.of();
        var index = requireUuid ? bundledIndex() : sourceIndex(modelRevision);
        Map<String, SourceText> result = new HashMap<>();
        for (var element : model.getElements()) {
            var text = sourceText(element, index, requireUuid, language);
            if (text != null) result.put(element.getId(), text);
        }
        return result;
    }

    private record SourceIndex(CatalogueDefinition definition, Map<String, Entry> entries) {}

    private static SourceIndex bundledIndex() {
        return new SourceIndex(CatalogueDefinition.standard(), BundledCatalogue.originalEditionTranslations());
    }

    private SourceIndex sourceIndex(String revision) {
        var generation = revision == null ? null : sources.findRevision(revision).orElse(null);
        if (generation == null || generation.workbook().use() != CatalogueSourceJournal.Use.APPLIED)
            return new SourceIndex(CatalogueDefinition.standard(), Map.of());
        if (BundledCatalogue.needsTranslationUpgrade(generation.workbook().sha256())) {
            // The bilingual edition preserves every original English cell and source UUID.
            return bundledIndex();
        }
        var entries = hierarchy == null ? Map.<String, Entry>of() : hierarchy.sourceEntries(revision);
        return new SourceIndex(forGeneration(generation), entries);
    }

    private static SourceText sourceText(ArchitectureElement element, SourceIndex index,
                                         boolean requireUuid, String language) {
        var entry = index.entries().get(element.getId());
        if (entry == null || !matches(element, index, entry, requireUuid)) return null;
        var node = entry.node();
        boolean german = "de".equals(language);
        String title = german && hasText(node.nameDe()) ? node.nameDe() : node.nameEn();
        boolean translated = german && hasText(entry.descriptionDe());
        return new SourceText(title, title.equals(node.nameEn()) ? null : node.nameEn(),
                translated ? entry.descriptionDe() : entry.descriptionEn(), translated ? "de" : "en");
    }

    private static boolean matches(ArchitectureElement element, SourceIndex index, Entry entry, boolean requireUuid) {
        var node = entry.node();
        String uuid = element.getExtensions().get(CatalogueReference.UUID_PROPERTY);
        return !node.code().equals(node.root())
                && Objects.equals(index.definition().typeFor(node.root()), element.getType())
                && (element.getTaxonomy() == null || element.getTaxonomy().equals(node.root()))
                && (uuid == null ? !requireUuid && node.nameEn().equals(element.getTitle()) : uuid.equalsIgnoreCase(node.uuid()));
    }

    private static boolean hasText(String value) { return value != null && !value.isBlank(); }

    @Override
    public AssignmentTarget assignmentTarget(CanonicalArchitectureModel model, String code) {
        return hierarchy == null ? new AssignmentTarget(false, null)
                : appliedGeneration(model.responsibilityCatalogueRevision()).map(generation -> new AssignmentTarget(
                        hierarchy.contains(generation.revision(), code), model.getCatalogueRevision() == null
                                || model.getCatalogueRevision().isBlank() ? generation.revision() : null))
                .orElseGet(() -> new AssignmentTarget(false, null));
    }

    @Override
    public List<Node> catalogueNodes(CanonicalArchitectureModel model) {
        if (hierarchy == null) return List.of();
        return appliedGeneration(model.responsibilityCatalogueRevision())
                .map(generation -> analysisNodes(generation.revision())).orElseGet(List::of);
    }

    @Override
    public CatalogueDefinition forModel(com.taxonomy.dsl.model.CanonicalArchitectureModel model) {
        String revision = model.responsibilityCatalogueRevision();
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
