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
public final class RepositoryHistoryHttpContract implements AutoCloseable {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private final URI application;
    private final String authorization;
    private final java.util.function.Function<String, String> publicationEvidence;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String name = "history-" + UUID.randomUUID();

    public RepositoryHistoryHttpContract(URI application, String authorization) {
        this(application, authorization, workspace -> "");
    }

    RepositoryHistoryHttpContract(URI application, String authorization,
                                  java.util.function.Function<String, String> publicationEvidence) {
        this.publicationEvidence = publicationEvidence;
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

        Copy forkWriter = copy(fork.repository, "projection failure writer", original);
        // This source can be published, but its confidence is outside the projection's range.
        String unprojectable = publishModel(forkWriter, model.replace("status: accepted;",
                "status: accepted;\n  confidence: 2.0;"));
        String rebuild = "/api/architecture/relations/projection/rebuild";
        var failed = send("POST", rebuild, fork, null, null, unprojectable);
        assertThat(failed.statusCode()).as("rebuild for the fork: %s", failed.body()).isEqualTo(202);
        String operationId = failed.headers().firstValue("X-Taxonomy-Operation-Id").orElseThrow();
        JsonNode receipt = get("/api/architecture/relations/projection/operations/" + operationId, fork);
        assertThat(receipt.required("repositoryId").textValue()).isEqualTo(fork.repository);
        assertThat(receipt.required("sourceCommit").textValue()).isEqualTo(unprojectable);
        assertThat(receipt.required("phase").textValue()).isEqualTo("RETRYABLE");
        assertThat(receipt.required("reason").textValue()).isEqualTo("PROJECTION_INTERRUPTED");
        assertStale(readiness(fork), unprojectable, original);
        assertReady(readiness(source), advanced, 2);
        assertReady(readiness(witness), original, 1);
        rebuildRelations(source, advanced, 2);
        assertStale(readiness(fork), unprojectable, original);

        assertThat(request("DELETE", index, fork, null, null, 200).required("purged").asInt()).isPositive();
        assertThat(get(element, fork).size()).isZero();
        assertThat(get(element, source)).isEqualTo(sourceRows);
        assertThat(get(element, witness)).isEqualTo(witnessRows);
        assertReady(readiness(source), advanced, 2);
        assertReady(readiness(witness), original, 1);
        assertStale(readiness(fork), unprojectable, original);
    }

