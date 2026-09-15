package com.taxonomy.analysis.service;

import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisTerminalContractTest {
    private final AnalysisProgressRegistry registry = new AnalysisProgressRegistry(new StandardEnvironment());
    private final WorkspaceContext scope = WorkspaceContext.SHARED;

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "ERROR", "PARTIAL"})
    void acceptedCancellationWinsOverCompletionWithoutAnotherCheckpoint(String result) {
        try (var handle = registry.open(null, "alice", scope, null)) {
            registry.cancel(handle.id(), "alice", scope);
            handle.finish(result);
            var snapshot = registry.snapshot(handle.id(), "alice", scope);
            assertEquals("CANCELLED", snapshot.status());
            assertEquals("CANCELLED", snapshot.stopReason());
        }
    }

    @Test void closingAfterAcceptedCancellationRetainsCancellation() {
        String id;
        try (var handle = registry.open(null, "alice", scope, null)) {
            id = handle.id();
            registry.cancel(id, "alice", scope);
        }
        assertEquals("CANCELLED", registry.snapshot(id, "alice", scope).status());
    }

    @Test void cancellationCannotRewriteAnAlreadyCompletedRun() {
        try (var handle = registry.open(null, "alice", scope, null)) {
            handle.finish("SUCCESS");
            assertEquals("COMPLETED", registry.cancel(handle.id(), "alice", scope).status());
            assertNull(registry.snapshot(handle.id(), "alice", scope).stopReason());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"MEMORY_PRESSURE", "TIME_LIMIT"})
    void aRecordedResourceStopIsNotOverwrittenByLaterCancellation(String reason) {
        try (var handle = registry.open(null, "alice", scope, null)) {
            assertThrows(AnalysisStoppedException.class, () -> AnalysisRunControl.call("MOCK", "CP", () -> {
                throw new AnalysisStoppedException(AnalysisStoppedException.Reason.valueOf(reason));
            }));
            registry.cancel(handle.id(), "alice", scope);
            var cancellation = assertThrows(AnalysisStoppedException.class, AnalysisRunControl::checkpoint);
            assertEquals(AnalysisStoppedException.Reason.CANCELLED, cancellation.reason());
            handle.finish("PARTIAL");
            assertEquals(reason, registry.snapshot(handle.id(), "alice", scope).stopReason());
            assertEquals("PARTIAL", registry.snapshot(handle.id(), "alice", scope).status());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "1-1-1-1-1", "CB2A3D71-E849-4A50-9855-1F9CB8F81402", " "})
    void malformedIdsAreRejectedConsistentlyOnAllRegistryOperations(String id) {
        assertBadId(() -> registry.open(id, "alice", scope, null));
        assertBadId(() -> registry.snapshot(id, "alice", scope));
        assertBadId(() -> registry.cancel(id, "alice", scope));
        assertBadId(() -> registry.callDetail(id, 1, "alice", scope));
        assertTrue(registry.recent("alice", scope, null, null).isEmpty());
    }

    private static void assertBadId(org.junit.jupiter.api.function.Executable operation) {
        assertEquals(400, assertThrows(ResponseStatusException.class, operation).getStatusCode().value());
    }
}
