package com.taxonomy.editor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.editor.ArchitectureCommandPort.*;
import com.taxonomy.export.LayeredDiagramLayoutService;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ArchitectureEditorControllerTest {
    private final ArchitectureEditorService service = mock(ArchitectureEditorService.class);
    private final WorkspaceResolver resolver = mock(WorkspaceResolver.class);
    private final ArchitectureEditorController controller = new ArchitectureEditorController(
            service,
            new ArchitectureEditorProjection(new LayeredDiagramLayoutService()),
            resolver);
    private final RepositoryContext scope = RepositoryContext.workspace(
            "repo-a", "workspace-a", "draft", "alice");
    private static final String HEAD = "a1".repeat(20);

    @Test
    void missingAndContradictoryPreconditionsNeverReachTheCommandPort() {
        var command = wire();
        assertThatThrownBy(() -> controller.execute(command, null, null))
                .isInstanceOf(ArchitectureEditorController.PreconditionRequiredException.class);
        assertThatThrownBy(() -> controller.preview(command, "\"" + "a".repeat(40) + "\"", null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(service);
    }

    @Test
    void missingIfMatchUsesTheSpecific428HandlerThroughSpringMvc() throws Exception {
        var mvc = standaloneSetup(controller).build();

        mvc.perform(post("/api/architecture/editor/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsBytes(wire())))
                .andExpect(status().is(428))
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"))
                .andExpect(jsonPath("$.detail").value(
                        "An exact semantic revision If-Match header is required"));

        verifyNoInteractions(service);
    }

    @Test
    void guessedContextIsNotFoundBeforeAnyModelHistoryRead() {
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(scope);

        assertThatThrownBy(() -> controller.read(
                "repo-b", "workspace-a", "draft", HEAD, null))
                .isInstanceOfSatisfying(CommandProblem.class,
                        error -> assertThat(controller.problem(error).getStatusCode().value())
                                .isEqualTo(404));

        verifyNoInteractions(service);
    }

    private ArchitectureEditorController.WireCommand wire() {
        String id = UUID.randomUUID().toString();
        return new ArchitectureEditorController.WireCommand(
                Context.of(scope, HEAD),
                new Metadata(id, id, id, "Test decision"),
                "CREATE_ELEMENT",
                null,
                "System",
                Map.of("title", "Name"),
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
