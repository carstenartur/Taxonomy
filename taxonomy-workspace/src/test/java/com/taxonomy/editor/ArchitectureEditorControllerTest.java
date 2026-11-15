package com.taxonomy.editor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.editor.ArchitectureCommandPort.*;
import com.taxonomy.export.LayeredDiagramLayoutService;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
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
    void legacyPageRedirectPreservesEveryDecodedCoordinateWithoutDoubleEncodingOrChangingOrigin() throws Exception {
        var coordinates = new org.springframework.util.LinkedMultiValueMap<String,String>();
        coordinates.add("workspace", ""); coordinates.add("workspaceScopeKey", "__shared__");
        coordinates.add("repositoryId", "repo + / & Ü"); coordinates.add("branch", "feature/a+b & 東京");
        coordinates.add("projectId", "42"); coordinates.add("requirementId", "7"); coordinates.add("snapshotId", "snapshot +/&");
        coordinates.add("sourceWorkspaceId", "private copy"); coordinates.add("sourceBranch", "source/a+b");
        coordinates.add("element", "arch + &"); coordinates.add("revision", "17"); coordinates.add("commit", HEAD);
        coordinates.add("viewId", "view/%2F+ Ü"); coordinates.add("layout", "reduce-crossings"); coordinates.add("layoutRevision", "12");
        coordinates.add("adoption", "review + preview"); coordinates.add("lang", "de");
        coordinates.add("unknown", "one"); coordinates.add("unknown", "two & three");
        coordinates.add("returnTo", "https://evil.example/path?x=1"); coordinates.add("payload", "\r\nLocation: //evil.example");
        var mvc = standaloneSetup(controller).setViewResolvers(new org.springframework.web.servlet.view.InternalResourceViewResolver()).build();
        String target = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/deployed/architecture/editor")
                        .contextPath("/deployed").params(coordinates))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        var uri = java.net.URI.create(target);
        assertThat(uri.getPath()).isEqualTo("/deployed/"); assertThat(uri.getHost()).isNull();
        assertThat(uri.getFragment()).isEqualTo("architecture"); assertThat(target).doesNotContain("\r", "\n");
        var decoded = new org.springframework.util.LinkedMultiValueMap<String,String>();
        for (String pair : uri.getRawQuery().split("&")) {
            String[] parts = pair.split("=",2);
            decoded.add(java.net.URLDecoder.decode(parts[0], java.nio.charset.StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(parts.length == 2 ? parts[1] : "", java.nio.charset.StandardCharsets.UTF_8));
        }
        coordinates.set("architectureSource", "working"); assertThat(decoded).isEqualTo(coordinates);
        verifyNoInteractions(service, resolver);
    }

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

    @Test
    void organizationMutationsUseTheNormalRevisionCheckedCommandPort() throws Exception {
        var base = wire();
        var properties = Map.of("title", "Operations", "parentId", "agency");
        var upsert = new ArchitectureEditorController.WireCommand(base.context(), base.metadata(), "UPSERT_ORGANIZATION",
                "ops", null, properties, null, null, null, null, null, null);
        var delete = new ArchitectureEditorController.WireCommand(base.context(), base.metadata(), "DELETE_ORGANIZATION",
                "ops", null, Map.of(), null, null, null, null, null, null);
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(scope);
        when(service.preview(eq(scope), any())).thenThrow(new CommandProblem("DISPATCHED", "", "", java.util.List.of()));
        for (var request : java.util.List.of(upsert, delete)) {
            assertThatThrownBy(() -> controller.preview(request, "\"workspace-revision-0\"", null))
                    .isInstanceOfSatisfying(CommandProblem.class, error -> assertThat(error.code()).isEqualTo("DISPATCHED"));
        }
        var captured = org.mockito.ArgumentCaptor.forClass(Command.class);
        verify(service, times(2)).preview(eq(scope), captured.capture());
        assertThat(captured.getAllValues()).extracting(command -> ((SemanticCommand) command.operation()).command())
                .containsExactly(new com.taxonomy.dsl.command.ArchitectureCommand.UpsertOrganization("ops", properties),
                        new com.taxonomy.dsl.command.ArchitectureCommand.DeleteOrganization("ops"));
    }

    @Test void packageWireUsesTypedCommandAndExactRevision() throws Exception {
        var base = wire();
        var packageWire = new ArchitectureEditorController.WireCommand(base.context(), base.metadata(), "CREATE_PACKAGE",
                null, null, Map.of("title", "Package"), null, null, null, null, null, null);
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(scope);
        when(service.preview(eq(scope), any())).thenThrow(new CommandProblem("DISPATCHED", "", "dispatched", java.util.List.of()));
        assertThatThrownBy(() -> controller.preview(packageWire, "\"workspace-revision-0\"", null))
                .isInstanceOfSatisfying(CommandProblem.class, p -> assertThat(p.code()).isEqualTo("DISPATCHED"));
        var argument = org.mockito.ArgumentCaptor.forClass(Command.class);
        verify(service).preview(eq(scope), argument.capture());
        assertThat(((SemanticCommand) argument.getValue().operation()).command())
                .isInstanceOf(com.taxonomy.dsl.command.ArchitectureCommand.CreateArchitecturePackage.class);
    }


    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void administratorAloneCannotEditPrivateWorkspace() throws Exception {
        authenticate("ROLE_ADMIN");
        prepareDocument(Context.of(scope, null));
        assertThat(controller.read("repo-a", "workspace-a", "draft", null, null).getBody().mayEdit()).isFalse();
    }

    @Test
    void readerCannotEditPrivateWorkspace() throws Exception {
        authenticate("ROLE_READER");
        prepareDocument(Context.of(scope, null));
        assertThat(controller.read("repo-a", "workspace-a", "draft", null, null).getBody().mayEdit()).isFalse();
    }

    @Test
    void architectCanEditPrivateWorkspace() throws Exception {
        authenticate("ROLE_ARCHITECT");
        prepareDocument(Context.of(scope, null));
        assertThat(controller.read("repo-a", "workspace-a", "draft", null, null).getBody().mayEdit()).isTrue();
    }

    @Test
    void architectCannotEditReadOnlyDocument() throws Exception {
        authenticate("ROLE_ARCHITECT");
        prepareDocument(Context.version(scope, null));
        assertThat(controller.read("repo-a", "workspace-a", "draft", null, null).getBody().mayEdit()).isFalse();
    }

    private void prepareDocument(Context context) throws Exception {
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(scope);
        when(service.read(scope, null, null)).thenReturn(new ArchitectureEditorService.Document(
                context, "", "READY", java.util.List.of(), java.util.List.of(), 0, null, "WORKSPACE_REVISION"));
    }

    private static void authenticate(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "alice", "unused", AuthorityUtils.createAuthorityList(authorities)));
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
