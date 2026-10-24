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
    void optionalLayoutReducesCrossingsAndUsesTheSameExactRevisionForSvgAndPdf() throws Exception {
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(scope);
        String dsl = """
                element arch-a type Capability {
                  title: "First capability";
                }
                element arch-b type Capability {
                  title: "Second capability";
                }
                element arch-c type System {
                  title: "First system";
                }
                element arch-d type System {
                  title: "Second system";
                }
                relation arch-a REALIZED_BY arch-d {
                  status: accepted;
                }
                relation arch-b REALIZED_BY arch-c {
                  status: accepted;
                }
                """;
        var document = new ArchitectureEditorService.Document(
                Context.of(scope, null, 3), dsl, "READY", List.of(), List.of(),
                0, null, "WORKSPACE_REVISION");
        when(service.read(scope, null, 3L)).thenReturn(document);
        var mvc = standaloneSetup(editor, exports).build();
        String query = "?repositoryId=repo-a&workspaceScopeKey=workspace-a&branch=draft&revision=3";
        var response = mvc.perform(get("/api/architecture/editor" + query + "&layout=reduce-crossings"))
                .andExpect(status().isOk()).andReturn().getResponse();
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsString());
        var edges = json.path("scene").path("edges");
        double sourceDifference = edges.get(0).path("sourceY").asDouble() - edges.get(1).path("sourceY").asDouble();
        double targetDifference = edges.get(0).path("targetY").asDouble() - edges.get(1).path("targetY").asDouble();
        assertThat(sourceDifference * targetDifference).as("the two edges must no longer cross").isPositive();
        assertThat(json.path("document").path("dsl").asText()).isEqualTo(dsl);
        assertThat(json.path("schema").path("layoutMode").asText()).isEqualTo("DERIVED_SERVER_LAYOUT_REDUCE_CROSSINGS");
        var svg = mvc.perform(get("/api/architecture/editor.svg" + query + "&layout=reduce-crossings"))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(svg.getHeader("X-Taxonomy-Layout-Source")).isEqualTo("DERIVED_SERVER_LAYOUT_REDUCE_CROSSINGS");
        assertThat(svg.getHeader("ETag")).isEqualTo("\"workspace-revision-3\"");
        for (var edge : edges) {
            assertThat(svg.getContentAsString()).contains(String.format(java.util.Locale.ROOT, "<line x1=\"%.2f\" y1=\"%.2f\" x2=\"%.2f\" y2=\"%.2f\"", edge.path("sourceX").asDouble(), edge.path("sourceY").asDouble(), edge.path("targetX").asDouble(), edge.path("targetY").asDouble()));
        }
        var pdf = mvc.perform(get("/api/architecture/editor.pdf" + query + "&layout=reduce-crossings"))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(pdf.getHeader("X-Taxonomy-Layout-Source")).isEqualTo(svg.getHeader("X-Taxonomy-Layout-Source"));
        assertThat(pdf.getHeader("ETag")).isEqualTo(svg.getHeader("ETag"));
        try (var parsed = org.apache.pdfbox.Loader.loadPDF(pdf.getContentAsByteArray())) {
            assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(parsed)).contains("First capability", "Second system");
        }
    }

    @Test
    void optimizedUrlPinsItsInitialRevisionForRelationRefreshAndExactRevisionExports() throws Exception {
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(scope);
        String elements = """
                element arch-a type Capability {
                  title: "First capability";
                }
                element arch-b type Capability {
                  title: "Second capability";
                }
                element arch-c type System {
                  title: "First system";
                }
                element arch-d type System {
                  title: "Second system";
                }
                """;
        String before = elements + """
                relation arch-a REALIZED_BY arch-d {
                  status: accepted;
                }
                relation arch-b REALIZED_BY arch-c {
                  status: accepted;
                }
                """;
        String after = before + """
                relation arch-a REALIZED_BY arch-c {
                  status: accepted;
                }
                relation arch-b REALIZED_BY arch-d {
                  status: accepted;
                }
                """;
        for (long revision : List.of(3L, 4L)) when(service.read(scope, null, revision)).thenReturn(
                new ArchitectureEditorService.Document(Context.of(scope, null, revision), revision == 3 ? before : after,
                        "READY", List.of(), List.of(), 0, null, "WORKSPACE_REVISION"));
        var mvc = standaloneSetup(editor, exports).build();
        String scopeQuery = "?repositoryId=repo-a&workspaceScopeKey=workspace-a&branch=draft&layout=reduce-crossings";
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var first = mapper.readTree(mvc.perform(get("/api/architecture/editor" + scopeQuery + "&revision=3"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(first.path("schema").path("layoutRevision").asLong(-1))
                .as("opening an optimized workspace URL captures its initial ordering revision").isEqualTo(3L);
        String query = scopeQuery + "&layoutRevision=" + first.path("schema").path("layoutRevision").asLong() + "&revision=";
        var next = mapper.readTree(mvc.perform(get("/api/architecture/editor" + query + "4"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(next.path("scene").path("nodes")).isEqualTo(first.path("scene").path("nodes"));
        assertThat(next.path("scene").path("edges").size()).isEqualTo(4);
        assertThat(next.path("schema").path("layoutRevision").asLong()).isEqualTo(3L);
        var svg = mvc.perform(get("/api/architecture/editor.svg" + query + "4"))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(svg.getHeader("ETag")).isEqualTo("\"workspace-revision-4\"");
        for (var edge : next.path("scene").path("edges")) assertThat(svg.getContentAsString()).contains(
                String.format(java.util.Locale.ROOT, "<line x1=\"%.2f\" y1=\"%.2f\" x2=\"%.2f\" y2=\"%.2f\"",
                        edge.path("sourceX").asDouble(), edge.path("sourceY").asDouble(),
                        edge.path("targetX").asDouble(), edge.path("targetY").asDouble()));
        mvc.perform(get("/api/architecture/editor.pdf" + query + "4"))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("ETag", "\"workspace-revision-4\""));
        for (String invalid : List.of("-1", "5")) mvc.perform(get("/api/architecture/editor" + query.replace("layoutRevision=3", "layoutRevision=" + invalid) + "4"))
                .andExpect(status().isBadRequest());
        when(service.read(scope, HEAD, null)).thenReturn(new ArchitectureEditorService.Document(
                Context.of(scope, HEAD), before, "HISTORICAL", List.of(), List.of(), 0, null, "GIT_CHECKPOINT"));
        mvc.perform(get("/api/architecture/editor" + scopeQuery + "&commit=" + HEAD))
                .andExpect(status().isOk()).andExpect(jsonPath("$.schema.layoutRevision").isEmpty());
        mvc.perform(get("/api/architecture/editor" + scopeQuery.replace("reduce-crossings", "stable") + "&revision=3"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.schema.layoutRevision").isEmpty());
        mvc.perform(get("/api/architecture/editor.svg").param("repositoryId", "repo-a").param("workspaceScopeKey", "workspace-a")
                        .param("branch", "draft").param("commit", HEAD).param("layout", "reduce-crossings").param("layoutRevision", "3"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownLayoutIsRejectedInsteadOfSilentlyUsingStableCoordinates() throws Exception {
        var mvc = standaloneSetup(editor, exports).build();
        for (String path : List.of("/api/architecture/editor", "/api/architecture/editor.svg", "/api/architecture/editor.pdf")) {
            mvc.perform(get(path).param("layout", "surprise").param("repositoryId", "repo-a")
                            .param("workspaceScopeKey", "workspace-a").param("branch", "draft").param("revision", "3"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
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
