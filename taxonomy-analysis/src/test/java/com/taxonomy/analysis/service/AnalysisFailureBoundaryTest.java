package com.taxonomy.analysis.service;

import com.taxonomy.analysis.controller.AnalysisApiController;
import com.taxonomy.analysis.controller.AnalysisSseEventMapper;
import com.taxonomy.analysis.usecase.AnalyzeNodeChildrenUseCase;
import com.taxonomy.analysis.usecase.AnalyzeRequirementUseCase;
import com.taxonomy.analysis.usecase.JustifyLeafUseCase;
import com.taxonomy.analysis.usecase.StreamRequirementAnalysisUseCase;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.AnalysisRequest;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisFailureBoundaryTest {
    @Test void cancellationDuringProviderFailureRemainsCancellation() { assertStoppedFailure(AnalysisStoppedException.Reason.CANCELLED); }
    @Test void deadlineDuringProviderFailureRemainsTimeLimit() { assertStoppedFailure(AnalysisStoppedException.Reason.TIME_LIMIT); }
    @Test void memoryPressureDuringProviderFailureRemainsMemoryPressure() { assertStoppedFailure(AnalysisStoppedException.Reason.MEMORY_PRESSURE); }

    private void assertStoppedFailure(AnalysisStoppedException.Reason reason) {
        AtomicBoolean cancel = new AtomicBoolean();
        AtomicLong clock = new AtomicLong();
        AtomicLong used = new AtomicLong(10L * 1024 * 1024);
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger stops = new AtomicInteger();
        AnalysisMemoryGuard guard = new AnalysisMemoryGuard(
                new AnalysisMemoryGuard.Policy(80, 92, 1024 * 1024, 5000, 100),
                () -> new AnalysisMemoryGuard.Sample(used.get(), 100L * 1024 * 1024), clock::get);
        try (var control = new AnalysisRunControl(observer(failures, stops), cancel::get, guard)) {
            AnalysisStoppedException error = assertThrows(AnalysisStoppedException.class,
                    () -> AnalysisRunControl.call("MOCK", "BP", () -> {
                        switch (reason) {
                            case CANCELLED -> cancel.set(true);
                            case TIME_LIMIT -> clock.set(101);
                            case MEMORY_PRESSURE -> used.set(99L * 1024 * 1024);
                        }
                        throw new IllegalStateException("provider failed while the run was stopping");
                    }));
            assertEquals(reason, error.reason());
            assertEquals(0, failures.get());
            assertEquals(1, stops.get());
        }
    }

    @Test void ordinaryProviderFailureKeepsItsOriginalIdentity() {
        AtomicInteger failures = new AtomicInteger(), stops = new AtomicInteger();
        var original = new IllegalStateException("ordinary provider failure");
        var guard = new AnalysisMemoryGuard(new AnalysisMemoryGuard.Policy(80, 92, 1024 * 1024, 5000, 100),
                () -> new AnalysisMemoryGuard.Sample(10L * 1024 * 1024, 100L * 1024 * 1024), () -> 0);
        try (var control = new AnalysisRunControl(observer(failures, stops), () -> false, guard)) {
            assertSame(original, assertThrows(IllegalStateException.class,
                    () -> AnalysisRunControl.call("MOCK", "BP", () -> { throw original; })));
            assertEquals(1, failures.get());
            assertEquals(0, stops.get());
        }
    }

    private static AnalysisRunControl.Observer observer(AtomicInteger failures, AtomicInteger stops) {
        return new AnalysisRunControl.Observer() {
            public void phase(String phase, String node) { }
            public long started(String provider, String node) { return 1; }
            public void completed(long id, LlmCallDetail detail, long duration) { fail("Unexpected completion"); }
            public void failed(long id, String error, long duration) { failures.incrementAndGet(); }
            public void stopped(AnalysisStoppedException.Reason reason) { stops.incrementAndGet(); }
        };
    }

    @Test void workspaceProvisioningFailureDoesNotStartAnUnobservableSharedAnalysis() {
        Fixture f = fixture();
        doThrow(new IllegalStateException("repository unavailable")).when(f.repository).ensureWorkspaceState("alice");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                assertThrows(ResponseStatusException.class, () -> f.controller.analyze(request())).getStatusCode());
        verifyNoInteractions(f.analysis, f.streaming);
        verify(f.workspace, never()).resolveCurrentContext();
    }

    @Test void workspaceResolutionFailureDoesNotStartStreamingWork() {
        Fixture f = fixture();
        when(f.workspace.resolveCurrentContext()).thenThrow(new IllegalStateException("workspace unavailable"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                assertThrows(ResponseStatusException.class, () -> f.controller.analyzeStream("Need communications", "MOCK")).getStatusCode());
        verifyNoInteractions(f.executor, f.analysis, f.streaming);
    }

    @Test void explicitWorkspaceHttpDenialRetainsItsStatus() {
        Fixture f = fixture();
        var denial = new ResponseStatusException(HttpStatus.FORBIDDEN, "denied workspace");
        when(f.workspace.resolveCurrentContext()).thenThrow(denial);
        assertSame(denial, assertThrows(ResponseStatusException.class, () -> f.controller.analyze(request())));
        verifyNoInteractions(f.analysis, f.streaming);
    }

    @Test void explicitWorkspaceAccessDenialNeverFallsBack() {
        Fixture f = fixture();
        var denial = new AccessDeniedException("foreign workspace");
        when(f.workspace.resolveCurrentContext()).thenThrow(denial);
        assertSame(denial, assertThrows(AccessDeniedException.class, () -> f.controller.analyze(request())));
        verifyNoInteractions(f.analysis, f.streaming);
    }

    private static AnalysisRequest request() {
        var request = new AnalysisRequest(); request.setBusinessText("Need communications"); return request;
    }

    private static Fixture fixture() {
        var taxonomy = mock(TaxonomyService.class);
        var repository = mock(RepositoryStateService.class);
        var workspace = mock(WorkspaceResolver.class);
        var executor = mock(ExecutorService.class);
        var analysis = mock(AnalyzeRequirementUseCase.class);
        var streaming = mock(StreamRequirementAnalysisUseCase.class);
        when(taxonomy.isInitialized()).thenReturn(true);
        when(workspace.resolveCurrentUsername()).thenReturn("alice");
        var controller = new AnalysisApiController(taxonomy, executor, new ObjectMapper(), analysis, streaming,
                mock(AnalyzeNodeChildrenUseCase.class), mock(JustifyLeafUseCase.class), new AnalysisSseEventMapper(),
                repository, workspace, mock(MessageSource.class));
        return new Fixture(controller, repository, workspace, executor, analysis, streaming);
    }
    private record Fixture(AnalysisApiController controller, RepositoryStateService repository,
                           WorkspaceResolver workspace, ExecutorService executor,
                           AnalyzeRequirementUseCase analysis, StreamRequirementAnalysisUseCase streaming) { }
}
