package com.taxonomy.editor;

import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.dsl.storage.ExpectedHeadDslCommitter.BranchHeadConflictException;
import com.taxonomy.editor.ArchitectureCommandPort.*;
import com.taxonomy.export.LayeredDiagramLayoutService;
import com.taxonomy.export.SvgDiagramRenderer;
import com.taxonomy.portfolio.workbench.ArchitecturePdfRenderer;
import com.taxonomy.relations.controller.GitHttpPrecondition;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArchitectureEditorControllerTest {
    private final ArchitectureEditorService service = mock(ArchitectureEditorService.class);
    private final WorkspaceResolver resolver = mock(WorkspaceResolver.class);
    private final ArchitectureEditorController controller = new ArchitectureEditorController(service,
            new ArchitectureEditorProjection(new LayeredDiagramLayoutService()), resolver,
            new SvgDiagramRenderer(), new ArchitecturePdfRenderer());
    private final RepositoryContext scope = RepositoryContext.workspace("repo-a", "workspace-a", "draft", "alice");
    private static final String HEAD = "a1".repeat(20);

    @Test
    void missingAndContradictoryPreconditionsNeverReachTheCommandPort() {
        var command = wire();
        assertThatThrownBy(() -> controller.execute(command, null, null)).isInstanceOf(GitHttpPrecondition.PreconditionRequiredException.class);
        assertThatThrownBy(() -> controller.preview(command, "\"" + "a".repeat(40) + "\"", null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(service);
    }

    @Test
    void guessedContextIsNotFoundBeforeAnyModelHistoryOrExportRead() {
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(scope);
        assertThatThrownBy(() -> controller.read("repo-b", "workspace-a", "draft", HEAD, null)).isInstanceOfSatisfying(CommandProblem.class,
                error -> assertThat(controller.problem(error).getStatusCode().value()).isEqualTo(404));
        assertThatThrownBy(() -> controller.svg("repo-a", "workspace-b", "draft", HEAD, null)).isInstanceOf(CommandProblem.class);
        assertThatThrownBy(() -> controller.pdf("repo-a", "workspace-a", "main", HEAD, null)).isInstanceOf(CommandProblem.class);
        verifyNoInteractions(service);
    }

    @Test
    void immutableJsonSvgAndPdfShareTheSameCommitAndGeometry() throws Exception {
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(scope);
        String dsl = "element arch-instance type System {\n  title: \"System title\";\n}\n";
        var document = new ArchitectureEditorService.Document(Context.of(scope, HEAD), dsl, "HISTORICAL", List.of(), List.of(), 0, null, "GIT_CHECKPOINT");
        when(service.read(scope, HEAD, null)).thenReturn(document);
        String requestedCommit = HEAD.toUpperCase(java.util.Locale.ROOT);
        when(service.read(scope, requestedCommit, null)).thenReturn(document);
        var json = controller.read("repo-a", "workspace-a", "draft", HEAD, null);
        var svg = controller.svg("repo-a", "workspace-a", "draft", HEAD, null);
        var pdf = controller.pdf("repo-a", "workspace-a", "draft", requestedCommit, null);
        assertThat(json.getBody().scene().nodes()).hasSize(1);
        assertThat(svg.getBody()).contains("System title", "arch-instance");
        assertThat(svg.getHeaders().getETag()).isEqualTo(json.getHeaders().getETag());
        assertThat(pdf.getHeaders().getETag()).isEqualTo(json.getHeaders().getETag());
        for (var response : List.of(json, svg, pdf)) {
            assertThat(response.getHeaders().getFirst("X-Taxonomy-Source")).isEqualTo("GIT_CHECKPOINT");
            assertThat(response.getHeaders()).doesNotContainKey("X-Taxonomy-Semantic-Revision");
        }
        assertThat(svg.getHeaders().getFirst("X-Taxonomy-Layout-Source")).isEqualTo("DERIVED_SERVER_LAYOUT");
        try (var parsed = org.apache.pdfbox.Loader.loadPDF(pdf.getBody())) {
            assertThat(parsed.getDocumentInformation().getSubject()).contains(HEAD, "repo-a", "workspace-a", "draft");
            assertThat(parsed.getDocumentInformation().getSubject()).doesNotContain("Revision 0");
            assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(parsed)).contains("System title");
        }
    }

    @Test
    void uncheckpointedJsonSvgAndPdfExposeTheSameSemanticRevision() throws Exception {
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(scope);
        String dsl = "element arch-instance type System {\n  title: \"Uncheckpointed title\";\n}\n";
        var document = new ArchitectureEditorService.Document(Context.of(scope, null, 3), dsl, "READY",
                List.of(), List.of(), 0, null, "WORKSPACE_REVISION");
        when(service.read(scope, null, 3L)).thenReturn(document);
        var json = controller.read("repo-a", "workspace-a", "draft", null, 3L);
        var svg = controller.svg("repo-a", "workspace-a", "draft", null, 3L);
        var pdf = controller.pdf("repo-a", "workspace-a", "draft", null, 3L);
        for (var response : List.of(json, svg, pdf)) {
            assertThat(response.getHeaders().getETag()).isEqualTo("\"workspace-revision-3\"");
            assertThat(response.getHeaders().getFirst("X-Taxonomy-Semantic-Revision")).isEqualTo("3");
            assertThat(response.getHeaders().getFirst("X-Taxonomy-Source")).isEqualTo("WORKSPACE_REVISION");
        }
    }

    private ArchitectureEditorController.WireCommand wire() {
        String id = UUID.randomUUID().toString();
        return new ArchitectureEditorController.WireCommand(Context.of(scope, HEAD), new Metadata(id, id, id, "Test decision"),
                "CREATE_ELEMENT", null, "System", Map.of("title", "Name"), null, null, null, null, null, null);
    }
}
