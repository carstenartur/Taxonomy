package com.taxonomy.catalog.definition;

import com.taxonomy.dsl.model.ArchitectureElement;
import com.taxonomy.dsl.model.ArchitectureRelation;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.dsl.model.CatalogueDefinition;
import com.taxonomy.dsl.validation.DslValidator;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.catalog.provenance.CatalogueSourceBytes;
import com.taxonomy.catalog.service.TaxonomyService;
import org.apache.poi.ss.usermodel.Sheet;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkbookCatalogueDefinitionTest {
    @Test void bundledWorkbookKeepsEveryStandardSheetAndRootIdentity() throws Exception {
        try (var bytes = getClass().getResourceAsStream("/data/C3_Taxonomy_Catalogue_25AUG2025.xlsx");
             var book = new XSSFWorkbook(bytes)) {
            var roots = WorkbookCatalogueDefinition.read(book).roots();
            assertThat(roots).extracting(CatalogueDefinition.Root::code)
                    .containsExactly("BP", "BR", "CP", "CI", "CO", "CR", "IP", "UA");
            assertThat(roots).allSatisfy(root -> assertThat(book.getSheet(root.sheet()).getLastRowNum()).isPositive());
        }
    }

    @Test void optionalTranslationColumnsCanBeInsertedBetweenOriginalColumns() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("Business Processes");
            row(sheet, 0, "Page", "UUID", "Title", "Title DE", "Description", "Description DE", "Parent",
                    "Dataset", "External ID", "Source", "Reference", "Order", "State", "Level");
            row(sheet, 1, "BP-20", "4ec1702a-929e-42a8-807b-cb7c82e1ab47", "Accounting", "Buchhaltung",
                    "Accounts", "Konten", "BP-10", "data", "external", "author", "source ref", "30", "approved", "3");
            var imported = TaxonomyService.readWorkbookNodes(book);
            var node = imported.nodes().get("BP-20");
            assertThat(node.getNameDe()).isEqualTo("Buchhaltung");
            assertThat(node.getDescriptionEn()).isEqualTo("Accounts");
            assertThat(node.getDescriptionDe()).isEqualTo("Konten");
            assertThat(node.getParentCode()).isEqualTo("BP-10");
            assertThat(node.getLevel()).isEqualTo(3);
            assertThat(node.getExternalId()).isEqualTo("external");
            assertThat(imported.uuidToCode()).containsEntry("4ec1702a-929e-42a8-807b-cb7c82e1ab47", "BP-20");
        }
    }

    @Test void partialNamedWorksheetNeverUsesTranslatedTextAsAnIdentifierOrSourceDescription() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("Business Processes");
            row(sheet, 0, "Page", "Title DE", "Title");
            row(sheet, 1, "BP-20", "Buchhaltung", "Accounting");
            var imported = TaxonomyService.readWorkbookNodes(book);
            var node = imported.nodes().get("BP-20");
            assertThat(imported.uuidToCode()).isEmpty();
            assertThat(node.getUuid()).isNull();
            assertThat(node.getNameDe()).isEqualTo("Buchhaltung");
            assertThat(node.getDescriptionEn()).isNull();
            assertThat(node.getParentCode()).isNull();
        }
    }

    @Test void bilingualEditionAddsEveryProjectTitleAndPreservesEverySourceCell() throws Exception {
        try (var original = getClass().getResourceAsStream("/data/C3_Taxonomy_Catalogue_25AUG2025.xlsx");
             var translated = getClass().getResourceAsStream("/" + BundledCatalogue.BILINGUAL_PATH);
             var sourceBook = new XSSFWorkbook(original); var translatedBook = new XSSFWorkbook(translated)) {
            var sourceNodes = TaxonomyService.readWorkbookNodes(sourceBook);
            var translatedNodes = TaxonomyService.readWorkbookNodes(translatedBook);
            assertThat(translatedNodes.uuidToCode()).hasSize(2564).isEqualTo(sourceNodes.uuidToCode());
            assertThat(translatedNodes.nodes()).containsOnlyKeys(sourceNodes.nodes().keySet().toArray(String[]::new));
            for (var sheet : sourceBook) {
                var copy = translatedBook.getSheet(sheet.getSheetName());
                assertThat(copy.getLastRowNum()).isEqualTo(sheet.getLastRowNum());
                assertThat(copy.getRow(0).getCell(12).getStringCellValue()).isEqualTo("Title DE");
                for (var row : sheet) {
                    var translatedRow = copy.getRow(row.getRowNum());
                    for (var cell : row) {
                        var actual = translatedRow.getCell(cell.getColumnIndex());
                        assertThat(actual.getCellType()).isEqualTo(cell.getCellType());
                        assertThat(actual.toString()).isEqualTo(cell.toString());
                    }
                }
            }
            assertThat(translatedNodes.nodes().values()).allSatisfy(node -> assertThat(node.getNameDe()).isNotBlank());
            assertThat(translatedNodes.nodes().get("BP-1016").getNameDe()).isEqualTo("Rechnungswesen");
        }
    }

    @Test void historicalGermanDisplayRequiresAnIdenticalSourceAndPreservesEditedTitles() throws Exception {
        var journal = mock(CatalogueSourceJournal.class);
        try (var source = getClass().getResourceAsStream("/data/C3_Taxonomy_Catalogue_25AUG2025.xlsx")) {
            var bytes = CatalogueSourceBytes.read(source);
            var workbook = new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.APPLIED, bytes.sha256(), bytes.length());
            var unused = new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.NOT_USED, null, 0);
            var generation = new CatalogueSourceJournal.Snapshot("old", Instant.EPOCH, workbook, unused, unused);
            when(journal.findRevision("old")).thenReturn(Optional.of(generation));
            when(journal.retainedWorkbook(workbook)).thenReturn(bytes);
            var reader = new RetainedCatalogueDefinitions(journal);
            var model = new CanonicalArchitectureModel();
            model.setCatalogueRevision("old");
            var entry = new ArchitectureElement("BP-1016", "Process", "Accounting", null, "BP");
            model.getElements().add(entry);
            assertThat(reader.translatedTitles(model, "de")).isEqualTo(java.util.Map.of("BP-1016", "Rechnungswesen"));
            assertThat(entry.getTitle()).isEqualTo("Accounting");
            assertThat(reader.translatedTitles(model, "en")).isEmpty();
            entry.getExtensions().put("x-catalogue-uuid", "d3483d54-ed70-420e-8da9-f4851258cb65");
            assertThat(reader.translatedTitles(model, "de")).isEmpty();
            entry.getExtensions().clear();
            entry.setTitle("Project costing");
            assertThat(reader.translatedTitles(model, "de")).isEmpty();
            entry.setTitle("Accounting");
            model.setCatalogueRevision(null);
            assertThat(reader.translatedTitles(model, "de")).isEmpty();
            entry.getExtensions().put("x-catalogue-uuid", "4ec1702a-929e-42a8-807b-cb7c82e1ab47");
            assertThat(reader.translatedTitles(model, "de")).isEqualTo(java.util.Map.of("BP-1016", "Rechnungswesen"));
            entry.setType("System");
            assertThat(reader.translatedTitles(model, "de")).isEmpty();
        }
    }

    @Test void configuredTypesValidateOnlyTheirDeclaredDirectionsInTheDsl() {
        var custom = new CatalogueDefinition("museum", true,
                List.of(new CatalogueDefinition.Root("Collections", "MC", "MuseumCollection")),
                List.of(new CatalogueDefinition.Direction("MC", "USES", "IP")));
        var validator = new DslValidator(model -> custom);
        var model = new CanonicalArchitectureModel();
        model.getElements().add(new ArchitectureElement("collection", "MuseumCollection", "Collection", null, null));
        model.getElements().add(new ArchitectureElement("catalogue", "InformationProduct", "Catalogue", null, null));
        model.getRelations().add(new ArchitectureRelation("collection", "USES", "catalogue"));
        assertThat(validator.validate(model).getWarnings()).isEmpty();
        model.getRelations().add(new ArchitectureRelation("catalogue", "USES", "collection"));
        assertThat(validator.validate(model).getWarnings()).hasSize(1);
    }

    @Test void legacyWorkbookAndItsExplicitExtensionHaveIndependentRootAndRelationDefinitions() throws Exception {
        try (var legacy = new XSSFWorkbook(); var extended = new XSSFWorkbook()) {
            var original = WorkbookCatalogueDefinition.read(legacy);
            assertThat(original.rootCodes()).containsExactly("BP", "BR", "CP", "CI", "CO", "CR", "IP", "UA");
            var schema = extended.createSheet("Catalogue Definition");
            row(schema, 0, "catalogue-definition-v1", "library-2026", "standard-v1");
            row(schema, 1, "root", "Museum Collections", "MC", "MuseumCollection");
            row(schema, 2, "direction", "MC", "USES", "IP");
            extended.createSheet("Museum Collections");
            var definition = WorkbookCatalogueDefinition.read(extended);
            assertThat(definition.rootCodes()).containsExactly("BP", "BR", "CP", "CI", "CO", "CR", "IP", "UA", "MC");
            assertThat(definition.typeFor("MC")).isEqualTo("MuseumCollection");
            assertThat(definition.rootFor("MuseumCollection")).isEqualTo("MC");
            assertThat(definition.allows("MC", "USES", "IP")).isTrue();
            assertThat(definition.allows("IP", "USES", "MC")).isFalse();
            assertThat(definition.allows("MC", "UNKNOWN", "IP")).isFalse();
            assertThat(original.rootCodes()).doesNotContain("MC");
            assertThat(original.allows("MC", "USES", "IP")).isFalse();
        }
    }

    @Test void archiveLookupReopensVersionAAfterAnotherWorkbookBecameCurrent() throws Exception {
        var journal = mock(CatalogueSourceJournal.class);
        try (var workbook = new XSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
            var schema = workbook.createSheet("Catalogue Definition");
            row(schema, 0, "catalogue-definition-v1", "registers-A", "empty");
            row(schema, 1, "root", "Public registers", "PR", "PublicRegister");
            row(schema, 2, "direction", "PR", "USES", "PR");
            workbook.createSheet("Public registers");
            workbook.write(bytes);
            var archived = CatalogueSourceBytes.read(new ByteArrayInputStream(bytes.toByteArray()));
            var reference = new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.APPLIED, archived.sha256(), archived.length());
            var absent = new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.NOT_RETAINED, null, 0);
            var generationA = new CatalogueSourceJournal.Snapshot("A", Instant.EPOCH, reference, absent, absent);
            var generationB = new CatalogueSourceJournal.Snapshot("B", Instant.EPOCH.plusSeconds(1), absent, absent, absent);
            when(journal.findRevision("A")).thenReturn(Optional.of(generationA));
            when(journal.findRevision("B")).thenReturn(Optional.of(generationB));
            when(journal.retainedWorkbook(reference)).thenReturn(archived);
            when(journal.current()).thenReturn(generationB);
            var reader = new RetainedCatalogueDefinitions(journal);
            assertThat(reader.current().rootCodes()).doesNotContain("PR");
            assertThat(reader.forRevision("A").typeFor("PR")).isEqualTo("PublicRegister");
            assertThat(reader.forRevision("A").allows("PR", "USES", "PR")).isTrue();
            assertThatIllegalStateException().isThrownBy(() -> reader.forRevision("B"));
            assertThat(reader.appliedGeneration(null)).isEmpty();
            assertThat(reader.appliedGeneration("A")).hasValueSatisfying(applied -> {
                assertThat(applied.revision()).isEqualTo("A");
                assertThat(applied.definition().allows("PR", "USES", "PR")).isTrue();
            });
            assertThat(reader.appliedGeneration("B")).isEmpty();
            assertThat(reader.appliedGeneration("unknown")).isEmpty();
            org.mockito.Mockito.verify(journal, org.mockito.Mockito.times(2)).current();
        }
    }

    @Test void rejectsAmbiguousRootMappingsMissingSheetsAndUnknownRelationTypes() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var schema = workbook.createSheet("Catalogue Definition");
            row(schema, 0, "catalogue-definition-v1", "museum", "standard-v1");
            row(schema, 1, "root", "Museum Collections", "MC", "MuseumCollection");
            assertThatIllegalArgumentException().isThrownBy(() -> WorkbookCatalogueDefinition.read(workbook));
            workbook.createSheet("Museum Collections");
            row(schema, 2, "direction", "MC", "UNKNOWN", "IP");
            assertThatIllegalArgumentException().isThrownBy(() -> WorkbookCatalogueDefinition.read(workbook));
            row(schema, 2, "root", "Museum Holdings", "MC", "Holdings");
            workbook.createSheet("Museum Holdings");
            assertThatIllegalArgumentException().isThrownBy(() -> WorkbookCatalogueDefinition.read(workbook));
        }
    }

    private static void row(Sheet sheet, int index, String... cells) {
        var row = sheet.createRow(index);
        for (int i = 0; i < cells.length; i++) row.createCell(i).setCellValue(cells[i]);
    }
}
