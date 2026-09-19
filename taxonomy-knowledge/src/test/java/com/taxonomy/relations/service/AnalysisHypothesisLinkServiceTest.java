package com.taxonomy.relations.service;

import com.taxonomy.relations.model.RelationHypothesis;
import com.taxonomy.relations.repository.RelationHypothesisRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AnalysisHypothesisLinkServiceTest {
    @Test
    void linksOnlyTheExactRepositoryWorkspaceAndSession() {
        var repository = mock(RelationHypothesisRepository.class);
        var first = mock(RelationHypothesis.class);
        var second = mock(RelationHypothesis.class);
        var selected = List.of(first, second);
        when(repository.findByAnalysisSessionIdInRepositoryWorkspace("repo-a", "workspace-a", "session-a"))
                .thenReturn(selected);
        new AnalysisHypothesisLinkService(repository).linkSnapshot(
                "repo-a", "workspace-a", "session-a", 31L, 41L, "snapshot-a");
        for (var hypothesis : selected) {
            verify(hypothesis).setProjectId(31L);
            verify(hypothesis).setRequirementId(41L);
            verify(hypothesis).setAnalysisSnapshotId("snapshot-a");
        }
        verify(repository).findByAnalysisSessionIdInRepositoryWorkspace("repo-a", "workspace-a", "session-a");
        verify(repository).saveAll(selected);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void emptyProjectionDoesNotCreateHypotheses() {
        var repository = mock(RelationHypothesisRepository.class);
        List<RelationHypothesis> selected = List.of();
        when(repository.findByAnalysisSessionIdInRepositoryWorkspace("repo-a", null, null)).thenReturn(selected);
        new AnalysisHypothesisLinkService(repository).linkSnapshot("repo-a", null, null, 31L, 41L, "snapshot-a");
        verify(repository).saveAll(selected);
        verify(repository).findByAnalysisSessionIdInRepositoryWorkspace("repo-a", null, null);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void readFailureReachesTheTransactionalCallerWithoutSaving() {
        var repository = mock(RelationHypothesisRepository.class);
        var failure = new IllegalStateException("projection unavailable");
        when(repository.findByAnalysisSessionIdInRepositoryWorkspace("r", "w", "s")).thenThrow(failure);
        assertThatThrownBy(() -> new AnalysisHypothesisLinkService(repository)
                .linkSnapshot("r", "w", "s", 1L, 2L, "snapshot")).isSameAs(failure);
        verify(repository).findByAnalysisSessionIdInRepositoryWorkspace("r", "w", "s");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void writeFailureIsNotSwallowed() {
        var repository = mock(RelationHypothesisRepository.class);
        var selected = List.of(mock(RelationHypothesis.class));
        var failure = new IllegalStateException("write unavailable");
        when(repository.findByAnalysisSessionIdInRepositoryWorkspace("r", "w", "s")).thenReturn(selected);
        when(repository.saveAll(selected)).thenThrow(failure);
        assertThatThrownBy(() -> new AnalysisHypothesisLinkService(repository)
                .linkSnapshot("r", "w", "s", 1L, 2L, "snapshot")).isSameAs(failure);
    }

    @Test
    void joinsExistingTransactionInsteadOfCommittingSeparately() throws Exception {
        var method = AnalysisHypothesisLinkService.class.getMethod("linkSnapshot",
                String.class, String.class, String.class, Long.class, Long.class, String.class);
        assertThat(method.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.REQUIRED);
    }
}
