package com.taxonomy.versioning.service;

import com.taxonomy.versioning.model.ContextHistoryRecord;
import com.taxonomy.versioning.repository.ContextHistoryRecordRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContextHistoryRetentionTest {
    private final ContextHistoryRecordRepository repository = mock(ContextHistoryRecordRepository.class);
    private final ContextHistoryService service = new ContextHistoryService(repository);

    @Test
    void retainsNewestFiftyEntriesAndDeletesOnlyTheOlderEntriesOfThatUser() {
        List<ContextHistoryRecord> newestFirst = IntStream.range(0, 53)
                .mapToObj(i -> new ContextHistoryRecord()).toList();
        when(repository.countByUsername("alice")).thenReturn(53L);
        when(repository.findByUsernameOrderByCreatedAtDesc("alice")).thenReturn(newestFirst);

        recordNavigation();

        verify(repository).deleteAll(newestFirst.subList(50, 53));
        verify(repository, never()).deleteByUsername(anyString());
    }

    @Test
    void atTheLimitDoesNotLoadOrDeleteHistory() {
        when(repository.countByUsername("alice")).thenReturn(50L);
        recordNavigation();
        verify(repository, never()).findByUsernameOrderByCreatedAtDesc(anyString());
        verify(repository, never()).deleteAll(anyIterable());
    }

    @Test
    void failedSaveDoesNotTrimAndDoesNotInterruptNavigation() {
        when(repository.save(any())).thenThrow(new IllegalStateException("storage unavailable"));
        assertDoesNotThrow(this::recordNavigation);
        verify(repository, never()).countByUsername(anyString());
    }

    @Test
    void failedTrimLeavesTheNavigationRecordSaved() {
        when(repository.countByUsername("alice")).thenThrow(new IllegalStateException("count unavailable"));
        assertDoesNotThrow(this::recordNavigation);
        verify(repository).save(any(ContextHistoryRecord.class));
        verify(repository, never()).deleteAll(anyIterable());
    }

    @Test
    void unavailableHistoryIsEmptyAndFailedClearIsNonFatal() {
        when(repository.findTop50ByUsernameOrderByCreatedAtDesc("alice"))
                .thenThrow(new IllegalStateException("read unavailable"));
        doThrow(new IllegalStateException("delete unavailable")).when(repository).deleteByUsername("alice");
        assertEquals(List.of(), service.getHistory("alice"));
        assertDoesNotThrow(() -> service.clearHistory("alice"));
        verify(repository).deleteByUsername("alice");
    }

    private void recordNavigation() {
        service.recordNavigation("alice", "old", "new", "draft", "review",
                "before", "after", "COMPARE", "origin");
    }
}
