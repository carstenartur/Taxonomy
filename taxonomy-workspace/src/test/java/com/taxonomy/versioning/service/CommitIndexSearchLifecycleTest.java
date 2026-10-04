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
        var runner = contextRunner();
        if (!role.isEmpty()) runner = runner.withPropertyValues("taxonomy.analysis.runtime-role=" + role);
        runner.run(context -> {
            context.getBean(CommitIndexSearchLifecycle.class).run(arguments);
            verify(indexRepository).count();
            verify(searchRebuilder).rebuildAll();
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
        CommitIndexSearchLifecycle lifecycle = new CommitIndexSearchLifecycle(
                indexRepository, searchRebuilder);

        lifecycle.run(arguments);

        verify(searchRebuilder).rebuildAll();
    }

    @Test
    void preservesAnAlreadyPopulatedTenantAwareSearchProjection() {
        when(indexRepository.count()).thenReturn(3L);
        CommitIndexSearchLifecycle lifecycle = new CommitIndexSearchLifecycle(
                indexRepository, searchRebuilder);

        lifecycle.run(arguments);

        verifyNoInteractions(searchRebuilder);
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
        } finally {
            Thread.interrupted();
        }
    }
}
