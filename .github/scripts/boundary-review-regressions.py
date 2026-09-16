"""Isolated review runner. This helper is not part of any product candidate."""
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()
graph = root / 'taxonomy-build/src/test/java/com/taxonomy/ArchitectureModuleGraphTest.java'
text = graph.read_text()
assert 'compiledBytesRejectWrongInternalIdentity' not in text
methods = r'''

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"copy", "file-alias", "directory-alias"})
    void compiledBytesRejectWrongInternalIdentity(String placement) throws Exception {
        bytecodeRepository();
        Path app = compile(APP, "com.taxonomy.AppConfig", "public class AppConfig {}", List.of());
        Path output = compile(A, A_CLASS, "public class Service {}", List.of());
        Path destination = output.resolve("com/taxonomy/a/Service.class");
        Path alias = temporaryRepository.resolve("fixture-binary-alias/Service.class");
        Files.createDirectories(alias.getParent());
        Files.copy(app.resolve("com/taxonomy/AppConfig.class"), alias);
        if (placement.equals("copy")) {
            Files.copy(alias, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } else if (placement.equals("file-alias")) {
            Files.delete(destination);
            Files.createSymbolicLink(destination, alias);
        } else {
            Files.delete(destination);
            Files.delete(destination.getParent());
            Files.createSymbolicLink(destination.getParent(), alias.getParent());
        }
        assertThatThrownBy(() -> ArchitectureModuleExtractionTest.evaluateRepository(temporaryRepository))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("compiled class")
                .hasMessageContaining("com.taxonomy.a.Service");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void compiledBytesRejectTruncatedOutput(boolean alias) throws Exception {
        bytecodeRepository();
        compile(APP, "com.taxonomy.AppConfig", "public class AppConfig {}", List.of());
        Path output = compile(A, A_CLASS, "public class Service {}", List.of());
        Path destination = output.resolve("com/taxonomy/a/Service.class");
        byte[] truncated = java.util.Arrays.copyOf(Files.readAllBytes(destination), 12);
        if (alias) {
            Path target = temporaryRepository.resolve("fixture-truncated.class");
            Files.write(target, truncated);
            Files.delete(destination);
            Files.createSymbolicLink(destination, target);
        } else {
            Files.write(destination, truncated);
        }
        assertThatThrownBy(() -> ArchitectureModuleExtractionTest.evaluateRepository(temporaryRepository))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("compiled class");
    }

    @Test
    void compiledBytesRejectContradictorySourceFileMetadata() throws Exception {
        bytecodeRepository();
        compile(APP, "com.taxonomy.AppConfig", "public class AppConfig {}", List.of());
        Path output = compile(A, A_CLASS, "public class Service {}", List.of());
        rewriteCompiledSourceAttribute(output.resolve("com/taxonomy/a/Service.class"), "WrongOwner.java");
        assertThatThrownBy(() -> ArchitectureModuleExtractionTest.evaluateRepository(temporaryRepository))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SourceFile")
                .hasMessageContaining("WrongOwner.java").hasMessageContaining("Service.java");
    }

    @Test
    void compiledBytesAllowAnOmittedOptionalSourceFileAttribute() throws Exception {
        bytecodeRepository();
        compile(APP, "com.taxonomy.AppConfig", "public class AppConfig {}", List.of());
        Path output = compile(A, A_CLASS, "public class Service {}", List.of());
        rewriteCompiledSourceAttribute(output.resolve("com/taxonomy/a/Service.class"), null);
        assertThat(ArchitectureModuleExtractionTest.evaluateRepository(temporaryRepository).violations()).isEmpty();
    }

    private static void rewriteCompiledSourceAttribute(Path file, String source) throws Exception {
        var reader = new org.springframework.asm.ClassReader(Files.readAllBytes(file));
        var writer = new org.springframework.asm.ClassWriter(0);
        reader.accept(new org.springframework.asm.ClassVisitor(org.springframework.asm.Opcodes.ASM9, writer) {
            @Override public void visitSource(String ignored, String debug) {
                if (source != null) super.visitSource(source, debug);
            }
        }, 0);
        Files.write(file, writer.toByteArray());
    }
'''
index = text.rfind('\n}')
assert index > 0 and not text[index + 2:].strip()
graph.write_text(text[:index] + methods + text[index:])

