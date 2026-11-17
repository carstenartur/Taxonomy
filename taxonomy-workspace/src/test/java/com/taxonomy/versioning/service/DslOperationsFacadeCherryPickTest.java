package com.taxonomy.versioning.service;

import com.taxonomy.versioning.controller.DslApiController;
import com.taxonomy.versioning.controller.DslReadWorkspaceContextResolver;
import com.taxonomy.workspace.service.BranchHeadConflictException;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryStateGuard;
import com.taxonomy.workspace.service.WorkspaceArchitectureVersionPort;
import com.taxonomy.workspace.service.WorkspaceResolver;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DslOperationsFacadeCherryPickTest {

    private final RepositoryContext requested = RepositoryContext.workspace("repository", "workspace", "draft", "alice");
    private final RepositoryContext target = RepositoryContext.workspace("repository", "workspace", "review", "alice");
    private final WorkspaceArchitectureVersionPort versions = spy(new DirectVersions());

    @Test
    void appliesACherryPickOnlyAtTheReviewedHead() throws Exception {
        try (var repository = new DslGitRepository()) {
            String reviewedHead = repository.commitDsl("source", "shared architecture\n", "alice", "Base");
            repository.createBranch("review", "source");
            String source = repository.commitDsl("source", "source architecture\n", "alice", "Source change");
            var facade = facadeFor(repository);

            String applied = facade.cherryPick(source, "review", reviewedHead.toUpperCase(Locale.ROOT));

            verify(versions).versionAtHead(eq(target), eq(reviewedHead), eq("Cherry-pick architecture version"), any());
            assertThat(repository.getHeadCommit("review")).isEqualTo(applied).isNotEqualTo(reviewedHead);
            assertThat(repository.getDslAtHead("review")).isEqualTo("source architecture\n");
            assertThatThrownBy(() -> facade.cherryPick(source, "review", reviewedHead))
                    .isInstanceOfSatisfying(BranchHeadConflictException.class, failure -> {
                        assertThat(failure.getExpectedHeadCommit()).isEqualTo(reviewedHead);
                        assertThat(failure.getActualHeadCommit()).isEqualTo(applied);
                    });
            assertThat(repository.getHeadCommit("review")).isEqualTo(applied);
        }
    }

    @Test
    void cherryPickHttpRequestWithoutAReviewedTargetLeavesTheBranchUnchanged() throws Exception {
        try (var repository = new DslGitRepository()) {
            String reviewedHead = repository.commitDsl("source", "shared architecture\n", "alice", "Base");
            repository.createBranch("review", "source");
            String source = repository.commitDsl("source", "source architecture\n", "alice", "Source change");
            var controller = new DslApiController(facadeFor(repository), mock(WorkspaceResolver.class),
                    mock(DslReadWorkspaceContextResolver.class));
            var mvc = MockMvcBuilders.standaloneSetup(controller).build();

            mvc.perform(post("/api/dsl/cherry-pick")
                            .param("commitId", source)
                            .param("targetBranch", "review"))
                    .andExpect(status().isBadRequest());

            assertThat(repository.getHeadCommit("review")).isEqualTo(reviewedHead);
        }
    }

    @Test
    void appliesManualConflictResolutionToTheReviewedTargetBranch() throws Exception {
        try (var repository = new DslGitRepository()) {
            repository.commitDsl("source", "shared architecture\n", "alice", "Base");
            repository.createBranch("review", "source");
            String source = repository.commitDsl("source", "source architecture\n", "alice", "Source change");
            String reviewedHead = repository.commitDsl("review", "review architecture\n", "alice", "Review change");
            var facade = facadeFor(repository);

            String resolved = facade.resolveCherryPick(source, "review", reviewedHead.toUpperCase(Locale.ROOT),
                    "resolved architecture\n", "alice");

            verify(versions).versionAtHead(eq(target), eq(reviewedHead), eq("Resolve cherry-pick conflict"), any());
            assertThat(repository.getHeadCommit("review")).isEqualTo(resolved).isNotEqualTo(reviewedHead);
            assertThat(repository.getDslAtHead("review")).isEqualTo("resolved architecture\n");
            assertThat(repository.getDslAtHead("source")).isEqualTo("source architecture\n");
        }
    }

    @Test
    void concurrentManualMergeResolutionsCanOnlyCommitOneChoiceAtTheReviewedTarget() throws Exception {
        try (var repository = new DslGitRepository(); var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            String targetHead = repository.commitDsl("review", "reviewed text", "alice", "Reviewed");
            repository.createBranch("source", "review");
            var mvc = MockMvcBuilders.standaloneSetup(new DslApiController(facadeFor(repository), mock(WorkspaceResolver.class),
                    mock(DslReadWorkspaceContextResolver.class))).build();
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Integer> submit = () -> {
                start.await();
                return mvc.perform(post("/api/dsl/merge/resolve").param("fromBranch", "source").param("intoBranch", "review")
                                .param("expectedTargetHead", targetHead).contentType("text/plain").content(Thread.currentThread().getName()))
                        .andReturn().getResponse().getStatus();
            };
            var first = workers.submit(submit);
            var second = workers.submit(submit);
            start.countDown();
            assertThat(java.util.List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 409);
            String winner = repository.getHeadCommit("review");
            assertThat(winner).isNotEqualTo(targetHead);
            mvc.perform(post("/api/dsl/merge/resolve").param("fromBranch", "source").param("intoBranch", "review")
                            .contentType("text/plain").content("missing precondition"))
                    .andExpect(status().is(428));
            assertThat(repository.getHeadCommit("review")).isEqualTo(winner);
            assertThat(repository.getHeadCommit("source")).isEqualTo(targetHead);
        }
    }

    private DslOperationsFacade facadeFor(DslGitRepository repository) {
        var resolver = mock(WorkspaceResolver.class);
        when(resolver.resolveCurrentUsername()).thenReturn("alice");
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(requested);
        var repositories = mock(DslGitRepositoryFactory.class);
        when(repositories.resolveRepository(target)).thenReturn(repository);
        when(repositories.resolveRepository(requested)).thenReturn(repository);
        return new DslOperationsFacade(repositories, mock(CommitIndexService.class),
                mock(ConflictDetectionService.class), mock(RepositoryStateGuard.class),
                mock(RepositoryStateService.class), resolver, versions);
    }

    private static class DirectVersions implements WorkspaceArchitectureVersionPort {
        @Override
        public <T> T version(RepositoryContext context, String rationale, GitAction<T> action) throws IOException {
            return action.run();
        }
    }
}
