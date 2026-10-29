package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceCentralReadPinsTest {
    private final WorkspaceManager manager = mock(WorkspaceManager.class);
    private final SystemRepositoryService repositories = mock(SystemRepositoryService.class);
    private final RepositoryMembershipService membership = mock(RepositoryMembershipService.class);
    private final WorkspaceContextResolver resolver = new WorkspaceContextResolver(manager,repositories,mock(UserWorkspaceRepository.class),membership);
    @AfterEach void clear() { RequestContextHolder.resetRequestAttributes(); }
    @Test void explicitCentralSelectionRetainsReadableRepositoryAndHistoricalBranchWithoutProvisioning() {
        var primary = repository("primary","draft"); var source = repository("source-repo","main");
        when(repositories.getPrimaryRepository()).thenReturn(primary);
        when(repositories.getRepository("source-repo")).thenReturn(source);
        when(membership.canRead(source,"alice")).thenReturn(true);
        request("source-repo","retained/source");
        assertThat(resolver.resolveRepositoryContextForUser("alice")).isEqualTo(RepositoryContext.centralRead("source-repo","retained/source","alice"));
        verifyNoInteractions(manager);
    }
    @Test void guessedOrUnreadableCentralRepositoryIsNotFoundEvenWithAnExactBranchPin() {
        when(repositories.getPrimaryRepository()).thenReturn(repository("primary","draft"));
        when(repositories.getRepository("private-repo")).thenReturn(repository("private-repo","main"));
        request("private-repo","main");
        assertThatThrownBy(() -> resolver.resolveRepositoryContextForUser("alice"))
                .isInstanceOfSatisfying(ResponseStatusException.class,error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
        verifyNoInteractions(manager);
    }
    @Test void malformedCentralBranchDoesNotFallBackToAnotherBranch() {
        var source = repository("source-repo","main");
        when(repositories.getPrimaryRepository()).thenReturn(repository("primary","draft"));
        when(repositories.getRepository("source-repo")).thenReturn(source);
        when(membership.canRead(source,"alice")).thenReturn(true);
        request("source-repo","../other");
        assertThatThrownBy(() -> resolver.resolveRepositoryContextForUser("alice")).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(manager);
    }
    private void request(String repository,String branch) {
        var request = new MockHttpServletRequest(); request.addParameter("workspaceId","");
        request.addParameter("repositoryId",repository); request.addParameter("branch",branch);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
    private SystemRepository repository(String id,String branch) {
        var result = new SystemRepository(); result.setRepositoryId(id); result.setDefaultBranch(branch); return result;
    }
}
