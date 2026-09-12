package com.taxonomy.composition.architecture;

import com.taxonomy.editor.ArchitectureCommandPort.Context;
import com.taxonomy.editor.ArchitectureEditorController;
import com.taxonomy.editor.ArchitectureEditorExportAdapter;
import com.taxonomy.editor.ArchitectureEditorProjection;
import com.taxonomy.editor.ArchitectureEditorService;
import com.taxonomy.export.ArchitecturePdfRenderer;
import com.taxonomy.export.LayeredDiagramLayoutService;
import com.taxonomy.export.SvgDiagramRenderer;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ArchitectureEditorExportControllerTest {

    private static final String HEAD = "a1".repeat(20);

    private final ArchitectureEditorService service = mock(ArchitectureEditorService.class);
    private final WorkspaceResolver resolver = mock(WorkspaceResolver.class);
    private final ArchitectureEditorProjection projection =
            new ArchitectureEditorProjection(new LayeredDiagramLayoutService());
    private final ArchitectureEditorController editor =
            new ArchitectureEditorController(service, projection, resolver);
    private final ArchitectureEditorExportController exports =
            new ArchitectureEditorExportController(
                    new ArchitectureEditorExportAdapter(service, projection),
                    resolver,
                    new SvgDiagramRenderer(),
                    new ArchitecturePdfRenderer());
    private final RepositoryContext scope = RepositoryContext.workspace(
            "repo-a", "workspace-a", "draft", "alice");

    @Test
    void exportRequiresAnExactRevisionOrVersionThroughSpringMvc() throws Exception {
        var mvc = standaloneSetup(exports).build();

        mvc.perform(get("/api/architecture/editor.svg")
                        .param("repositoryId", "repo-a")
                        .param("workspaceScopeKey", "workspace-a")
                        .param("branch", "draft"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_COMMAND"))
                .andExpect(jsonPath("$.detail").value(
                        "An exact revision or version is required for export"));

        verifyNoInteractions(service, resolver);
    }

    @Test
    void guessedContextIsNotFoundBeforeAnyModelHistoryOrExportRead() {
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(scope);

        assertThatThrownBy(() -> exports.svg(
                "repo-a", "workspace-b", "draft", HEAD, null))
                .isInstanceOf(com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem.class);
        assertThatThrownBy(() -> exports.pdf(
                "repo-a", "workspace-a", "main", HEAD, null))
                .isInstanceOf(com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem.class);

        verifyNoInteractions(service);
    }

    @Test
    void immutableJsonSvgAndPdfShareTheSameCommitAndGeometry() throws Exception {
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(scope);
        String dsl = "element arch-instance type System {\n  title: \"System title\";\n}\n";
        var document = new ArchitectureEditorService.Document(
                Context.of(scope, HEAD), dsl, "HISTORICAL", List.of(), List.of(),
                0, null, "GIT_CHECKPOINT");
        when(service.read(scope, HEAD, null)).thenReturn(document);

        String requestedCommit = HEAD.toUpperCase(java.util.Locale.ROOT);
        var uppercaseDocument = new ArchitectureEditorService.Document(
                Context.of(scope, requestedCommit), dsl, "HISTORICAL", List.of(), List.of(),
                0, null, "GIT_CHECKPOINT");
        when(service.read(scope, requestedCommit, null)).thenReturn(uppercaseDocument);

        var json = editor.read("repo-a", "workspace-a", "draft", HEAD, null);
        var svg = exports.svg("repo-a", "workspace-a", "draft", HEAD, null);
        var pdf = exports.pdf("repo-a", "workspace-a", "draft", requestedCommit, null);

        assertThat(json.getBody().scene().nodes()).hasSize(1);
        assertThat(svg.getBody()).contains("System title", "arch-instance");
        assertThat(json.getHeaders().getETag()).isEqualTo("\"" + HEAD + "\"");
        assertThat(svg.getHeaders().getETag()).isEqualTo(json.getHeaders().getETag());
        assertThat(pdf.getHeaders().getETag()).isEqualTo(json.getHeaders().getETag());
        for (var responseItem : List.of(json, svg, pdf)) {
            assertThat(responseItem.getHeaders().getFirst("X-Taxonomy-Source"))
                    .isEqualTo("GIT_CHECKPOINT");
            assertThat(responseItem.getHeaders().getFirst("X-Taxonomy-Semantic-Revision"))
                    .isNull();
        }
        assertThat(svg.getHeaders().getFirst("X-Taxonomy-Layout-Source"))
                .isEqualTo("DERIVED_SERVER_LAYOUT");
        assertThat(pdf.getHeaders().getFirst("X-Taxonomy-Layout-Source"))
                .isEqualTo("DERIVED_SERVER_LAYOUT");
        try (var parsed = org.apache.pdfbox.Loader.loadPDF(pdf.getBody())) {
            assertThat(parsed.getDocumentInformation().getSubject())
                    .contains(requestedCommit, "repo-a", "workspace-a", "draft")
                    .doesNotContain("Revision 0");
            assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(parsed))
                    .contains("System title");
        }
    }

    @Test
    void uncheckpointedJsonSvgAndPdfExposeTheSameSemanticRevision() throws Exception {
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(scope);
        String dsl = "element arch-instance type System {\n  title: \"Uncheckpointed title\";\n}\n";
        var document = new ArchitectureEditorService.Document(
                Context.of(scope, null, 3), dsl, "READY", List.of(), List.of(),
                0, null, "WORKSPACE_REVISION");
        when(service.read(scope, null, 3L)).thenReturn(document);

        var json = editor.read("repo-a", "workspace-a", "draft", null, 3L);
        var svg = exports.svg("repo-a", "workspace-a", "draft", null, 3L);
        var pdf = exports.pdf("repo-a", "workspace-a", "draft", null, 3L);

        for (var responseItem : List.of(json, svg, pdf)) {
            assertThat(responseItem.getHeaders().getETag())
                    .isEqualTo("\"workspace-revision-3\"");
            assertThat(responseItem.getHeaders().getFirst("X-Taxonomy-Semantic-Revision"))
                    .isEqualTo("3");
            assertThat(responseItem.getHeaders().getFirst("X-Taxonomy-Source"))
                    .isEqualTo("WORKSPACE_REVISION");
        }
    }
}
