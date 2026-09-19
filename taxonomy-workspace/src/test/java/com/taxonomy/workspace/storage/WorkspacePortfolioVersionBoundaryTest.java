package com.taxonomy.workspace.storage;

import com.taxonomy.workspace.service.*;
import com.taxonomy.versioning.service.SemanticGitMergeService;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkspacePortfolioVersionBoundaryTest {
    @ParameterizedTest @ValueSource(booleans={false,true})
    void commitRunsInsideTheSelectedBranchVersionBoundary(boolean shared) throws Exception {
        check(shared, false);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void mergeRunsInsideTheTargetBranchVersionBoundary(boolean shared) throws Exception {
        check(shared, true);
    }
    private void check(boolean shared, boolean merge) throws Exception {
        var context=new WorkspaceContext("alice",shared ? null : "ws-a","other","repo-a");
        var repository=mock(DslGitRepository.class);
        var repositories=mock(DslGitRepositoryFactory.class);
        var merges=mock(SemanticGitMergeService.class);
        when(repositories.resolveRepository(context)).thenReturn(repository);
        when(repository.getGitRepository()).thenReturn(mock(Repository.class));
        var events=new ArrayList<String>();
        var contexts=new ArrayList<RepositoryContext>();
        WorkspaceArchitectureVersionPort versions=new WorkspaceArchitectureVersionPort() {
            public <T> T version(RepositoryContext selected,String rationale,GitAction<T> action) throws IOException {
                contexts.add(selected); events.add("checkpoint");
                assertThat(Thread.holdsLock(repository.getGitRepository())).isFalse();
                T result=action.run(); events.add("import"); return result;
            }
        };
        when(repository.commitDsl(anyString(),anyString(),anyString(),anyString())).thenAnswer(call->{
            events.add("write"); assertThat(Thread.holdsLock(repository.getGitRepository())).isTrue(); return "new-head";
        });
        when(merges.mergeBranches(eq(repository),anyString(),anyString(),anyString(),anyString())).thenAnswer(call->{
            events.add("write"); assertThat(Thread.holdsLock(repository.getGitRepository())).isTrue();
            return new SemanticGitMergeService.MergeOutcome(true,"new-head",false,List.of(),null);
        });
        var beans=new DefaultListableBeanFactory();
        beans.registerSingleton("repositories",repositories); beans.registerSingleton("merges",merges);
        beans.registerSingleton("versions",versions);
        var adapter=(WorkspacePortfolioDocumentAdapter)beans.createBean(WorkspacePortfolioDocumentAdapter.class,
                AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR,false);
        var handle=adapter.resolveRepository(context);
        if(merge) assertThat(handle.mergeBranches("source","target","alice","Save").commitId()).isEqualTo("new-head");
        else assertThat(handle.commitDsl("target","dsl","alice","Save")).isEqualTo("new-head");
        assertThat(events).containsExactly("checkpoint","write","import");
        assertThat(contexts).containsExactly(shared ? RepositoryContext.centralWrite("repo-a","target","alice")
                : RepositoryContext.workspace("repo-a","ws-a","target","alice"));
        verify(repositories,times(1)).resolveRepository(context);
        assertThat(Thread.holdsLock(repository.getGitRepository())).isFalse();
    }
}
