package com.taxonomy.workspace.service;

import com.taxonomy.dto.ContextHistoryEntry;
import com.taxonomy.dto.ContextMode;
import com.taxonomy.dto.ContextRef;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/** One navigation cursor, with no projection or operation state. */
public final class WorkspaceNavigation {
    private final int maxHistory;

    // Context navigation state
    private volatile NavigationSelection navigation;
    private final Deque<ContextHistoryEntry> history = new ArrayDeque<>();
    private final Deque<ContextRef> previousContexts = new ArrayDeque<>();

    public record NavigationSelection(ContextRef current, ContextRef origin) {}

    WorkspaceNavigation(int maxHistory, String branch) {
        this.maxHistory = maxHistory;
        ContextRef current = new ContextRef(
                UUID.randomUUID().toString(),
                branch == null || branch.isBlank() ? "draft" : branch,
                null,
                Instant.now(),
                ContextMode.EDITABLE,
                null, null, null, null, null, false
        );
        this.navigation = new NavigationSelection(current, null);
    }

    synchronized WorkspaceNavigation copy() {
        var result = new WorkspaceNavigation(maxHistory, navigation.current().branch());
        result.navigation = navigation;
        result.history.addAll(history);
        result.previousContexts.addAll(previousContexts);
        return result;
    }

    // ── Context navigation ──────────────────────────────────────────

    public ContextRef getCurrentContext() {
        return navigation.current();
    }

    /** Read the current selection and its recorded origin together. */
    public NavigationSelection getNavigationSelection() {
        return navigation;
    }

    public synchronized void setCurrentContext(ContextRef ctx) {
        ContextRef previous = navigation.current();
        ContextRef origin = previous.contextId().equals(ctx.originContextId()) ? previous : null;
        if (origin == null) {
            previousContexts.clear();
        } else {
            previousContexts.addLast(origin);
            while (previousContexts.size() > Math.max(1, maxHistory)) previousContexts.removeFirst();
        }
        this.navigation = new NavigationSelection(ctx, origin);
    }

    /** Restore the prior selection under a fresh identity for request fencing. */
    public synchronized ContextRef restorePreviousContext() {
        ContextRef previous = previousContexts.pollLast();
        if (previous == null) return navigation.current();
        ContextRef origin = previousContexts.peekLast();
        ContextRef restored = new ContextRef(UUID.randomUUID().toString(), previous.branch(), previous.commitId(),
                Instant.now(), previous.mode(), origin == null ? null : origin.contextId(),
                origin == null ? null : origin.branch(), origin == null ? null : origin.commitId(),
                previous.openedFromSearch(), previous.matchedElementId(), previous.dirty());
        this.navigation = new NavigationSelection(restored, origin);
        return restored;
    }

    public synchronized List<ContextHistoryEntry> getHistory() {
        return new ArrayList<>(history);
    }

    public synchronized void addHistoryEntry(ContextHistoryEntry entry) {
        history.addLast(entry);
        while (history.size() > maxHistory) {
            history.pollFirst();
        }
    }

    public synchronized ContextHistoryEntry pollLastHistory() {
        return history.pollLast();
    }

    public synchronized boolean isHistoryEmpty() {
        return history.isEmpty();
    }

}
