package com.taxonomy.versioning.controller;

import com.taxonomy.versioning.model.ArchitectureCommitIndex;
import com.taxonomy.versioning.service.CommitIndexService;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.storage.DslWorkspaceReadAdapter;
import com.taxonomy.workspace.service.WorkspaceDslReadPort;
import com.taxonomy.workspace.service.BranchHeadConflictException;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class VersionedHistorySourceTest {
    private final RepositoryContext selected = RepositoryContext.workspace("A", "A1", "review", "alice");
    private final WorkspaceResolver resolver = mock(WorkspaceResolver.class);
    private final CommitIndexService index = mock(CommitIndexService.class);

    @Test
    void lineageUsesSelectedGitAncestryAndDoesNotTrustProjectedBranchLabels() throws Exception {
        try (var factory = new DslGitRepositoryFactory(null)) {
            var git = factory.resolveRepository(selected);
            String base = git.commitDsl("source", "# base", "alice", "base");
            git.createBranch("review", "source");
            String head = git.commitDsl("review", "# head", "alice", "head");
            String unrelated = git.commitDsl("independent", "# other", "alice", "independent");
            when(index.search("term", 50, selected)).thenReturn(List.of(hit("source", base, 1),
                    hit("review", unrelated, 3), hit("review", head, 2)));
            when(resolver.resolveUnambiguousRepositoryForRead()).thenReturn(selected);
            var mvc = MockMvcBuilders.standaloneSetup(new CommitHistoryQueryController(index, resolver,
                    new DslWorkspaceReadAdapter(factory), JsonMapper.builder().build())).build();
            String tag = mvc.perform(get("/api/dsl/history/search-versioned").param("query", "term").param("currentBranch", "review"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].onCurrentLineage").value(true))
                    .andExpect(jsonPath("$[1].onCurrentLineage").value(false))
                    .andExpect(jsonPath("$[1].latestOnCurrentBranch").value(false))
                    .andExpect(jsonPath("$[2].latestOnCurrentBranch").value(true))
                    .andExpect(header().string("X-Taxonomy-Commit", head))
                    .andExpect(header().exists("ETag")).andReturn().getResponse().getHeader("ETag");
            when(index.search("term", 50, selected)).thenReturn(List.of(hit("review", head, 2)));
            mvc.perform(get("/api/dsl/history/search-versioned").param("query", "term").param("currentBranch", "review"))
                    .andExpect(status().isOk()).andExpect(header().string("ETag", org.hamcrest.Matchers.not(tag)));
            verify(resolver, times(2)).resolveUnambiguousRepositoryForRead();
            verifyNoMoreInteractions(resolver);
        }
    }

    @Test
    void emptySearchStillRequiresTheSelectedHeadAndProducesScopeAndContentSensitiveValidators() throws Exception {
        var store = mock(WorkspaceDslReadPort.class);
        var read = mock(WorkspaceDslReadPort.RepositoryRead.class);
        var other = RepositoryContext.centralRead("B", "review", "alice");
        when(resolver.resolveUnambiguousRepositoryForRead()).thenReturn(selected, selected, other, selected);
        when(store.openRead(any())).thenReturn(read);
        String head = "a".repeat(40);
        when(read.currentHead()).thenReturn(head);
        var mvc = MockMvcBuilders.standaloneSetup(new CommitHistoryQueryController(index, resolver, store,
                JsonMapper.builder().build())).build();
        var request = get("/api/dsl/history/search-versioned").param("query", "term");
        String tag = mvc.perform(request).andExpect(status().isOk()).andExpect(content().json("[]"))
                .andExpect(header().string("X-Taxonomy-Repository", "A"))
                .andExpect(header().string("X-Taxonomy-Workspace-Id", "A1"))
                .andReturn().getResponse().getHeader("ETag");
        mvc.perform(request).andExpect(header().string("ETag", tag));
        mvc.perform(request).andExpect(header().string("ETag", org.hamcrest.Matchers.not(tag)));
        mvc.perform(get("/api/dsl/history/search-versioned").param("query", "another")).andExpect(header().string("ETag", org.hamcrest.Matchers.not(tag)));
        verify(read, times(4)).verifyExpectedHead(head);
        verify(resolver, times(4)).resolveUnambiguousRepositoryForRead();
        verifyNoMoreInteractions(resolver);
    }

    @Test
    void missingOrStalePinsAndUnavailableGitNeverReturnRowsOrValidators() throws Exception {
        var store = mock(WorkspaceDslReadPort.class);
        var read = mock(WorkspaceDslReadPort.RepositoryRead.class);
        when(resolver.resolveUnambiguousRepositoryForRead()).thenReturn(selected);
        when(store.openRead(selected)).thenReturn(read);
        String head = "a".repeat(40);
        String next = "b".repeat(40);
        when(read.currentHead()).thenReturn(null, head, head, head).thenThrow(new IOException("private Git path"));
        var mvc = MockMvcBuilders.standaloneSetup(new CommitHistoryQueryController(index, resolver, store,
                JsonMapper.builder().build())).build();
        String path = "/api/dsl/history/search-versioned";
        mvc.perform(get(path).param("query", "term").param("currentBranch", "other"))
                .andExpect(status().isConflict()).andExpect(header().string("X-Taxonomy-Source-State", "STALE"));
        mvc.perform(get(path).param("query", "term").param("commitId", "HEAD"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(store, index);
        mvc.perform(get(path).param("query", "term"))
                .andExpect(status().isNotFound()).andExpect(header().string("X-Taxonomy-Source-State", "MISSING"));
        mvc.perform(get(path).param("query", "term").param("commitId", next))
                .andExpect(status().isConflict()).andExpect(header().string("X-Taxonomy-Commit", head));
        verifyNoInteractions(index);
        when(index.search("term", 50, selected)).thenReturn(List.of(hit("review", next, 1)));
        when(read.relationshipsTo(head, List.of(next))).thenReturn(List.of(WorkspaceDslReadPort.CommitRelationship.UNAVAILABLE),
                List.of(WorkspaceDslReadPort.CommitRelationship.ANCESTOR));
        mvc.perform(get(path).param("query", "term")).andExpect(status().isConflict())
                .andExpect(header().doesNotExist("ETag")).andExpect(content().string(""));
        when(read.verifyExpectedHead(head)).thenThrow(new BranchHeadConflictException("review", head, next, "moved"));
        mvc.perform(get(path).param("query", "term")).andExpect(status().isConflict())
                .andExpect(header().string("X-Taxonomy-Commit", next)).andExpect(content().string(""));
        mvc.perform(get(path).param("query", "term")).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("X-Taxonomy-Source-State", "FAILED")).andExpect(content().string(""));
    }

    private ArchitectureCommitIndex hit(String branch, String commit, int time) {
        var row = new ArchitectureCommitIndex();
        row.setRepositoryId(selected.repositoryId());
        row.setWorkspaceId(selected.workspaceId());
        row.setBranch(branch);
        row.setCommitId(commit);
        row.setCommitTimestamp(Instant.ofEpochSecond(time));
        return row;
    }
}
