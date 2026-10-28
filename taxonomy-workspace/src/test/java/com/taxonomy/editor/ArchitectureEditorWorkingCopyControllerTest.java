package com.taxonomy.editor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.RepositoryWorkspaceService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ArchitectureEditorWorkingCopyControllerTest {
    private final WorkspaceResolver resolver = mock(WorkspaceResolver.class);
    private final RepositoryMembershipService membership = mock(RepositoryMembershipService.class);
    private final RepositoryWorkspaceService workspaces = mock(RepositoryWorkspaceService.class);
    private final SystemRepositoryService repositories = mock(SystemRepositoryService.class);
    private final SystemRepository repository = new SystemRepository();
    private final ArchitectureEditorWorkingCopyController controller =
            new ArchitectureEditorWorkingCopyController(resolver, membership, workspaces, repositories);
    private final RepositoryContext central = RepositoryContext.centralRead("repo-a", "reviewed/draft", "alice");

    @Test
    void editorCreatesAnExactWorkingCopyWithoutEnablingTheRepositoryCatalogueApi() throws Exception {
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(central);
        when(repositories.getRepository("repo-a")).thenReturn(repository);
        when(membership.canContribute(repository, "alice")).thenReturn(true);
        var workspace = new UserWorkspace();
        workspace.setWorkspaceId("copy-a");
        workspace.setSourceRepositoryId("repo-a");
        workspace.setSourceBranch("reviewed/draft");
        workspace.setCurrentBranch("draft");
        workspace.setProvisioningStatus(WorkspaceProvisioningStatus.READY);
        when(workspaces.createWorkingCopy("alice", "repo-a", "reviewed/draft", "My architecture", ""))
                .thenReturn(workspace);

        standaloneSetup(controller).build().perform(post("/api/architecture/editor/workspaces")
                .contentType(MediaType.APPLICATION_JSON).content(body("repo-a", "reviewed/draft")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workspaceId").value("copy-a"))
                .andExpect(jsonPath("$.sourceRepositoryId").value("repo-a"))
                .andExpect(jsonPath("$.sourceBranch").value("reviewed/draft"))
                .andExpect(jsonPath("$.currentBranch").value("draft"));
        verify(workspaces).createWorkingCopy("alice", "repo-a", "reviewed/draft", "My architecture", "");
    }

    @Test
    void aDifferentRepositoryOrBranchCannotBeSubstituted() throws Exception {
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(central);
        var mvc = standaloneSetup(controller).build();
        for (var source : new String[][] {{"repo-b", "reviewed/draft"}, {"repo-a", "other"}}) {
            mvc.perform(post("/api/architecture/editor/workspaces").contentType(MediaType.APPLICATION_JSON)
                    .content(body(source[0], source[1]))).andExpect(status().isNotFound());
        }
        verifyNoInteractions(membership, workspaces, repositories);
    }

    @Test
    void aPrivateWorkspaceCannotBeSilentlyReplacedWithItsCentralSource() throws Exception {
        when(resolver.resolveCurrentRepositoryContext())
                .thenReturn(RepositoryContext.workspace("repo-a", "private-a", "reviewed/draft", "alice"));
        standaloneSetup(controller).build().perform(post("/api/architecture/editor/workspaces")
                .contentType(MediaType.APPLICATION_JSON).content(body("repo-a", "reviewed/draft")))
                .andExpect(status().isConflict());
        verifyNoInteractions(membership, workspaces, repositories);
    }

    @Test
    void readingTheSourceDoesNotGrantPermissionToCreateAWorkingCopy() throws Exception {
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(central);
        when(repositories.getRepository("repo-a")).thenReturn(repository);
        when(membership.canContribute(repository, "alice")).thenReturn(false);
        standaloneSetup(controller).build().perform(post("/api/architecture/editor/workspaces")
                .contentType(MediaType.APPLICATION_JSON).content(body("repo-a", "reviewed/draft")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(workspaces);
    }

    private byte[] body(String repository, String branch) throws Exception {
        return new ObjectMapper().writeValueAsBytes(Map.of(
                "repositoryId", repository, "branch", branch, "displayName", "My architecture"));
    }
}
