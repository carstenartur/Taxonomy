package com.taxonomy;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/** Persists the real graph used by native screenshots through the application's HTTP boundary. */
final class SavedGraphHttpFixture {
    private static final Map<String, String> CENTRAL_HEADERS = Map.of("X-Taxonomy-Workspace-Id", "");
    private static final ObjectMapper JSON = new ObjectMapper();
    @FunctionalInterface
    interface Requests {
        Map<?, ?> request(String url, String method, String contentType, String body, Map<String, String> headers);
    }

    record Saved(String repositoryId, String workspaceId, String branch, String commitId, String scope) {}

    private SavedGraphHttpFixture() {}

    static Saved create(Requests requests, String branch, String dsl, int relationCount) {
        String identity = "graph-" + UUID.randomUUID();
        // A dedicated in-memory application owns this repository for its whole lifetime.
        // The public HTTP API deletes private working copies, not central repositories.
        Map<?, ?> repository = requests.request("/api/repositories", "POST", "application/json",
                JSON.writeValueAsString(Map.of("displayName", "Saved graph " + identity, "slug", identity,
                        "description", "Published catalogue graph for native screenshots", "visibility", "PUBLIC",
                        "defaultBranch", branch)), Map.of());
        String repositoryId = requiredString(repository, "repositoryId");
        String scope = "repositoryId=" + encode(repositoryId) + "&workspaceId=&workspaceScopeKey=__shared__&branch=" + encode(branch);
        String workspaceId = null;
        try {
            Map<?, ?> workspace = requests.request("/api/architecture/editor/workspaces?" + scope, "POST", "application/json",
                    JSON.writeValueAsString(Map.of("repositoryId", repositoryId, "branch", branch,
                            "displayName", "Graph working copy " + identity)), CENTRAL_HEADERS);
            workspaceId = requiredString(workspace, "workspaceId");
            assertEquals(repositoryId, workspace.get("sourceRepositoryId"));
            assertEquals(branch, workspace.get("sourceBranch"));
            assertEquals("READY", workspace.get("provisioningStatus"));
            String workspaceBranch = requiredString(workspace, "currentBranch");
            String privateScope = "repositoryId=" + encode(repositoryId) + "&workspaceId=" + encode(workspaceId)
                    + "&branch=" + encode(workspaceBranch);
            Map<String, String> privateHeaders = Map.of("X-Taxonomy-Workspace-Id", workspaceId);
            Map<?, ?> saved = requests.request("/api/dsl/commit?" + privateScope
                    + "&message=Accepted+catalogue+graph+example", "POST", "text/plain", dsl, privateHeaders);
            assertEquals(Boolean.TRUE, saved.get("valid"));
            fullCommit(saved, "commitId");
            Map<?, ?> published = requests.request("/api/workspace/publish?" + privateScope
                    + "&userBranch=" + encode(workspaceBranch), "POST", null, null, privateHeaders);
            assertEquals(Boolean.TRUE, published.get("success"));
            String commit = fullCommit(published, "mergeCommit");
            Map<?, ?> readiness = requests.request("/api/architecture/relations/projection/readiness?" + scope,
                    "GET", null, null, CENTRAL_HEADERS);
            assertEquals(repositoryId, readiness.get("repositoryId"));
            assertNull(readiness.get("workspaceId"));
            assertEquals(branch, readiness.get("branch"));
            assertEquals(commit, readiness.get("currentHeadCommit"));
            Map<?, ?> projection = requests.request("/api/architecture/relations/projection/rebuild?" + scope,
                    "POST", null, null, Map.of("X-Taxonomy-Workspace-Id", "", "If-Match", "\"" + commit + "\""));
            assertEquals(repositoryId, projection.get("repositoryId"));
            assertNull(projection.get("workspaceId"));
            assertEquals(branch, projection.get("branch"));
            assertEquals("READY", projection.get("readinessState"));
            assertEquals(commit, projection.get("currentHeadCommit"));
            assertEquals(commit, projection.get("projectedCommit"));
            assertEquals(relationCount, ((Number) projection.get("relationCount")).intValue());
            return new Saved(repositoryId, workspaceId, branch, commit, scope);
        } catch (RuntimeException | AssertionError failure) {
            // The owned workspace is known before commit/publication, including a lost response.
            if (workspaceId != null) {
                try { remove(requests, repositoryId, workspaceId); }
                catch (RuntimeException | AssertionError cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    static void remove(Requests requests, Saved saved) {
        remove(requests, saved.repositoryId(), saved.workspaceId());
    }

    private static void remove(Requests requests, String repositoryId, String workspaceId) {
        Map<?, ?> deleted = requests.request("/api/workspace/" + encode(workspaceId) + "?repositoryId=" + encode(repositoryId)
                        + "&workspaceId=" + encode(workspaceId), "DELETE", null, null,
                Map.of("X-Taxonomy-Workspace-Id", workspaceId));
        assertEquals(Boolean.TRUE, deleted.get("success"));
        assertEquals(workspaceId, deleted.get("deleted"), "Only the owned fixture workspace must be deleted");
    }

    private static String fullCommit(Map<?, ?> response, String field) {
        String commit = requiredString(response, field);
        assertTrue(commit.matches("[a-f0-9]{40}"), "A persisted full " + field + " is required");
        return commit;
    }

    private static String requiredString(Map<?, ?> response, String field) {
        assertInstanceOf(String.class, response.get(field), "Fixture response must contain " + field);
        String value = (String) response.get(field);
        assertFalse(value.isBlank(), "Fixture response must contain a non-empty " + field);
        return value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
