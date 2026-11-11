package com.taxonomy.backup.runtime;

import com.taxonomy.catalog.definition.WorkbookCatalogueDefinition;
import com.taxonomy.catalog.service.CatalogueOverlayService;
import com.taxonomy.catalog.snapshot.CatalogueRuntimePolicy;
import com.taxonomy.catalog.snapshot.CatalogueSnapshotService;
import com.taxonomy.catalog.snapshot.CatalogueSourceIdentity;
import com.taxonomy.catalog.snapshot.FrozenCatalogueContext;
import tools.jackson.databind.ObjectMapper;
import com.taxonomy.catalog.definition.RetainedCatalogueDefinitions;
import com.taxonomy.dsl.export.TaxDslExportService;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.editor.ArchitectureCommandPort;
import com.taxonomy.editor.ArchitectureEditorProjection;
import com.taxonomy.editor.ArchitectureEditorService;
import com.taxonomy.export.LayeredDiagramLayoutService;
import com.taxonomy.dto.AnalysisMode;
import com.taxonomy.dto.AnalysisScope;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogueDefinitionImportIT {
    @Test void addedMuseumCollectionSheetImportsItsDeepBranchAndRetainsPriorWorkbook() throws Exception {
        try (var fixture = new CatalogueSourceLoaderIT.Fixture()) {
            byte[] legacy = CatalogueSourceLoaderIT.workbook("Legacy", true);
            fixture.resources.workbook.bytes = legacy;
            fixture.load(false, false);
            var previous = fixture.journal.current();
            fixture.resources.workbook.bytes = MuseumCatalogueWorkbook.extend(legacy);
            fixture.load(false, true);

            var scope = new AnalysisScope(Set.of("MC"), AnalysisMode.FULL);
            var selected = fixture.tx.execute(tx -> {
                scope.validateRoots(fixture.service.getRootCodes());
                return scope.selectedTree(fixture.service.getFullTree());
            });
            assertThat(selected).hasSize(1);
            var current = selected.getFirst();
            for (int depth = 1; depth <= 10; depth++) {
                assertThat(current.getChildren()).hasSize(1);
                current = current.getChildren().getFirst();
                assertThat(current.getCode()).isEqualTo("MC-" + depth);
                assertThat(current.getLevel()).isEqualTo(depth);
            }
            var definitions = new RetainedCatalogueDefinitions(fixture.journal);
            var exported = fixture.tx.execute(tx -> new TaxDslExportService(fixture.nodes, fixture.relations, fixture.journal, definitions).exportAll("museum"));
            var model = new ArchitectureDslCommands().model(exported);
            assertThat(model.getCatalogueRevision()).isEqualTo(fixture.journal.current().id());
            var leaf = model.getElements().stream().filter(node -> node.getId().equals("MC-10")).findFirst().orElseThrow();
            assertThat(leaf.getType()).isEqualTo("MuseumCollection");
            assertThat(leaf.getExtensions().get("x-catalogue-parent")).isEqualTo("MC-9");
            var context = new ArchitectureCommandPort.Context("library", "central", "draft", "abc", 1L, "reviewer", "READ_ONLY");
            var document = new ArchitectureEditorService.Document(context, exported, "READY", List.of(), List.of(), 1L, null, "CHECKPOINT");
            var editor = new ArchitectureEditorProjection(new LayeredDiagramLayoutService(), definitions);
            var view = fixture.tx.execute(tx -> editor.project(document, false));
            assertThat(view.schema().typeRoots()).containsEntry("MuseumCollection", "MC");
            assertThat(view.schema().relationDomains().get("USES").sourceDomains().get("MC").allows("IP")).isTrue();
            assertThat(view.scene().nodes()).extracting(node -> node.id()).contains("MC-10");
            assertThat(fixture.journal.current().id()).isNotEqualTo(previous.id());
            try (var old = new XSSFWorkbook(fixture.journal.retainedWorkbook(previous.workbook()).openStream())) {
                assertThat(WorkbookCatalogueDefinition.read(old).rootCodes())
                        .containsExactly("BP", "BR", "CP", "CI", "CO", "CR", "IP", "UA");
            }
        }
    }

    @Test void historicalCaptureReadsTheRetainedWorkbookAndOverlayAfterReplacement() throws Exception {
        try (var fixture = new CatalogueSourceLoaderIT.Fixture()) {
            fixture.resources.workbook.bytes = MuseumCatalogueWorkbook.extend(CatalogueSourceLoaderIT.workbook("First collection", true));
            fixture.resources.overlay.bytes = CatalogueSourceLoaderIT.overlay("museum-overlay-1");
            fixture.load(true, false);
            String generation = fixture.journal.current().id();
            var service = new CatalogueSnapshotService(fixture.nodes,
                    new CatalogueOverlayService(new ObjectMapper(), fixture.resources, true, "memory:overlay.json"),
                    CatalogueRuntimePolicy.fullCatalogue(), fixture.journal, new RetainedCatalogueDefinitions(fixture.journal));
            assertThat(service.currentDefinition().typeFor("MC")).isEqualTo("MuseumCollection");

            fixture.resources.workbook.bytes = CatalogueSourceLoaderIT.workbook("Replacement", true);
            fixture.resources.overlay.bytes = CatalogueSourceLoaderIT.overlay("replacement-overlay");
            fixture.load(true, true);
            assertThat(service.currentDefinition().rootCodes()).doesNotContain("MC");

            var source = new CatalogueSourceIdentity("museum", "library", "project", "project-commit");
            var captured = service.captureRevision(source, generation, Set.of("MC", "CP"));
            try (var ignored = service.bind(source, Set.of("MC", "CP"), captured)) {
                var view = FrozenCatalogueContext.current();
                assertThat(view.node("MC-10").getParent().getCode()).isEqualTo("MC-9");
                assertThat(view.node("CP-1").getNameEn()).isEqualTo("First collection");
                assertThat(view.overlayMetadata().mappingVersion()).isEqualTo("museum-overlay-1");
                assertThat(view.definition().typeFor("MC")).isEqualTo("MuseumCollection");
            }
            assertThat(fixture.journal.current().id()).isNotEqualTo(generation);
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    service.captureRevision(source, fixture.journal.current().id(), Set.of("MC")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

}
