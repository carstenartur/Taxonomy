package com.taxonomy.analysis.controller;

import com.taxonomy.analysis.service.AnalysisProgressRegistry;
import com.taxonomy.analysis.service.AnalysisRunControl;
import com.taxonomy.analysis.usecase.*;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.MessageSource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AnalysisStreamAdmissionContractTest {
    private static final String ID = "cb2a3d71-e849-4a50-9855-1f9cb8f81402";
    private final ExecutorService executor = mock(ExecutorService.class);
    private final StreamRequirementAnalysisUseCase streaming = mock(StreamRequirementAnalysisUseCase.class);
    private final AnalyzeRequirementUseCase full = mock(AnalyzeRequirementUseCase.class);
    private final AtomicReference<Runnable> queued = new AtomicReference<>();
    private AnalysisProgressRegistry registry;
    private MockMvc mvc;

    @BeforeEach void setup() {
        var taxonomy = mock(TaxonomyService.class);
        when(taxonomy.isInitialized()).thenReturn(true);
        var resolver = mock(WorkspaceResolver.class);
        when(resolver.resolveCurrentUsername()).thenReturn("alice");
        when(resolver.resolveCurrentContext()).thenReturn(WorkspaceContext.SHARED);
        var controller = new AnalysisApiController(taxonomy, executor, new ObjectMapper(), full, streaming,
                mock(AnalyzeNodeChildrenUseCase.class), mock(JustifyLeafUseCase.class), new AnalysisSseEventMapper(),
                mock(RepositoryStateService.class), resolver, mock(MessageSource.class));
        registry = new AnalysisProgressRegistry(new StandardEnvironment());
        ReflectionTestUtils.setField(controller, "analysisProgressRegistry", registry);
        doAnswer(call -> { queued.set(call.getArgument(0)); return null; }).when(executor).execute(any());
        mvc = MockMvcBuilders.standaloneSetup(controller,
                new AnalysisProgressController(registry, resolver)).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "1-1-1-1-1", "CB2A3D71-E849-4A50-9855-1F9CB8F81402"})
    void invalidOperationHeaderFailsBeforeSchedulingForBothEndpoints(String id) throws Exception {
        mvc.perform(post("/api/analyze").header("X-Analysis-Operation-Id", id)
                        .contentType("application/json").content("{\"businessText\":\"communications\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/analyze-stream").header("X-Analysis-Operation-Id", id)
                        .param("businessText", "communications"))
                .andExpect(status().isBadRequest());
        assertNull(queued.get());
        verifyNoInteractions(full, streaming);
    }

    @Test void malformedObservationIsNotMistakenForPendingAdmission() throws Exception {
        mvc.perform(get("/api/analysis-runs/not-a-uuid").param("waitForRegistration", "true"))
                .andExpect(status().isBadRequest());
        assertTrue(registry.recent("alice", WorkspaceContext.SHARED, null, null).isEmpty());
    }

    @Test void cooperativeStopBeforeStreamingIsPartialAndDoesNotInvokeTheProvider() throws Exception {
        var request = mvc.perform(get("/api/analyze-stream").header("X-Analysis-Operation-Id", ID)
                        .param("businessText", "communications"))
                .andExpect(request().asyncStarted()).andReturn();
        assertNotNull(queued.get());
        Thread worker = Thread.ofPlatform().start(() -> {
            Thread.currentThread().interrupt(); // Disconnect/cancellation before worker preflight.
            queued.get().run();
        });
        worker.join(3000);
        assertFalse(worker.isAlive());
        var completed = mvc.perform(asyncDispatch(request)).andExpect(status().isOk()).andReturn();
        String body = completed.getResponse().getContentAsString();
        assertTrue(body.contains("\"status\":\"PARTIAL\""), body);
        assertTrue(body.contains("CANCELLED"), body);
        assertFalse(body.contains("\"status\":\"ERROR\""), body);
        assertEquals("CANCELLED", registry.snapshot(ID, "alice", WorkspaceContext.SHARED).status());
        verifyNoInteractions(streaming);
    }

    @Test void duplicateSseIdIsRejectedBeforeQueuingAndKeepsTheOriginalRun() throws Exception {
        mvc.perform(get("/api/analyze-stream").header("X-Analysis-Operation-Id", ID)
                        .param("businessText", "communications"))
                .andExpect(request().asyncStarted());
        Runnable accepted = queued.get();
        mvc.perform(get("/api/analyze-stream").header("X-Analysis-Operation-Id", ID)
                        .param("businessText", "communications"))
                .andExpect(status().isConflict()).andExpect(request().asyncNotStarted());
        verify(executor, times(1)).execute(any());
        assertSame(accepted, queued.get());
        assertEquals("RUNNING", registry.snapshot(ID, "alice", WorkspaceContext.SHARED).status());
        assertFalse(AnalysisRunControl.active(), "Admission must not bind the HTTP thread's run control");
        verifyNoInteractions(streaming);
    }

    @Test void queuedSseReservationsCountTowardCapacityBeforeAnyWorkerStarts() throws Exception {
        for (int i = 0; i < 4; i++) {
            mvc.perform(get("/api/analyze-stream").header("X-Analysis-Operation-Id", UUID.randomUUID().toString())
                            .param("businessText", "communications"))
                    .andExpect(request().asyncStarted());
        }
        mvc.perform(get("/api/analyze-stream").header("X-Analysis-Operation-Id", ID)
                        .param("businessText", "communications"))
                .andExpect(status().isServiceUnavailable()).andExpect(request().asyncNotStarted());
        verify(executor, times(4)).execute(any());
        assertEquals(4, registry.recent("alice", WorkspaceContext.SHARED, null, null).size());
        assertFalse(AnalysisRunControl.active());
        verifyNoInteractions(streaming);
    }

    @Test void rejectedQueueSubmissionReleasesItsReservationForTheSameId() throws Exception {
        doThrow(new RejectedExecutionException("full")).when(executor).execute(any());
        mvc.perform(get("/api/analyze-stream").header("X-Analysis-Operation-Id", ID)
                        .param("businessText", "communications"))
                .andExpect(status().isServiceUnavailable()).andExpect(request().asyncNotStarted());
        assertTrue(registry.recent("alice", WorkspaceContext.SHARED, null, null).isEmpty());
        assertFalse(AnalysisRunControl.active());
        doAnswer(call -> { queued.set(call.getArgument(0)); return null; }).when(executor).execute(any());
        mvc.perform(get("/api/analyze-stream").header("X-Analysis-Operation-Id", ID)
                        .param("businessText", "communications"))
                .andExpect(request().asyncStarted());
        assertEquals(ID, registry.snapshot(ID, "alice", WorkspaceContext.SHARED).operationId());
    }

    @Test void cancellationOfQueuedReservationIsObservedByWorkerWithoutLeakingThreadContext() throws Exception {
        var request = mvc.perform(get("/api/analyze-stream").header("X-Analysis-Operation-Id", ID)
                        .param("businessText", "communications"))
                .andExpect(request().asyncStarted()).andReturn();
        assertFalse(AnalysisRunControl.active());
        assertEquals("CANCELLING", registry.cancel(ID, "alice", WorkspaceContext.SHARED).status());
        var workerFailure = new AtomicReference<Throwable>();
        Thread worker = Thread.ofPlatform().start(() -> {
            try {
                assertFalse(AnalysisRunControl.active());
                queued.get().run();
                assertFalse(AnalysisRunControl.active(), "Worker must release its thread-local control");
            } catch (Throwable failure) { workerFailure.set(failure); }
        });
        worker.join(3000);
        assertFalse(worker.isAlive());
        assertNull(workerFailure.get());
        mvc.perform(asyncDispatch(request)).andExpect(status().isOk());
        assertEquals("CANCELLED", registry.snapshot(ID, "alice", WorkspaceContext.SHARED).status());
        verifyNoInteractions(streaming);
    }
}
