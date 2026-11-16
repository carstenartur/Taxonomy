package com.taxonomy.workspace.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.WebUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Navigation state owned by a signed-in browser session, independent of its working copy. */
public final class WorkspaceNavigationSession {
    private static final String ATTRIBUTE = WorkspaceNavigationSession.class.getName();
    private static final int LIMIT = 64;
    private final Map<Key, WorkspaceNavigation> cursors = new LinkedHashMap<>(16, 0.75f, true);

    private record Key(String username, String repository, String workspace, UUID cursor) {
        Key(WorkspaceContext scope, UUID cursor) {
            this(scope.username(), scope.repositoryId(), scope.workspaceId(), cursor);
        }
    }

    public static WorkspaceNavigation resolve(HttpServletRequest request, HttpServletResponse response,
                                              WorkspaceContext scope, boolean initialize, int maxHistory) {
        String requested = request.getHeader("X-Taxonomy-Navigation-Cursor");
        if (requested == null || scope.workspaceId() == null) return null;
        UUID cursor = uuid(requested);
        var session = request.getSession(false);
        if (session == null) throw unavailable();
        WorkspaceNavigationSession state;
        synchronized (WebUtils.getSessionMutex(session)) {
            state = (WorkspaceNavigationSession) session.getAttribute(ATTRIBUTE);
            if (state == null) {
                state = new WorkspaceNavigationSession();
                session.setAttribute(ATTRIBUTE, state);
            }
        }
        return state.resolve(scope, cursor, request.getHeader("X-Taxonomy-Navigation-Previous"),
                initialize, maxHistory, response);
    }

    private synchronized WorkspaceNavigation resolve(WorkspaceContext scope, UUID cursor, String previous,
                                                     boolean initialize, int maxHistory, HttpServletResponse response) {
        Key key = new Key(scope, cursor);
        WorkspaceNavigation existing = cursors.get(key);
        if (existing != null) return existing;
        if (!initialize) throw unavailable();
        WorkspaceNavigation source = previous == null ? null : cursors.get(new Key(scope, uuid(previous)));
        if (previous != null && source == null && response != null) {
            response.setHeader("X-Taxonomy-Navigation-Reset", "true");
        }
        WorkspaceNavigation created = source == null
                ? new WorkspaceNavigation(maxHistory, scope.currentBranch()) : source.copy();
        cursors.put(key, created);
        if (cursors.size() > LIMIT) cursors.remove(cursors.keySet().iterator().next());
        return created;
    }

    private static UUID uuid(String value) {
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid navigation cursor");
        }
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "The workspace navigation is unavailable. Refresh the context and try again.");
    }
}
