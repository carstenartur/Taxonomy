package com.taxonomy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The same public A / working copy / fork contract against each packaged database configuration. */
final class RepositoryHistoryHttpContract implements AutoCloseable {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private final URI application;
    private final String authorization;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String name = "history-" + UUID.randomUUID();

    RepositoryHistoryHttpContract(URI application, String authorization) {
        this.application = application;
        this.authorization = authorization;
    }

    void verify() throws Exception {
        String source = post("/api/repositories", Map.of("displayName", name, "slug", name,
                "visibility", "PRIVATE", "defaultBranch", "draft")).required("repositoryId").textValue();
        JsonNode fork = post("/api/repositories/" + source + "/forks", Map.of("displayName", name + " fork",
                "slug", name + "-fork", "visibility", "PRIVATE", "sourceBranch", "draft"));
        String forkId = fork.required("repositoryId").textValue();
        String base = fork.required("forkPointCommit").textValue();
        assertThat(forkId).isNotEqualTo(source);
        assertThat(fork.required("upstreamRepositoryId").textValue()).isEqualTo(source);
        assertThat(base).matches("[0-9a-f]{40}");

        Copy sourceEdit = copy(source, "source editor", base);
        Copy forkEdit = copy(forkId, "fork editor", base);
        Copy witness = copy(source, "independent recipient", base);
        String forkMarker = name + " fork only";
        String sourceMarker = name + " source only";
        String forkCommit = publish(forkEdit, forkMarker);
        String sourceCommit = publish(sourceEdit, sourceMarker);
        assertThat(forkCommit).isNotEqualTo(sourceCommit);

        String workspaceFetch = "/api/workspace/upstream/fetch";
        request("POST", workspaceFetch, witness, JSON.writeValueAsString(Map.of(
                "sourceCommit", sourceCommit, "expectedTrackedCommit", forkCommit)), "application/json", 409);
        var fetch = Map.of("sourceCommit", sourceCommit, "expectedTrackedCommit", base);
        assertThat(json("POST", workspaceFetch, witness, fetch).required("trackedCommit").textValue()).isEqualTo(sourceCommit);
        String forkUpstream = "/api/repositories/" + forkId + "/upstream";
        assertThat(post(forkUpstream + "/fetch", fetch).required("trackedCommit").textValue()).isEqualTo(sourceCommit);
        assertThat(head(witness)).isEqualTo(base);
        assertThat(head(forkEdit)).isEqualTo(forkCommit);

        archiveAndDelete(sourceEdit, forkEdit, forkCommit, forkMarker);
        Copy preservedSource = copy(source, "source after deletion", sourceCommit);
        assertThat(content(preservedSource)).contains(sourceMarker).doesNotContain(forkMarker);
        assertThat(get("/api/workspace/upstream", witness).required("trackedCommit").textValue()).isEqualTo(sourceCommit);

        archiveAndDelete(forkEdit, preservedSource, sourceCommit, sourceMarker);
        Copy preservedFork = copy(forkId, "fork after deletion", forkCommit);
        assertThat(content(preservedFork)).contains(forkMarker).doesNotContain(sourceMarker);
        assertThat(get(forkUpstream, null).required("trackedCommit").textValue()).isEqualTo(sourceCommit);
        assertThat(head(witness)).isEqualTo(base);
        String original = content(witness);
        for (Copy survivor : new Copy[]{preservedSource, preservedFork, witness}) {
            assertThat(get("/api/dsl/git/commit/" + base, survivor).required("dslText").textValue()).isEqualTo(original);
        }
    }

