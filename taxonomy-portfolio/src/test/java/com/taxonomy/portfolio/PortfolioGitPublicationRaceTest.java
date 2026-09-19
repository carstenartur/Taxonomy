package com.taxonomy.portfolio;

import com.taxonomy.portfolio.service.*;
import com.taxonomy.portfolio.repository.*;
import com.taxonomy.workspace.service.*;
import com.taxonomy.workspace.storage.*;
import com.taxonomy.versioning.service.SemanticGitMergeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import java.io.IOException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PortfolioGitPublicationRaceTest {
    static final String BASE="requirement BASE {}";
    static final String NEXT="requirement BASE {}\nproject P {}";
    static final String OTHER="requirement BASE {}\nrequirement CONCURRENT {}";
    static final WorkspaceContext CONTEXT=new WorkspaceContext("alice","ws-a","draft","repo-a");
    static class RacingRepository extends DslGitRepository {
        boolean armed, fired;
        void race() throws IOException { if(armed && !fired) { fired=true; super.commitDsl("draft",OTHER,"bob","Independent edit"); } }
        @Override public String commitDsl(String b,String d,String a,String m) throws IOException {
            race(); return super.commitDsl(b,d,a,m);
        }
        @Override public String commitDslIfHeadMatches(String b,String h,String d,String a,String m) throws IOException {
            race(); return super.commitDslIfHeadMatches(b,h,d,a,m);
        }
    }
    private PortfolioGitService service(RacingRepository repository) throws Exception {
        var factory=mock(DslGitRepositoryFactory.class);
        when(factory.resolveRepository(CONTEXT)).thenReturn(repository);
        var beans=new DefaultListableBeanFactory();
        beans.registerSingleton("repositories",factory);
        beans.registerSingleton("merges",mock(SemanticGitMergeService.class));
        beans.registerSingleton("versions",new WorkspaceArchitectureVersionPort() {
            public <T> T version(RepositoryContext context,String rationale,GitAction<T> action) throws IOException { return action.run(); }
        });
        var adapter=(WorkspacePortfolioDocumentAdapter)beans.createBean(WorkspacePortfolioDocumentAdapter.class,
                AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR,false);
        return spy(new PortfolioGitService(mock(ProjectPortfolioService.class),mock(ArchitectureProjectRepository.class),
                mock(ProjectRequirementRepository.class),mock(ProjectRequirementVersionRepository.class),
                mock(RequirementElementMappingRepository.class),adapter));
    }
    @Test void projectionCannotOverwriteAnInterveningEditorCommit() throws Exception {
        try(var repository=new RacingRepository()) {
            repository.commitDsl("draft",BASE,"alice","Base");
            var service=service(repository); doReturn(NEXT).when(service).contributeTo(any(),eq("alice"),eq(CONTEXT));
            repository.armed=true;
            assertThatThrownBy(()->service.commit("draft","Save","alice",CONTEXT)).isInstanceOf(PortfolioException.class);
            assertThat(repository.fired).isTrue();
            assertThat(repository.getDslAtHead("draft")).isEqualTo(OTHER);
        }
    }
    @Test void noOpCannotClaimAConcurrentCommitAsItsOwnResult() throws Exception {
        try(var repository=new RacingRepository()) {
            repository.commitDsl("draft",BASE,"alice","Base");
            var service=service(repository);
            doAnswer(call->{ repository.commitDsl("draft",OTHER,"bob","Intervening edit"); return BASE; })
                    .when(service).contributeTo(any(),eq("alice"),eq(CONTEXT));
            assertThatThrownBy(()->service.commit("draft","Save","alice",CONTEXT)).isInstanceOf(PortfolioException.class);
            assertThat(repository.getDslAtHead("draft")).isEqualTo(OTHER);
        }
    }
    @Test void unchangedProjectionDoesNotCreateACommit() throws Exception {
        try(var repository=new RacingRepository()) {
            String before=repository.commitDsl("draft",BASE,"alice","Base");
            var service=service(repository); doReturn(BASE).when(service).contributeTo(any(),eq("alice"),eq(CONTEXT));
            var result=service.commit("draft","Save","alice",CONTEXT);
            assertThat(result.changed()).isFalse(); assertThat(result.commitId()).isEqualTo(before);
            assertThat(repository.getHeadCommit("draft")).isEqualTo(before);
        }
    }
}
