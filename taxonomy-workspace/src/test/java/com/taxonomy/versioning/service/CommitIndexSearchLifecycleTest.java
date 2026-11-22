package com.taxonomy.versioning.service;

import com.taxonomy.versioning.repository.ArchitectureCommitIndexRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommitIndexSearchLifecycleTest {

    @Mock
    private ArchitectureCommitIndexRepository indexRepository;

    @Mock
    private CommitIndexSearchRebuilder searchRebuilder;

    @Mock
    private ApplicationArguments arguments;

    @Test
    void workerRuntimeDoesNotAccessTheDisabledGlobalCommitIndex() {
        contextRunner().withPropertyValues("taxonomy.analysis.runtime-role=worker").run(context -> {
            context.getBean(CommitIndexSearchLifecycle.class).run(arguments);
            verifyNoInteractions(indexRepository, searchRebuilder);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "all", "coordinator"})
    void searchableRuntimesKeepTheEmptyProjectionRebuild(String role) {
        when(searchRebuilder.hasCompleteCoverage(0L)).thenReturn(false, true);
        var runner = contextRunner();
        if (!role.isEmpty()) runner = runner.withPropertyValues("taxonomy.analysis.runtime-role=" + role);
        runner.run(context -> {
            context.getBean(CommitIndexSearchLifecycle.class).run(arguments);
            verify(indexRepository, org.mockito.Mockito.times(2)).count();
            verify(searchRebuilder).rebuildAll();
            assertThat(context.getBean(CommitIndexSearchLifecycle.class).isReady()).isTrue();
        });
    }

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner().withUserConfiguration(CommitIndexSearchLifecycle.class)
                .withBean(ArchitectureCommitIndexRepository.class, () -> indexRepository)
                .withBean(CommitIndexSearchRebuilder.class, () -> searchRebuilder);
    }

    @Test
    void rebuildsAndPurgesSearchDocumentsWhenProjectionTableIsEmpty()
            throws Exception {
        when(indexRepository.count()).thenReturn(0L);
        when(searchRebuilder.hasCompleteCoverage(0L)).thenReturn(false, true);
        CommitIndexSearchLifecycle lifecycle = new CommitIndexSearchLifecycle(
                indexRepository, searchRebuilder);

        lifecycle.run(arguments);

        verify(searchRebuilder).rebuildAll();
        assertThat(lifecycle.isReady()).isTrue();
    }

    @Test
    void preservesAnAlreadyVerifiedTenantAwareSearchProjection() throws Exception {
        when(indexRepository.count()).thenReturn(3L);
        when(searchRebuilder.hasCompleteCoverage(3L)).thenReturn(true);
        CommitIndexSearchLifecycle lifecycle = new CommitIndexSearchLifecycle(
                indexRepository, searchRebuilder);

        lifecycle.run(arguments);

        verify(searchRebuilder, org.mockito.Mockito.never()).rebuildAll();
        assertThat(lifecycle.isReady()).isTrue();
    }

    @Test
    void aPopulatedDatabaseCannotHideAnIncompleteSearchProjection() throws Exception {
        when(indexRepository.count()).thenReturn(3L);
        when(searchRebuilder.hasCompleteCoverage(3L)).thenReturn(false, true);
        var lifecycle = new CommitIndexSearchLifecycle(indexRepository, searchRebuilder);

        lifecycle.run(arguments);

        verify(searchRebuilder).rebuildAll();
        assertThat(lifecycle.isReady()).isTrue();
    }

    @Test
    void incompleteRebuildCannotOpenHistorySearch() {
        when(indexRepository.count()).thenReturn(3L);
        var lifecycle = new CommitIndexSearchLifecycle(indexRepository, searchRebuilder);

        assertThatThrownBy(() -> lifecycle.run(arguments)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("coverage is incomplete");
        assertThat(lifecycle.getState()).isEqualTo(CommitIndexSearchLifecycle.State.FAILED);
        assertThat(lifecycle.isReady()).isFalse();
    }

    @Test
    void explicitlyDisabledSearchDoesNotOpenHibernateSearch() {
        contextRunner().withPropertyValues("spring.jpa.properties.hibernate.search.enabled=false").run(context -> {
            var lifecycle = context.getBean(CommitIndexSearchLifecycle.class);
            lifecycle.run(arguments);
            assertThat(lifecycle.getState()).isEqualTo(CommitIndexSearchLifecycle.State.DISABLED);
            assertThat(lifecycle.isReady()).isFalse();
            verifyNoInteractions(indexRepository, searchRebuilder);
        });
    }

    @Test
    void interruptionFailsStartupClosedAndRestoresInterruptFlag()
            throws Exception {
        when(indexRepository.count()).thenReturn(0L);
        doThrow(new InterruptedException("stop"))
                .when(searchRebuilder).rebuildAll();
        CommitIndexSearchLifecycle lifecycle = new CommitIndexSearchLifecycle(
                indexRepository, searchRebuilder);

        try {
            assertThatThrownBy(() -> lifecycle.run(arguments))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("rebuild was interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(lifecycle.isReady()).isFalse();
        } finally {
            Thread.interrupted();
        }
    }
}