    void verifyTargetProjections() throws Exception {
        String sourceId = post("/api/repositories", Map.of("displayName", name, "slug", name,
                "visibility", "PRIVATE", "defaultBranch", "draft")).required("repositoryId").textValue();
        Copy source = new Copy(sourceId, "", "draft");
        String base = readiness(source).required("currentHeadCommit").textValue();
        Copy writer = copy(sourceId, "projection writer", base);
        String model = """
                meta {
                  language: "taxdsl";
                  version: "2.0";
                  namespace: "default";
                }
                element CR-1047 type CoreService {
                  title: "Shared service";
                }
                element BP-1327 type Process {
                  title: "Shared process";
                }
                relation CR-1047 SUPPORTS BP-1327 {
                  status: accepted;
                }
                """;
        String original = publishModel(writer, model);
        rebuildRelations(source, original, 1);
        JsonNode forked = post("/api/repositories/" + sourceId + "/forks", Map.of("displayName", name + " fork",
                "slug", name + "-fork", "visibility", "PRIVATE", "sourceBranch", source.branch));
        assertThat(forked.required("forkPointCommit").textValue()).isEqualTo(original);
        assertThat(forked.required("upstreamRepositoryId").textValue()).isEqualTo(sourceId);
        Copy fork = new Copy(forked.required("repositoryId").textValue(), "", source.branch);
        Copy witness = copy(sourceId, "projection witness", original);

        for (Copy selected : java.util.List.of(fork, witness)) {
            JsonNode fresh = readiness(selected);
            assertThat(fresh.required("currentHeadCommit").textValue()).isEqualTo(original);
            assertThat(fresh.required("readinessState").textValue()).isEqualTo("NOT_BUILT");
            rebuildRelations(selected, original, 1);
        }
        String advanced = publishModel(writer, model + """
                element CP-1023 type Capability {
                  title: "Source only";
                }
                relation CP-1023 REALIZES CR-1047 {
                  status: accepted;
                }
                """);
        assertThat(advanced).isNotEqualTo(original);
        JsonNode stale = readiness(source);
        assertThat(stale.required("currentHeadCommit").textValue()).isEqualTo(advanced);
        assertThat(stale.required("projectedCommit").textValue()).isEqualTo(original);
        assertThat(stale.required("readinessState").textValue()).isEqualTo("STALE");
        rebuildRelations(source, advanced, 2);
        request("POST", "/api/architecture/relations/projection/rebuild", fork, null, null, 412, advanced);
        assertReady(readiness(fork), original, 1);
        assertReady(readiness(witness), original, 1);

        String index = "/api/dsl/history/index";
        for (Copy selected : java.util.List.of(source, witness, fork)) {
            assertThat(request("PUT", index, selected, null, null, 200).required("indexed").asInt()).isPositive();
        }
        String element = "/api/dsl/history/element/CR-1047";
        JsonNode sourceRows = get(element, source), witnessRows = get(element, witness), forkRows = get(element, fork);
        assertHistoryContains(sourceRows, source, advanced);
        assertHistoryContains(witnessRows, witness, original);
        assertHistoryContains(forkRows, fork, original);
        assertThat(request("DELETE", index, fork, null, null, 200).required("purged").asInt()).isPositive();
        assertThat(get(element, fork).size()).isZero();
        assertThat(get(element, source)).isEqualTo(sourceRows);
        assertThat(get(element, witness)).isEqualTo(witnessRows);
        assertReady(readiness(source), advanced, 2);
        assertReady(readiness(witness), original, 1);
        assertReady(readiness(fork), original, 1);
    }

    private String publishModel(Copy copy, String model) throws Exception {
        var committed = request("POST", "/api/dsl/commit", copy, model, "text/plain;charset=UTF-8", 200);
        assertThat(committed.required("databaseBacked").booleanValue()).isTrue();
        assertThat(head(copy)).isEqualTo(committed.required("commitId").textValue());
        var published = json("POST", "/api/workspace/publish", copy, Map.of());
        assertThat(published.required("success").booleanValue()).isTrue();
        return published.required("mergeCommit").textValue();
    }

    private JsonNode readiness(Copy copy) throws Exception {
        var state = get("/api/architecture/relations/projection/readiness", copy);
        assertThat(state.required("repositoryId").textValue()).isEqualTo(copy.repository);
        assertThat(state.path("workspaceId").textValue()).isEqualTo(copy.workspace.isEmpty() ? null : copy.workspace);
        assertThat(state.required("branch").textValue()).isEqualTo(copy.branch);
        return state;
    }

    private void rebuildRelations(Copy copy, String commit, int count) throws Exception {
        assertReady(request("POST", "/api/architecture/relations/projection/rebuild", copy, null, null, 200, commit),
                commit, count);
        assertReady(readiness(copy), commit, count);
    }

    private static void assertReady(JsonNode state, String commit, int count) {
        assertThat(state.required("readinessState").textValue()).isEqualTo("READY");
        assertThat(state.required("currentHeadCommit").textValue()).isEqualTo(commit);
        assertThat(state.required("projectedCommit").textValue()).isEqualTo(commit);
        assertThat(state.required("relationCount").asInt()).isEqualTo(count);
    }

