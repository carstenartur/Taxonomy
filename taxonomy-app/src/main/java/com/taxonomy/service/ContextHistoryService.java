package com.taxonomy.service;

import com.taxonomy.model.ContextHistoryRecord;
import com.taxonomy.repository.ContextHistoryRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Manages persistent context navigation history with origin tracking.
 *
 * <p>Each navigation event captures the source and destination contexts,
 * branches, and commits, together with the reason for the navigation
 * (e.g. {@code "COMPARE"}, {@code "SEARCH_OPEN"}, {@code "MANUAL_SWITCH"}).
 *
 * <p>The {@code originContextId} field supports "return-to-origin"
 * navigation: when a user drills into history or a comparison, the
 * original context is preserved so they can jump back to where they
 * started.
 *
 * <p>History is capped at 50 entries per user to prevent unbounded growth.
 */
@Service
public class ContextHistoryService {

    private static final Logger log = LoggerFactory.getLogger(ContextHistoryService.class);

    private final ContextHistoryRecordRepository historyRepository;

    public ContextHistoryService(ContextHistoryRecordRepository historyRepository) {
        this.historyRepository = historyRepository;
    }

    /**
     * Record a navigation event in the user's persistent history.
     *
     * @param username        the authenticated user's username
     * @param fromContextId   the context the user navigated away from (may be null)
     * @param toContextId     the context the user navigated to
     * @param fromBranch      the branch the user was on before navigation (may be null)
     * @param toBranch        the branch the user navigated to
     * @param fromCommitId    the commit SHA before navigation (may be null)
     * @param toCommitId      the commit SHA after navigation (may be null)
     * @param reason          the reason for navigation (e.g. "COMPARE", "SEARCH_OPEN")
     * @param originContextId the original context for return-to-origin support (may be null)
     */
    public void recordNavigation(String username,
                                 String fromContextId,
                                 String toContextId,
                                 String fromBranch,
                                 String toBranch,
                                 String fromCommitId,
                                 String toCommitId,
                                 String reason,
                                 String originContextId) {
        try {
            ContextHistoryRecord record = new ContextHistoryRecord();
            record.setUsername(username);
            record.setFromContextId(fromContextId);
            record.setToContextId(toContextId);
            record.setFromBranch(fromBranch);
            record.setToBranch(toBranch);
            record.setFromCommitId(fromCommitId);
            record.setToCommitId(toCommitId);
            record.setReason(reason);
            record.setOriginContextId(originContextId);
            record.setCreatedAt(Instant.now());

            historyRepository.save(record);
            log.debug("User '{}': recorded navigation {} -> {} (reason: {})",
                    username, fromContextId, toContextId, reason);
        } catch (Exception e) {
            // Non-fatal: navigation history is informational
            log.warn("Could not record navigation for user '{}': {}",
                    username, e.getMessage());
        }
    }

    /**
     * Get the user's recent navigation history (max 50 entries).
     *
     * <p>Results are ordered by creation time, newest first.
     *
     * @param username the authenticated user's username
     * @return list of history records, newest first (never null)
     */
    public List<ContextHistoryRecord> getHistory(String username) {
        try {
            return historyRepository.findTop50ByUsernameOrderByCreatedAtDesc(username);
        } catch (Exception e) {
            log.warn("Could not retrieve history for user '{}': {}", username, e.getMessage());
            return List.of();
        }
    }

    /**
     * Delete all navigation history for a user.
     *
     * <p>This is a destructive operation. It removes all persistent history
     * records for the specified user.
     *
     * @param username the user whose history to clear
     */
    public void clearHistory(String username) {
        try {
            historyRepository.deleteByUsername(username);
            log.info("Cleared navigation history for user '{}'", username);
        } catch (Exception e) {
            log.warn("Could not clear history for user '{}': {}", username, e.getMessage());
        }
    }
}
