package com.taxonomy.composition.architecture;

import com.taxonomy.editor.ArchitectureCommandPort.Context;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ArchitectureFocusControllerTest {
    private final ArchitectureFocusSuggestionService service = mock(ArchitectureFocusSuggestionService.class);
    private final WorkspaceResolver resolver = mock(WorkspaceResolver.class);
    private final ArchitectureFocusController controller = new ArchitectureFocusController(service, resolver);
    private final String body = """
            {"context":{"repositoryId":"repo","workspaceScopeKey":"workspace","branch":"draft",
            "commit":null,"revision":7,"actor":"alice","writeMode":"PRIVATE_WORKSPACE"},"sourceId":"arch-source"}
            """;

    @Test void exactRevisionHeaderIsRequiredBeforeInvestigation() throws Exception {
        var mvc = standaloneSetup(controller).build();
        mvc.perform(post("/api/architecture/editor/focus/suggestions").contentType("application/json").content(body))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(post("/api/architecture/editor/focus/suggestions").contentType("application/json").content(body)
                        .header("If-Match", "\"workspace-revision-6\""))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service, resolver);
    }

    @Test void successfulResponseCarriesCapturedRevisionAndNoStore() throws Exception {
        var scope = RepositoryContext.workspace("repo", "workspace", "draft", "alice");
        var context = Context.of(scope, null, 7);
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(scope);
        when(service.investigate(eq(scope), any())).thenReturn(new ArchitectureFocusSuggestionService.Response(
                context, "arch-source", "ab".repeat(32), "LOCAL_TEXT_UNASSESSED", "NO_CANDIDATES", 0, 0, java.util.List.of()));
        standaloneSetup(controller).build().perform(post("/api/architecture/editor/focus/suggestions")
                        .contentType("application/json").content(body).header("If-Match", "\"workspace-revision-7\""))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"workspace-revision-7\""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.context.revision").value(7));
    }
}