    private static void assertHistoryContains(JsonNode rows, Copy copy, String commit) {
        assertThat(rows.isArray()).isTrue();
        assertThat(rows.values()).anyMatch(row -> commit.equals(row.required("commitId").textValue()))
                .allSatisfy(row -> {
                    assertThat(row.required("repositoryId").textValue()).isEqualTo(copy.repository);
                    assertThat(row.path("workspaceId").textValue()).isEqualTo(copy.workspace.isEmpty() ? null : copy.workspace);
                });
    }

    private record Copy(String repository, String workspace, String branch) {}

    private Copy copy(String repositoryId, String purpose, String expected) throws Exception {
        JsonNode created = post("/api/repositories/" + repositoryId + "/workspaces",
                Map.of("displayName", name + " " + purpose, "sourceBranch", "draft"));
        assertThat(created.required("baseCommit").textValue()).isEqualTo(expected);
        assertThat(created.required("currentCommit").textValue()).isEqualTo(expected);
        assertThat(created.required("sourceRepositoryId").textValue()).isEqualTo(repositoryId);
        var copy = new Copy(repositoryId, created.required("workspaceId").textValue(), created.required("currentBranch").textValue());
        assertThat(head(copy)).isEqualTo(expected);
        return copy;
    }

    private String publish(Copy copy, String marker) throws Exception {
        String dsl = content(copy) + "\nelement HISTORY-OVERLAP type Capability {\n  title: \"" + marker + "\";\n}\n";
        var result = request("POST", "/api/dsl/commit", copy, dsl, "text/plain;charset=UTF-8", 200);
        assertThat(result.required("databaseBacked").booleanValue()).isTrue();
        String commit = result.required("commitId").textValue();
        assertThat(head(copy)).isEqualTo(commit);
        var published = json("POST", "/api/workspace/publish", copy, Map.of());
        assertThat(published.required("mergeCommit").textValue()).isEqualTo(commit);
        return commit;
    }

    private void archiveAndDelete(Copy removed, Copy survivor, String retained, String marker) throws Exception {
        String path = "/api/workspace/" + removed.workspace;
        assertThat(post(path + "/archive", Map.of()).required("archived").booleanValue()).isTrue();
        assertThat(head(survivor)).isEqualTo(retained);
        String before = content(survivor);
        assertThat(before).contains(marker);
        assertThat(request("DELETE", path, null, null, null, 200).required("deleted").textValue()).isEqualTo(removed.workspace);
        request("GET", path + "/info", null, null, null, 404);
        assertThat(head(survivor)).isEqualTo(retained);
        assertThat(content(survivor)).isEqualTo(before);
    }

    private String head(Copy copy) throws Exception {
        return get("/api/git/state", copy).required("headCommit").textValue();
    }

    private String content(Copy copy) throws Exception {
        return get("/api/dsl/git/head", copy).required("dslText").textValue();
    }

    private JsonNode post(String path, Object body) throws Exception {
        return json("POST", path, null, body);
    }

    private JsonNode get(String path, Copy copy) throws Exception {
        return request("GET", path, copy, null, null, 200);
    }

    private JsonNode json(String method, String path, Copy copy, Object body) throws Exception {
        return request(method, path, copy, JSON.writeValueAsString(body), "application/json", 200);
    }

    private JsonNode request(String method, String path, Copy copy, String body, String contentType, int expected) throws Exception {
        return request(method, path, copy, body, contentType, expected, null);
    }

    private JsonNode request(String method, String path, Copy copy, String body, String contentType,
                             int expected, String revision) throws Exception {
        var request = HttpRequest.newBuilder().timeout(Duration.ofSeconds(90))
                .header("Authorization", authorization).header("Accept", "application/json");
        if (revision != null) request.header("If-Match", "\"" + revision + "\"");
        if (copy != null) {
            path += "?repositoryId=" + copy.repository + "&workspaceId=" + copy.workspace
                    + "&workspaceScopeKey=" + (copy.workspace.isEmpty() ? "__shared__" : copy.workspace) + "&branch=" + copy.branch;
            request.header("X-Taxonomy-Workspace-Id", copy.workspace);
        }
        if (contentType != null) request.header("Content-Type", contentType);
        var payload = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        var response = client.send(request.uri(application.resolve(path)).method(method, payload).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s %s: %s", method, path, response.body()).isEqualTo(expected);
        return expected == 200 ? JSON.readTree(response.body()) : null;
    }

    @Override public void close() {
        client.close();
    }
}
