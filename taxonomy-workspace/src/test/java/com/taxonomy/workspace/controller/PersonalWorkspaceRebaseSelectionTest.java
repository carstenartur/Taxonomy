package com.taxonomy.workspace.controller;

import com.taxonomy.versioning.service.ContextNavigationService;
import com.taxonomy.workspace.service.PersonalWorkspaceRebase;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.stream.Stream;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PersonalWorkspaceRebaseSelectionTest {

    private static final String REBASE = "/api/workspace/upstream/rebase";
    private static final String APPLY_BODY = """
            {"expectedRefTag":"reviewed","expectedLocalCommit":"local",
             "expectedTrackedCommit":"upstream","orderedCommits":["local"]}
            """;
    private static final String RESUME_BODY = """
            {"candidate":"replayed","expectedHead":"current"}
            """;

    static Stream<RepositoryContext> changedSelections() {
        return Stream.of(
                RepositoryContext.centralRead("source", "draft", "alice"),
                RepositoryContext.workspace("other-source", "w1", "draft", "alice"),
                RepositoryContext.workspace("source", "other-copy", "draft", "alice"),
                RepositoryContext.workspace("source", "w1", "other-branch", "alice"));
    }

    @ParameterizedTest
    @MethodSource("changedSelections")
    void changingTheReviewedSelectionBlocksBothApplyAndRecovery(RepositoryContext selected) throws Exception {
        var resolver = mock(WorkspaceResolver.class);
        when(resolver.resolveUnambiguousRepositoryForRead()).thenReturn(selected);
        var navigation = mock(ContextNavigationService.class);
        var rebases = mock(PersonalWorkspaceRebase.class);
        var mvc = MockMvcBuilders.standaloneSetup(
                new PersonalWorkspaceRebaseController(resolver, navigation, rebases)).build();

        mvc.perform(reviewedPost(REBASE, APPLY_BODY)).andExpect(status().isConflict());
        mvc.perform(reviewedPost(REBASE + "/resume", RESUME_BODY)).andExpect(status().isConflict());
        verifyNoInteractions(navigation, rebases);
    }

    private static MockHttpServletRequestBuilder reviewedPost(
            String path, String body) {
        return post(path).param("repositoryId", "source").param("workspaceId", "w1")
                .param("branch", "draft").param("expectedContextId", "reviewed-browser-context")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
