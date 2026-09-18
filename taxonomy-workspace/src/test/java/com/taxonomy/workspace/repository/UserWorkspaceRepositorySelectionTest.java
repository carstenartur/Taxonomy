package com.taxonomy.workspace.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.taxonomy.workspace.model.UserWorkspace;

class UserWorkspaceRepositorySelectionTest {

    @Test
    void implicitSelectionKeepsNeverAccessedRowsBehindRecentlyAccessedRows() {
        UserWorkspaceRepository repository = mock(UserWorkspaceRepository.class, CALLS_REAL_METHODS);
        UserWorkspace neverAccessed = workspace("z-never", false, false, null);
        UserWorkspace recent = workspace("b-recent", false, false, Instant.parse("2026-09-16T12:00:00Z"));
        UserWorkspace older = workspace("a-older", false, false, Instant.parse("2026-09-15T12:00:00Z"));
        when(repository.findByUsernameAndArchivedFalseOrderByLastAccessedAtDesc("alice"))
                .thenReturn(List.of(neverAccessed, older, recent));

        assertThat(repository.findByUsernameAndSharedFalse("alice"))
                .hasValueSatisfying(actual -> assertThat(actual).isSameAs(recent));
    }

    @Test
    void defaultWorkspaceStillWinsEvenWhenItHasNeverBeenAccessed() {
        UserWorkspaceRepository repository = mock(UserWorkspaceRepository.class, CALLS_REAL_METHODS);
        UserWorkspace defaultWorkspace = workspace("z-default", true, false, null);
        UserWorkspace recent = workspace("a-recent", false, false, Instant.parse("2026-09-16T12:00:00Z"));
        when(repository.findByUsernameAndArchivedFalseOrderByLastAccessedAtDesc("alice"))
                .thenReturn(List.of(recent, defaultWorkspace));

        assertThat(repository.findByUsernameAndSharedFalse("alice"))
                .hasValueSatisfying(actual -> assertThat(actual).isSameAs(defaultWorkspace));
    }

    @Test
    void sharedRowsAreIgnoredAndWorkspaceIdBreaksAccessTimeTies() {
        UserWorkspaceRepository repository = mock(UserWorkspaceRepository.class, CALLS_REAL_METHODS);
        Instant access = Instant.parse("2026-09-16T12:00:00Z");
        UserWorkspace shared = workspace("0-shared", true, true, access.plusSeconds(10));
        UserWorkspace laterId = workspace("b", false, false, access);
        UserWorkspace expected = workspace("a", false, false, access);
        when(repository.findByUsernameAndArchivedFalseOrderByLastAccessedAtDesc("alice"))
                .thenReturn(List.of(shared, laterId, expected));

        assertThat(repository.findByUsernameAndSharedFalse("alice"))
                .hasValueSatisfying(actual -> assertThat(actual).isSameAs(expected));
    }

    private static UserWorkspace workspace(String id, boolean isDefault, boolean shared, Instant lastAccessedAt) {
        UserWorkspace workspace = new UserWorkspace();
        workspace.setWorkspaceId(id);
        workspace.setUsername("alice");
        workspace.setDefault(isDefault);
        workspace.setShared(shared);
        workspace.setArchived(false);
        workspace.setLastAccessedAt(lastAccessedAt);
        return workspace;
    }
}
