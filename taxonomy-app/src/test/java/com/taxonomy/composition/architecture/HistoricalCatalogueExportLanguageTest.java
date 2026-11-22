package com.taxonomy.composition.architecture;

import com.taxonomy.catalog.definition.RetainedCatalogueDefinitions;
import com.taxonomy.catalog.provenance.CatalogueSourceBytes;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.editor.ArchitectureCommandPort.Context;
import com.taxonomy.editor.ArchitectureEditorExportAdapter;
import com.taxonomy.editor.ArchitectureEditorProjection;
import com.taxonomy.editor.ArchitectureEditorService;
import com.taxonomy.export.ArchitecturePdfRenderer;
import com.taxonomy.export.LayeredDiagramLayoutService;
import com.taxonomy.export.SvgDiagramRenderer;
import com.taxonomy.extension.api.plugin.ExtensionCatalog;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class HistoricalCatalogueExportLanguageTest {
    private static final String REVISION = "22025f68-64e8-438e-9540-58519a197bf8";
    private static final String COMMIT = "a1".repeat(20);

    @Test void historicalEnglishSourceIsDisplayedInGermanSvgAndPdfOnlyForUneditedCatalogueTitles() throws Exception {
        var journal = mock(CatalogueSourceJournal.class);
        try (var source = getClass().getResourceAsStream("/data/C3_Taxonomy_Catalogue_25AUG2025.xlsx")) {
            var bytes = CatalogueSourceBytes.read(source);
            var workbook = new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.APPLIED, bytes.sha256(), bytes.length());
            var unused = new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.NOT_USED, null, 0);
            when(journal.findRevision(REVISION)).thenReturn(Optional.of(
                    new CatalogueSourceJournal.Snapshot(REVISION, Instant.EPOCH, workbook, unused, unused)));
            when(journal.retainedWorkbook(workbook)).thenReturn(bytes);
        }
        var projection = new ArchitectureEditorProjection(new LayeredDiagramLayoutService(), new RetainedCatalogueDefinitions(journal));
        var service = mock(ArchitectureEditorService.class);
        var resolver = mock(WorkspaceResolver.class);
        var scope = RepositoryContext.workspace("repo", "workspace", "draft", "alice");
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(scope);
        String dsl = """
                meta {
                  language: "taxdsl";
                  version: "2.1";
                  namespace: "example";
                  catalogueRevision: "%s";
                }
                element BP-1016 type Process {
                  title: "Accounting";
                }
                element BP-1017 type Process {
                  title: "Edited title";
                }
                """.formatted(REVISION);
        var document = new ArchitectureEditorService.Document(Context.of(scope, COMMIT), dsl, "HISTORICAL",
                List.of(), List.of(), 0, null, "GIT_CHECKPOINT");
        when(service.read(scope, COMMIT, null)).thenReturn(document);
        var exports = new ArchitectureEditorExportController(new ArchitectureEditorExportAdapter(service, projection),
                resolver, new SvgDiagramRenderer(), new ArchitecturePdfRenderer(), mock(ExtensionCatalog.class));
        var mvc = standaloneSetup(exports).build();
        var germanSvg = mvc.perform(request("svg", Locale.GERMANY)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(germanSvg.getContentAsString()).contains("Rechnungswesen", "Edited title").doesNotContain("Daten beschaffen");
        var pdf = mvc.perform(request("pdf", Locale.GERMANY)).andExpect(status().isOk()).andReturn().getResponse();
        try (var parsed = Loader.loadPDF(pdf.getContentAsByteArray())) {
            assertThat(new PDFTextStripper().getText(parsed)).contains("Rechnungswesen", "Edited title");
        }
        assertThat(mvc.perform(request("svg", Locale.ENGLISH)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString()).contains("Accounting", "Edited title").doesNotContain("Rechnungswesen");
    }

    private static MockHttpServletRequestBuilder request(String format, Locale locale) {
        return get("/api/architecture/editor." + format).param("repositoryId", "repo")
                .param("workspaceScopeKey", "workspace").param("branch", "draft").param("commit", COMMIT).locale(locale);
    }
}
