package com.taxonomy.service;

import com.taxonomy.model.ContextHistoryRecord;
import com.taxonomy.repository.ContextHistoryRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ContextHistoryService}.
 *
 * <p>Verifies persistent navigation history: recording, retrieval,
 * and clearing. No Spring context required.
 */
class ContextHistoryServiceTest {

    private ContextHistoryRecordRepository historyRepo;
    private ContextHistoryService historyService;

    @BeforeEach
    void setUp() {
        historyRepo = mock(ContextHistoryRecordRepository.class);
        historyService = new ContextHistoryService(historyRepo);
    }

    // ── Recording ───────────────────────────────────────────────────

    @Test
    void recordNavigation_persistsEntry() {
        when(historyRepo.save(any(ContextHistoryRecord.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        historyService.recordNavigation(
                "alice",
                "ctx-old", "ctx-new",
                "draft", "feature",
                "aaa1111", "bbb2222",
                "MANUAL_SWITCH",
                "ctx-origin"
        );

        verify(historyRepo).save(argThat(record -> {
            assertEquals("alice", record.getUsername());
            assertEquals("ctx-old", record.getFromContextId());
            assertEquals("ctx-new", record.getToContextId());
            assertEquals("draft", record.getFromBranch());
            assertEquals("feature", record.getToBranch());
            assertEquals("aaa1111", record.getFromCommitId());
            assertEquals("bbb2222", record.getToCommitId());
            assertEquals("MANUAL_SWITCH", record.getReason());
            assertEquals("ctx-origin", record.getOriginContextId());
            assertNotNull(record.getCreatedAt());
            return true;
        }));
    }

    // ── Retrieval ───────────────────────────────────────────────────

    @Test
    void getHistory_returnsRecordsInOrder() {
        ContextHistoryRecord recent = new ContextHistoryRecord();
        recent.setUsername("alice");
        recent.setReason("COMPARE");
        recent.setCreatedAt(Instant.now());

        ContextHistoryRecord older = new ContextHistoryRecord();
        older.setUsername("alice");
        older.setReason("SEARCH_OPEN");
        older.setCreatedAt(Instant.now().minusSeconds(60));

        when(historyRepo.findTop50ByUsernameOrderByCreatedAtDesc("alice"))
                .thenReturn(List.of(recent, older));

        List<ContextHistoryRecord> history = historyService.getHistory("alice");

        assertEquals(2, history.size());
        assertEquals("COMPARE", history.get(0).getReason());
        assertEquals("SEARCH_OPEN", history.get(1).getReason());
    }

    // ── Clearing ────────────────────────────────────────────────────

    @Test
    void clearHistory_deletesAllForUser() {
        historyService.clearHistory("alice");

        verify(historyRepo).deleteByUsername("alice");
    }
}