    public void verifyRefPolicy(javax.sql.DataSource database) throws Exception {
        String repository = post("/api/repositories", Map.of("displayName", name, "slug", name,
                "visibility", "PRIVATE", "defaultBranch", "draft")).required("repositoryId").textValue();
        var central = new Copy(repository, "", "draft");
        var working = copy(repository, "policy writer", head(central));
        json("POST", "/api/dsl/branches?name=policy-review&fromBranch=draft", working, Map.of());
        json("POST", "/api/dsl/branches?name=POLICY-REVIEW&fromBranch=draft", working, Map.of());
        var selected = new Copy(repository, working.workspace, "policy-review");
        var differentCase = new Copy(repository, working.workspace, "POLICY-REVIEW");
        String original = branchHead(working, selected.branch);
        String model = content(working) + "\nelement POLICY-SELECTED type Capability {\n  title: \"Reviewed policy\";\n}\n";
        String policyPath = "/api/repositories/" + repository + "/ref-policy";
        assertThat(get(policyPath, selected).required("revision").asLong()).isZero();
        var requested = new java.util.LinkedHashMap<String, Object>(Map.of("preventUpdates", true,
                "preventForce", true, "preventDelete", true, "rationale", "Reviewed branch policy"));
        request("PUT", policyPath, selected, JSON.writeValueAsString(requested), "application/json", 428);
        requested.put("expectedRevision", 0);
        String body = JSON.writeValueAsString(requested);
        HttpResponse<String> winner;
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var attempts = java.util.stream.IntStream.range(0, 2).mapToObj(index -> workers.submit(() -> {
                assertThat(start.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                return send("PUT", policyPath, selected, body, "application/json", null);
            })).toList();
            start.countDown();
            var responses = java.util.List.of(attempts.get(0).get(90, java.util.concurrent.TimeUnit.SECONDS),
                    attempts.get(1).get(90, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
            winner = responses.stream().filter(response -> response.statusCode() == 200).findFirst().orElseThrow();
        }
        var accepted = JSON.readTree(winner.body());
        assertThat(accepted.path("policy").path("revision").asLong()).isOne();
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(database);
        var evidence = jdbc.queryForMap("select actor_principal,detail from principal_access_audit where audit_id=?",
                accepted.required("auditId").textValue());
        assertThat(evidence.get("actor_principal").toString()).matches("[0-9a-f-]{36}");
        assertThat(evidence.get("detail").toString()).contains(repository, "Reviewed branch policy", "APPLIED");
        assertThat(jdbc.queryForObject("select revision from repository_ref_rule where repository_id=? and branch_name=? and workspace_id=?",
                Long.class, repository, selected.branch, working.workspace)).isOne();
        var separatePolicy = json("PUT", policyPath, differentCase, Map.of("expectedRevision", 0,
                "preventUpdates", false, "preventForce", true, "preventDelete", true,
                "rationale", "Independent exact branch spelling")).required("policy");
        assertThat(separatePolicy.required("branch").textValue()).isEqualTo(differentCase.branch);
        assertThat(separatePolicy.required("revision").asLong()).isOne();
        assertThat(get(policyPath, selected).required("preventUpdates").booleanValue()).isTrue();
        assertThat(get(policyPath, differentCase).required("preventUpdates").booleanValue()).isFalse();
        request("POST", "/api/dsl/commit", selected, model, "text/plain;charset=UTF-8", 403);
        assertThat(branchHead(working, selected.branch)).isEqualTo(original);
        assertThat(request("POST", "/api/dsl/commit", differentCase, model, "text/plain;charset=UTF-8", 200)
                .required("databaseBacked").booleanValue()).isTrue();
        assertThat(branchHead(working, selected.branch)).isEqualTo(original);
        String otherModel = content(working) + "\nelement POLICY-OTHER type Capability {\n  title: \"Other branch\";\n}\n";
        assertThat(request("POST", "/api/dsl/commit", working, otherModel, "text/plain;charset=UTF-8", 200)
                .required("databaseBacked").booleanValue()).isTrue();
        assertThat(branchHead(working, selected.branch)).isEqualTo(original);
        requested.put("expectedRevision", 1);
        requested.put("preventUpdates", false);
        assertThat(json("PUT", policyPath, selected, requested).path("policy").path("revision").asLong()).isEqualTo(2);
        var published = request("POST", "/api/dsl/commit", selected, model, "text/plain;charset=UTF-8", 200);
        assertThat(published.required("databaseBacked").booleanValue()).isTrue();
        assertThat(branchHead(working, selected.branch)).isEqualTo(published.required("commitId").textValue()).isNotEqualTo(original);
        // Deletion already holds the catalogue fence. The native policy check must reuse that
        // connection for metadata, including on SQL Server where a second reader self-blocks.
        String etag = get("/api/dsl/branch/deletion-preview?name=" + selected.branch, working)
                .required("etag").textValue();
        assertThat(etag).startsWith("\"").endsWith("\"");
        String confirmation = etag.substring(1, etag.length() - 1);
        request("DELETE", "/api/dsl/branch?name=" + selected.branch, working, null, null, 403, confirmation);
        assertThat(branchHead(working, selected.branch)).isEqualTo(published.required("commitId").textValue());
        requested.put("expectedRevision", 2);
        requested.put("preventDelete", false);
        assertThat(json("PUT", policyPath, selected, requested).path("policy").path("revision").asLong()).isEqualTo(3);
        request("DELETE", "/api/dsl/branch?name=" + selected.branch, working, null, null, 200, confirmation);
        assertThat(get("/api/dsl/branches", working).values()).extracting(row -> row.required("name").textValue())
                .doesNotContain(selected.branch).contains(working.branch, differentCase.branch);
    }

    private String branchHead(Copy copy, String branch) throws Exception {
        for (JsonNode candidate : get("/api/dsl/branches", copy).values()) {
            if (branch.equals(candidate.required("name").textValue()))
                return candidate.required("headCommitId").textValue();
        }
        throw new AssertionError("Expected exact Git branch " + branch);
    }

    private String publishModel(Copy copy, String model) throws Exception {
        var committed = request("POST", "/api/dsl/commit", copy, model, "text/plain;charset=UTF-8", 200);
        assertThat(committed.required("databaseBacked").booleanValue()).isTrue();
        assertThat(head(copy)).isEqualTo(committed.required("commitId").textValue());
        var published = publishWithEvidence(copy);
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

    private static void assertStale(JsonNode state, String head, String projected) {
        assertThat(state.required("readinessState").textValue()).isEqualTo("STALE");
        assertThat(state.required("currentHeadCommit").textValue()).isEqualTo(head);
        assertThat(state.required("projectedCommit").textValue()).isEqualTo(projected);
        assertThat(state.required("relationCount").asInt()).isZero();
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
        JsonNode published = publishWithEvidence(copy);
        assertThat(published.required("status").textValue()).isEqualTo("COMPLETED");
        String id = published.required("operationId").textValue();
        JsonNode receipt = get("/api/workspace/publications/" + id, copy);
        assertThat(receipt.required("repositoryId").textValue()).isEqualTo(copy.repository);
        assertThat(receipt.required("workspaceId").textValue()).isEqualTo(copy.workspace);
        assertThat(receipt.required("branch").textValue()).isEqualTo(copy.branch);
        assertThat(receipt.required("resultCommit").textValue()).isEqualTo(commit);
        assertThat(receipt.required("decisionCode").textValue()).isEqualTo("COMPLETED");
        assertThat(published.required("mergeCommit").textValue()).isEqualTo(commit);
        return commit;
    }

    private JsonNode publishWithEvidence(Copy copy) throws Exception {
        try {
            return json("POST", "/api/workspace/publish", copy, Map.of());
        } catch (AssertionError failedResponse) {
            try {
                String evidence = publicationEvidence.apply(copy.workspace);
                if (!evidence.isEmpty()) failedResponse.addSuppressed(new AssertionError(evidence));
            } catch (RuntimeException evidenceUnavailable) {
                failedResponse.addSuppressed(new AssertionError("Publication evidence unavailable: "
                        + evidenceUnavailable.getClass().getSimpleName()));
            }
            throw failedResponse;
        }
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
        var response = send(method, path, copy, body, contentType, revision);
        assertThat(response.statusCode()).as("%s %s in %s: %s", method, path, copy, response.body()).isEqualTo(expected);
        return expected == 200 ? JSON.readTree(response.body()) : null;
    }

    private HttpResponse<String> send(String method, String path, Copy copy, String body, String contentType,
                                      String revision) throws Exception {
        var request = HttpRequest.newBuilder().timeout(Duration.ofSeconds(90))
                .header("Authorization", authorization).header("Accept", "application/json");
        if (revision != null) request.header("If-Match", "\"" + revision + "\"");
        if (copy != null) {
            path += (path.contains("?") ? "&" : "?") + "repositoryId=" + copy.repository + "&workspaceId=" + copy.workspace
                    + "&workspaceScopeKey=" + (copy.workspace.isEmpty() ? "__shared__" : copy.workspace) + "&branch=" + copy.branch;
            request.header("X-Taxonomy-Workspace-Id", copy.workspace);
        }
        if (contentType != null) request.header("Content-Type", contentType);
        var payload = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        return client.send(request.uri(application.resolve(path)).method(method, payload).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Override public void close() {
        client.close();
    }
}
