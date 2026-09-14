package com.taxonomy.versioning.controller;

import com.taxonomy.dto.*;
import com.taxonomy.versioning.service.ContextCompareService;
import com.taxonomy.versioning.service.ContextNavigationService;
import com.taxonomy.versioning.service.SelectiveTransferService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContextNavigationControllerEdgeTest {
    private final ContextNavigationService navigation = mock(ContextNavigationService.class);
    private final ContextCompareService compare = mock(ContextCompareService.class);
    private final SelectiveTransferService transfer = mock(SelectiveTransferService.class);
    private final WorkspaceResolver resolver = mock(WorkspaceResolver.class);
    private final ContextNavigationController controller =
            new ContextNavigationController(navigation, compare, transfer, resolver);
    private final WorkspaceContext workspace = new WorkspaceContext("alice", "workspace", "draft", "repo");
    private final SemanticChange element = new SemanticChange("ADD", "ELEMENT", "element-a", "new", null, "value");
    private final SemanticChange relation = new SemanticChange("REMOVE", "RELATION", "relation-a", "old", "value", null);
    private final SemanticChange other = new SemanticChange("MODIFY", "OTHER", "other-a", "other", "a", "b");
    private final TransferSelection selection = new TransferSelection("from", "to", Set.of("element-a"),
            Set.of("relation-a"), TransferSelection.TransferMode.COPY);

    @Test
    void navigationReadsKeepTheAuthenticatedUsersContextAndHistory() {
        when(resolver.resolveCurrentUsername()).thenReturn("alice");
        ContextRef expected = new ContextRef("context", "review", null, null,
                ContextMode.READ_ONLY, null, null, null, null, null, false);
        List<ContextHistoryEntry> entries = List.of(new ContextHistoryEntry("old", "context", null, null));
        when(navigation.getCurrentContext("alice")).thenReturn(expected);
        when(navigation.returnToOrigin("alice")).thenReturn(expected);
        when(navigation.back("alice")).thenReturn(expected);
        when(navigation.getHistory("alice")).thenReturn(entries);
        assertSame(expected, controller.getCurrentContext().getBody());
        assertSame(expected, controller.returnToOrigin().getBody());
        assertSame(expected, controller.back().getBody());
        assertSame(entries, controller.getHistory().getBody());
    }

    @Test
    void successfulTransferReturnsTheActualCommit() throws Exception {
        when(transfer.applyTransfer(selection)).thenReturn("created-commit");
        var result = controller.applyTransfer(selection);
        assertEquals(200, result.getStatusCode().value());
        assertEquals("created-commit", result.getBody().get("commitId"));
        assertEquals(true, result.getBody().get("success"));
        verify(transfer).applyTransfer(selection);
    }

    static Stream<Arguments> filters() {
        return Stream.of(Arguments.of(null, 3), Arguments.of(Set.of(), 3),
                Arguments.of(Set.of("elements"), 1), Arguments.of(Set.of("relations"), 1),
                Arguments.of(Set.of("elements", "relations"), 2), Arguments.of(Set.of("unknown"), 0));
    }

    @ParameterizedTest
    @MethodSource("filters")
    void filtersActualChangesWithoutLosingSummaryOrRawDiff(Set<String> filter, int count) throws Exception {
        when(resolver.resolveCurrentContext()).thenReturn(workspace);
        ContextComparison input = new ContextComparison(null, null,
                new ContextComparison.DiffSummary(1, 0, 0, 0, 0, 1), List.of(element, relation, other), "raw-diff");
        when(compare.compareBranches(any(), any(), eq(workspace))).thenReturn(input);
        var response = controller.compare("left", null, "right", null, filter);
        assertEquals(200, response.getStatusCode().value());
        var result = response.getBody();
        assertNotNull(result);
        assertEquals(count, result.changes().size());
        assertSame(input.summary(), result.summary());
        assertEquals("raw-diff", result.rawDslDiff());
        if (filter != null && !filter.isEmpty()) {
            assertEquals(input.changes().stream().filter(change ->
                    (filter.contains("elements") && change.category().equals("ELEMENT"))
                    || (filter.contains("relations") && change.category().equals("RELATION"))).toList(), result.changes());
        } else {
            assertSame(input, result);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void eitherPinnedCommitSelectsSnapshotComparison(boolean leftPinned) throws Exception {
        when(resolver.resolveCurrentContext()).thenReturn(workspace);
        controller.compare("left", leftPinned ? "pinned" : null, "right", leftPinned ? null : "pinned", null);
        verify(compare).compareContexts(argThat(ref -> ref.branch().equals("left")
                        && (leftPinned ? "pinned".equals(ref.commitId()) : ref.commitId() == null)),
                argThat(ref -> ref.branch().equals("right")
                        && (leftPinned ? ref.commitId() == null : "pinned".equals(ref.commitId()))), eq(workspace));
        verify(compare, never()).compareBranches(any(), any(), any());
    }

    @Test
    void comparisonFailureReturnsServerError() throws Exception {
        when(compare.compareBranches(any(), any(), any())).thenThrow(new IOException("unavailable"));
        assertEquals(500, controller.compare("left", null, "right", null, null).getStatusCode().value());
    }

    @Test
    void variantFailureIsNotReportedAsSuccess() throws Exception {
        when(navigation.createVariantFromCurrent(any(), eq("variant"), any())).thenThrow(new IOException("disk full"));
        var response = controller.createVariant("variant");
        assertEquals(500, response.getStatusCode().value());
        assertEquals("Failed to create variant: disk full", response.getBody().get("error"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void previewReportsConflictsAndSelectionCountsWithoutApplying(boolean conflict) throws Exception {
        when(resolver.resolveCurrentContext()).thenReturn(workspace);
        List<TransferConflict> conflicts = conflict
                ? List.of(new TransferConflict("element-a", "old", "new", List.of())) : List.of();
        when(transfer.previewTransfer(selection, workspace)).thenReturn(conflicts);
        var response = controller.previewTransfer(selection);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(conflicts, response.getBody().get("conflicts"));
        assertEquals(conflict, response.getBody().get("hasConflicts"));
        assertEquals(1, response.getBody().get("selectedElements"));
        assertEquals(1, response.getBody().get("selectedRelations"));
        verify(transfer, never()).applyTransfer(any());
    }

    @Test
    void transferFailuresReturnErrorsAndLegacyResolutionFallbackIsExplicit() throws Exception {
        when(resolver.resolveCurrentContext()).thenThrow(new IllegalStateException("no request context"));
        when(transfer.previewTransfer(selection, WorkspaceContext.SHARED)).thenThrow(new IOException("preview unavailable"));
        when(transfer.applyTransfer(selection)).thenThrow(new IOException("write unavailable"));
        var preview = controller.previewTransfer(selection);
        assertEquals(500, preview.getStatusCode().value());
        assertEquals("Transfer preview failed: preview unavailable", preview.getBody().get("error"));
        var apply = controller.applyTransfer(selection);
        assertEquals(500, apply.getStatusCode().value());
        assertEquals("Transfer failed: write unavailable", apply.getBody().get("error"));
    }
}
