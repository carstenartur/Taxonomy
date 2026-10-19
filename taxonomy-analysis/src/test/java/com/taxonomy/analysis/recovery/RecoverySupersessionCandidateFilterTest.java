package com.taxonomy.analysis.recovery;

import com.taxonomy.dto.LlmCallDetail;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** A node preselection may avoid opaque reads but must never authorize supersession by itself. */
class RecoverySupersessionCandidateFilterTest {
    @Test void acceptedHistoryDoesNotAllocateAnInListComparisonForEveryCheckpoint() {
        // Exercise retained terminal evidence in the real persistent store, rather than a mocked query.
        RecoveryCheckpointPersistenceProbe.supersessionTerminalHistoryAllocationIsBounded();
    }

    @Test void unrelatedLegacyEncodedAndMultiNodeQuestionsNeedOnlyMetadata() {
        RecoveryCheckpointPersistenceProbe.unrelatedSupersessionUsesMetadataOnly();
    }
    @Test void unrelatedEncodedPromptDoesNotReachThePayloadDecoder() {
        RecoveryCheckpointPersistenceProbe.filteredSupersessionDecodesOnlyRelevantPrompts(false);
    }
    @Test void relevantEncodedPromptStillRequiresSuccessfulPayloadDecoding() {
        RecoveryCheckpointPersistenceProbe.filteredSupersessionDecodesOnlyRelevantPrompts(true);
    }
    @Test void invalidNodeMetadataFailsClosedBeforeAnyPayloadRead() {
        RecoveryCheckpointPersistenceProbe.filteredSupersessionRejectsCorruptNodeMetadata();
    }
    @Test void matchingCorrectionKeepsFullIdentityHashEvidenceAndRevisionChecks() {
        RecoveryCheckpointPersistenceProbe.supersessionUsesQuestionInputsOnly(true);
    }
    @Test void matchingDistributedCorrectionKeepsNamespaceAndLocalAmbiguityChecks() {
        RecoveryCheckpointPersistenceProbe.supersessionIsNamespaceBound(true);
    }
    @Test void evenAnEmptyPreselectionRequiresTheCurrentUncancelledClaim() {
        RecoveryCheckpointPersistenceProbe.supersessionIsClaimBound(true);
    }

    @Test void anExistingSessionStoreRetainsItsOriginalSupersessionCallback() {
        var calls = new AtomicInteger();
        Predicate<AnalysisCheckpointSession.Question> predicate = q -> false;
        var store = new AnalysisCheckpointSession.Store() {
            public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question q) { throw new AssertionError(); }
            public void finish(AnalysisCheckpointSession.Question q, String state, LlmCallDetail detail) { throw new AssertionError(); }
            public void checkActive() { throw new AssertionError(); }
            public void supersedeEvidence(String kind, String provider, Predicate<AnalysisCheckpointSession.Question> covered) {
                assertEquals("SOURCES", kind); assertEquals("MOCK", provider); assertSame(predicate, covered); calls.incrementAndGet();
            }
        };
        try (var session = new AnalysisCheckpointSession(store)) {
            AnalysisCheckpointSession.supersedeEvidence("SOURCES", "MOCK", Set.of("UA-covered"), predicate);
        }
        assertEquals(1, calls.get());
    }

    @Test void theExecutionAdapterForwardsTheSameClaimAndExactPredicateWithTheCodes() {
        var store = mock(AnalysisContinuationStore.class);
        var service = new AnalysisContinuationService(store, null, null, null, new ObjectMapper());
        var claim = new AnalysisContinuationStore.Claim("run", "claim", null);
        Predicate<AnalysisCheckpointSession.Question> predicate = q -> false;
        Set<String> codes = Set.of("UA-first", "UA-second");
        try (var execution = service.new Execution(claim)) {
            AnalysisCheckpointSession.supersedeEvidence("SOURCES", "MOCK", codes, predicate);
        }
        verify(store).supersedeEvidence(claim, "SOURCES", "MOCK", codes, predicate, null);
        verify(store, never()).supersedeEvidence(claim, "SOURCES", "MOCK", predicate, null);
    }
}