test = root / 'taxonomy-workspace/src/test/java/com/taxonomy/workspace/service/WorkspaceCentralReadBoundaryTest.java'
assert not test.exists()
test.write_text(r'''package com.taxonomy.workspace.service;

import com.taxonomy.workspace.controller.WorkspaceController;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.SyncStateRepository;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.versioning.service.SemanticGitMergeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkspaceCentralReadBoundaryTest {
    private final WorkspaceManager manager = mock(WorkspaceManager.class);
    private final WorkspaceResolver resolver = mock(WorkspaceResolver.class);

    @AfterEach
    void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

    private MockMvc mvc() {
        when(resolver.resolveCurrentUsername()).thenReturn("alice");
        return MockMvcBuilders.standaloneSetup(new WorkspaceController(
                manager, resolver, null, null, null, null, null, null)).build();
    }

    private MockHttpServletRequestBuilder centralPin(MockHttpServletRequestBuilder request, String transport) {
        String value = transport.endsWith("whitespace") ? "  " : "";
        return transport.startsWith("header")
                ? request.header(WorkspaceContextResolver.WORKSPACE_HEADER, value)
                : request.param(WorkspaceContextResolver.WORKSPACE_QUERY_PARAMETER, value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"header", "query", "header-whitespace", "query-whitespace"})
    void centralCurrentIsNoContentWithoutSelectingAMetadataRow(String transport) {
        MockMvc mvc = mvc();
        assertDoesNotThrow(() -> mvc.perform(centralPin(get("/api/workspace/current"), transport))
                .andExpect(status().isNoContent()).andExpect(content().string("")));
        verifyNoInteractions(manager);
        verify(resolver, never()).resolveCurrentWorkspaceMetadata();
        verify(resolver, never()).resolveCurrentRepositoryContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {"header", "query", "header-whitespace", "query-whitespace"})
    void centralProvisionIsRejectedBeforeAnyProvisioningOrLookup(String transport) {
        MockMvc mvc = mvc();
        assertDoesNotThrow(() -> mvc.perform(centralPin(post("/api/workspace/provision"), transport))
                .andExpect(status().isForbidden()));
        verifyNoInteractions(manager);
        verify(resolver, never()).resolveCurrentWorkspaceMetadata();
        verify(resolver, never()).resolveCurrentRepositoryContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {"header", "query", "header-whitespace", "query-whitespace"})
    void centralProvisioningStatusRemainsAReadOnlyAbsentResult(String transport) {
        MockMvc mvc = mvc();
        assertDoesNotThrow(() -> mvc.perform(centralPin(get("/api/workspace/provisioning-status"), transport))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NOT_PROVISIONED")));
        verifyNoInteractions(manager);
        verify(resolver, never()).resolveCurrentRepositoryContext();
    }

    @Test
    void namedCurrentStillUsesOnlyTheNamedMetadata() throws Exception {
        var workspace = new UserWorkspace();
        workspace.setWorkspaceId("chosen-workspace");
        when(resolver.resolveCurrentWorkspaceMetadata()).thenReturn(workspace);
        mvc().perform(get("/api/workspace/current")
                .header(WorkspaceContextResolver.WORKSPACE_HEADER, "chosen-workspace"))
                .andExpect(status().isOk());
        verify(manager).getWorkspaceMetadataInfo(workspace);
        verify(manager, never()).getWorkspaceInfo(anyString());
        verify(resolver, never()).resolveCurrentRepositoryContext();
    }

    @Test
    void namedProvisionStillUsesTheAuthorizedNamedWorkspace() throws Exception {
        var workspace = new UserWorkspace();
        workspace.setWorkspaceId("chosen-workspace");
        workspace.setCurrentBranch("work");
        workspace.setProvisioningStatus(WorkspaceProvisioningStatus.READY);
        when(resolver.resolveCurrentWorkspaceMetadata()).thenReturn(workspace);
        when(manager.provisionWorkspaceRepository("alice", "chosen-workspace")).thenReturn(workspace);
        mvc().perform(post("/api/workspace/provision")
                .header(WorkspaceContextResolver.WORKSPACE_HEADER, "chosen-workspace"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.branch").value("work"));
        verify(manager).provisionWorkspaceRepository("alice", "chosen-workspace");
        verify(manager, never()).provisionWorkspaceRepository("alice");
    }

    @ParameterizedTest
    @CsvSource({"MERGE,true", "KEEP_MINE,true", "TAKE_SHARED,true", "PULL,true", "PUBLISH,true",
                "MERGE,false", "KEEP_MINE,false", "TAKE_SHARED,false", "PULL,false", "PUBLISH,false"})
    void centralReadCannotReachAnySynchronizationWrite(String operation, boolean explicitEmptyPin) {
        var request = new MockHttpServletRequest();
        if (explicitEmptyPin) request.addHeader(WorkspaceContextResolver.WORKSPACE_HEADER, "");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var catalog = mock(SystemRepositoryService.class);
        var metadata = new SystemRepository();
        metadata.setRepositoryId("selected-source");
        metadata.setDefaultBranch("release");
        when(catalog.getPrimaryRepository()).thenReturn(metadata);
        var rows = mock(UserWorkspaceRepository.class);
        var syncRows = mock(SyncStateRepository.class);
        var factory = mock(DslGitRepositoryFactory.class);
        var semantic = mock(SemanticGitMergeService.class);
        var portfolio = mock(WorkspacePortfolioGitPort.class);
        var editor = mock(WorkspaceArchitectureVersionPort.class);
        var contexts = new WorkspaceContextResolver(manager, catalog, rows);
        var service = new GitNativeSyncIntegrationService(syncRows, rows, catalog, factory,
                semantic, portfolio, contexts, editor);
        clearInvocations(factory); // The legacy constructor obtains its primary handle once.

        assertThrows(AccessDeniedException.class, () -> {
            switch (operation) {
                case "PULL" -> service.syncFromShared("alice", "work");
                case "PUBLISH" -> service.publishToShared("alice", "work");
                default -> service.resolveDiverged("alice", "work",
                        SyncIntegrationService.DivergedStrategy.valueOf(operation));
            }
        });
        verifyNoInteractions(factory, semantic, portfolio, editor, rows, syncRows);
        if (explicitEmptyPin) verifyNoInteractions(manager);
    }
}
''')
print('Installed counterexamples only; production code is unchanged.')
