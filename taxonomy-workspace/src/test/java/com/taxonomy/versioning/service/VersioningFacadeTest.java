package com.taxonomy.versioning.service;

import com.taxonomy.dto.ContextComparison;
import com.taxonomy.dto.ContextMode;
import com.taxonomy.dto.ContextRef;
import com.taxonomy.dto.RepositoryState;
import com.taxonomy.versioning.model.ContextHistoryRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VersioningFacadeTest {
    private final ContextNavigationService navigation = mock(ContextNavigationService.class);
    private final ContextCompareService compare = mock(ContextCompareService.class);
    private final ContextHistoryService history = mock(ContextHistoryService.class);
    private final RepositoryStateService states = mock(RepositoryStateService.class);
    private final VersioningFacade facade = new VersioningFacade(navigation, compare, history, states);

    @Test
    void variantPreviewUsesTheCreatedBranchNotTheRequestedName() throws Exception {
        ContextRef variant = context("variant/resolved");
        RepositoryState state = mock(RepositoryState.class);
        when(navigation.createVariantFromCurrent("alice", "requested")).thenReturn(variant);
        when(states.getState("alice", "variant/resolved")).thenReturn(state);

        var result = facade.createVariantWithPreview("alice", "requested");

        assertSame(variant, result.variantContext());
        assertSame(state, result.repositoryState());
        verify(states).getState("alice", "variant/resolved");
    }

    @Test
    void failedVariantCreationPropagatesWithoutReadingAnotherState() throws Exception {
        IOException failure = new IOException("cannot create branch");
        when(navigation.createVariantFromCurrent("alice", "variant")).thenThrow(failure);
        assertSame(failure, assertThrows(IOException.class,
                () -> facade.createVariantWithPreview("alice", "variant")));
        verifyNoInteractions(states);
    }

    @Test
    void comparisonBuildsReadOnlyHeadReferencesAndPreservesTheResult() throws Exception {
        ContextComparison expected = new ContextComparison(context("left"), context("right"),
                new ContextComparison.DiffSummary(0, 0, 0, 0, 0, 0), List.of(), "diff");
        when(compare.compareBranches(any(), any())).thenReturn(expected);
        assertSame(expected, facade.compareAndSummarize("left", "right"));
        ArgumentCaptor<ContextRef> left = ArgumentCaptor.forClass(ContextRef.class);
        ArgumentCaptor<ContextRef> right = ArgumentCaptor.forClass(ContextRef.class);
        verify(compare).compareBranches(left.capture(), right.capture());
        assertEquals("left", left.getValue().branch());
        assertEquals("right", right.getValue().branch());
        for (ContextRef ref : List.of(left.getValue(), right.getValue())) {
            assertEquals(ContextMode.READ_ONLY, ref.mode());
            assertNull(ref.commitId());
            assertNotNull(ref.timestamp());
            assertFalse(ref.dirty());
        }
    }

    @Test
    void fullStateKeepsTheUsersActiveBranchAndHistoryTogether() {
        ContextRef current = context("review");
        RepositoryState state = mock(RepositoryState.class);
        List<ContextHistoryRecord> entries = List.of(new ContextHistoryRecord());
        when(navigation.getCurrentContext("alice")).thenReturn(current);
        when(states.getState("alice", "review")).thenReturn(state);
        when(history.getHistory("alice")).thenReturn(entries);
        var result = facade.getFullContextState("alice");
        assertSame(current, result.currentContext());
        assertSame(state, result.repositoryState());
        assertSame(entries, result.history());
    }

    private static ContextRef context(String branch) {
        return new ContextRef("context", branch, null, Instant.EPOCH,
                ContextMode.EDITABLE, null, null, null, null, null, false);
    }
}
