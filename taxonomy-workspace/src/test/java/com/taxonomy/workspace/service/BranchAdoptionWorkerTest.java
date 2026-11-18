package com.taxonomy.workspace.service;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.io.IOException;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BranchAdoptionWorkerTest {
    @Test
    void aNativeRefDenialStopsTheScheduledRuleForPermissionReview() throws Exception {
        var rules = mock(BranchAdoptionService.class);
        var due = new BranchAdoptionService.Due("rule", 1);
        when(rules.due()).thenReturn(List.of(due));
        doThrow(new IOException("JGit wrapped its storage callback", new AccessDeniedException("protected ref")))
                .when(rules).advance(due);

        new BranchAdoptionWorker(rules).checkEnabledRules();

        verify(rules).failed(due, true);
    }

    @Test
    void anInteractiveRetryAlsoReportsANativeRefDenial() throws Exception {
        var rules = mock(BranchAdoptionService.class);
        var context = RepositoryContext.centralWrite("architecture", "draft", "alice");
        var admitted = new BranchAdoptionRule.View("architecture", null, "draft", "architecture", "main", true,
                2, "ENABLED", "a".repeat(40), "b".repeat(40), "b".repeat(40), null);
        when(rules.inspect(context)).thenReturn(admitted);
        doThrow(new IOException("storage policy", new AccessDeniedException("protected ref")))
                .when(rules).advance(any(), eq(false), eq(admitted.sourceCommit()));

        new BranchAdoptionWorker(rules).retryReviewed(context, admitted, admitted.sourceCommit());

        verify(rules).failed(any(), eq(true));
    }

    @Test
    void anOrdinaryStorageFailureRequiresARecheckedPrerequisite() throws Exception {
        var rules = mock(BranchAdoptionService.class);
        var due = new BranchAdoptionService.Due("rule", 1);
        when(rules.due()).thenReturn(List.of(due));
        doThrow(new IOException("source moved")).when(rules).advance(due);

        new BranchAdoptionWorker(rules).checkEnabledRules();

        verify(rules).failed(due, false);
    }
}
