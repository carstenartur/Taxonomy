package com.taxonomy.portfolio.service;

import com.taxonomy.portfolio.dto.RequirementIntakeDtos.*;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RequirementIntakeServiceTest {
    private final RequirementIntakeCandidates candidates = mock(RequirementIntakeCandidates.class);
    private final RequirementIntakeMatcher matcher = mock(RequirementIntakeMatcher.class);
    private final RequirementIntakeService service = new RequirementIntakeService(candidates, matcher);
    private final WorkspaceContext context = new WorkspaceContext("alice", "work", "draft", "repo");
    private final Scope scope = new Scope("repo", "work", "work", "draft");

    @Test void preservesExactRawInputDigestAndOnlyExactFallbackMatches() {
        var row = RequirementIntakeMatcherTest.candidate();
        frozen(List.of(row), false, true);
        when(matcher.review(eq(row.text()), anyList(), any())).thenReturn(new RequirementIntakeMatcher.Result(Status.UNAVAILABLE, "PROVIDER_NOT_CONFIGURED", null, List.of(row)));
        var result = service.review(new Request(2L, null, row.text(), null), "alice", context);
        assertThat(result.status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(result.manualReviewRequired()).isTrue();
        assertThat(result.candidates().getFirst().classification()).isEqualTo(Classification.DUPLICATE);
        assertThat(RequirementIntakeService.inputHash(" a ")).isNotEqualTo(RequirementIntakeService.inputHash("a"));
        assertThat(RequirementIntakeService.inputHash("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test void partialCandidateCoverageCannotBeReportedAsCompleteNoMatch() {
        frozen(List.of(), true, true);
        when(matcher.review(anyString(), anyList(), any())).thenReturn(new RequirementIntakeMatcher.Result(Status.COMPLETE, null, "GEMINI", List.of()));
        assertThat(service.review(new Request(null, null, "text", null), "alice", context).status()).isEqualTo(Status.PARTIAL);
    }

    @Test void demoBindingNeverFallsBackToConfiguredProvider() {
        frozen(List.of(), false, false);
        var result = service.review(new Request(2L, null, "text", null), "alice", context);
        assertThat(result.code()).isEqualTo("PROJECT_PROVIDER_BOUND");
        assertThat(result.status()).isEqualTo(Status.UNAVAILABLE);
        verifyNoInteractions(matcher);
    }

    @Test void authorizationIsRecheckedAfterModelWorkBeforeDisclosingCandidateEvidence() {
        frozen(List.of(), false, true);
        when(matcher.review(anyString(), anyList(), any())).thenReturn(new RequirementIntakeMatcher.Result(Status.COMPLETE, null, "GEMINI", List.of()));
        doNothing().doThrow(PortfolioException.notFound("Unavailable")).when(candidates).recheck(any(), eq("alice"), eq(context));
        assertThatThrownBy(() -> service.review(new Request(2L, null, "text", null), "alice", context)).isInstanceOf(PortfolioException.class);
        var order=inOrder(candidates,matcher);
        order.verify(candidates).recheck(any(),eq("alice"),eq(context));
        order.verify(matcher).review(anyString(),anyList(),any());
        order.verify(candidates).recheckSources(any(),eq("alice"));
        order.verify(candidates).recheck(any(),eq("alice"),eq(context));
    }

    @Test void invalidInputIsRejectedBeforeReadingPrivateEvidence() {
        assertThatThrownBy(() -> service.review(new Request(null, null, " ", null), "alice", context)).isInstanceOf(PortfolioException.class);
        verifyNoInteractions(candidates, matcher);
    }

    @Test void failedReviewKeepsCandidateEvidenceForExplicitManualDecision() {
        var row = RequirementIntakeMatcherTest.candidate();
        frozen(List.of(row), false, true);
        when(matcher.review(anyString(), anyList(), any())).thenReturn(new RequirementIntakeMatcher.Result(Status.FAILED, "INVALID_MODEL_RESPONSE", "GEMINI", List.of(row)));
        var result = service.review(new Request(2L, null, "Different input", null), "alice", context);
        assertThat(result.status()).isEqualTo(Status.FAILED);
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().getFirst().classification()).isNull();
    }

    private void frozen(List<Candidate> rows, boolean partial, boolean allowed) {
        var frozen = new RequirementIntakeCandidates.Frozen(scope, 2L, rows, List.of(), new Coverage(rows.size(), rows.size(), partial), allowed);
        when(candidates.freeze(any(), eq("alice"), eq(context))).thenReturn(frozen);
        when(candidates.discover(any(), eq("alice"))).thenAnswer(call -> call.getArgument(0));
    }
}
