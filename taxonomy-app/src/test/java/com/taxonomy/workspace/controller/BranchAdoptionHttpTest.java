package com.taxonomy.workspace.controller;

import com.taxonomy.security.model.PrincipalUserDetails;
import com.taxonomy.security.access.WorkspaceFetchAuthorityAdapter;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.security.service.UserManagementService;
import com.taxonomy.testsupport.LocalTestLogin;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.service.ArchitectureRepositoryProvisioningService;
import com.taxonomy.workspace.service.BranchAdoptionWorker;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:hsqldb:mem:branch-adoption-http;hsqldb.tx=mvcc;hsqldb.lob_compressed=true;hsqldb.lob_file_scale=1",
        "taxonomy.workspace.adoption-poll=3600000",
        "taxonomy.security.require-password-change=false"
})
@Tag("ci-app-shard-domain-integration")
class BranchAdoptionHttpTest {
    private static final String USER = "branch-adopter";
    private static final String PASSWORD = "Branch-Adoption-Fixture-72!";
    @LocalServerPort int port;
    @Autowired ApplicationContext application;
    @Autowired UserManagementService accounts;
    @Autowired ArchitectureRepositoryProvisioningService provisioning;
    @Autowired DslGitRepositoryFactory repositories;
    @Autowired BranchAdoptionWorker worker;
    @Autowired ObjectMapper json;
    @MockitoSpyBean WorkspaceFetchAuthorityAdapter actors;

    @Test
    void authenticatedRuleAppliesOrderedChangesAndExposesAnExactReviewableConflict() throws Exception {
        accounts.createUser(Map.of("username", USER, "roles", List.of("USER", "ARCHITECT")), PASSWORD, "fixture");
        String actor;
        String repository;
        try (var login = LocalTestLogin.openExisting(application, USER, PASSWORD)) {
            var principal = (PrincipalUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            actor = principal.principalId().value().toString();
            repository = provisioning.createRepository("Branch adoption", "branch-adoption", "",
                    RepositoryVisibility.PRIVATE, USER, "main").getRepositoryId();
        }
        var git = repositories.getCentralRepository(repository);
        String base = git.commitDsl("main", model("base", "base"), USER, "Base");
        git.createBranch("draft", "main");
        git.createBranch("untouched", "main");
        String draft = git.commitDsl("draft", model("mine", "base"), USER, "Local work");

        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            var off = request(client, repository, "", "GET", Map.of(), 200);
            assertThat(off.get("enabled").booleanValue()).isFalse();
            var on = request(client, repository, "", "PUT", Map.of("enabled", true, "expectedRevision", 0), 200);
            assertThat(on.get("sourceCommit").asString()).isEqualTo(base);
            request(client, repository, "", "PUT", Map.of("enabled", false, "expectedRevision", 0), 409);

            String first = git.commitDsl("main", model("base", "first"), USER, "Independent change");
            String second = git.commitDsl("main", model("base", "second"), USER, "Depends on first");
            String conflict = git.commitDsl("main", model("different", "second"), USER, "Overlaps local change");
            // Each scheduled call commits a distinct SQL transaction for selection, preparation or publication.
            for (int tick = 0; tick < 5; tick++) worker.checkEnabledRules();
            var prepared = request(client, repository, "", "GET", Map.of(), 200);
            assertThat(prepared.get("status").asString()).isEqualTo("PREPARED");
            assertThat(prepared.get("sourceCommit").asString()).isEqualTo(second);
            String competing = git.commitDsl("draft", model("mine", "first", "concurrent"), USER, "Concurrent edit");
            worker.checkEnabledRules();
            var stale = request(client, repository, "", "GET", Map.of(), 200);
            assertThat(stale.get("status").asString()).isEqualTo("PREREQUISITE");
            assertThat(git.getHeadCommit("draft")).isEqualTo(competing);
            var reviewed = request(client, repository, "/decision", "POST",
                    Map.of("action", "RETRY", "sourceCommit", second, "expectedRevision", stale.get("revision").asLong()), 200);
            assertThat(reviewed.get("status").asString()).isEqualTo("APPLIED");
            worker.checkEnabledRules();
            worker.checkEnabledRules();

            var stopped = request(client, repository, "", "GET", Map.of(), 200);
            assertThat(stopped.get("status").asString()).isEqualTo("CONFLICT");
            assertThat(stopped.get("sourceCommit").asString()).isEqualTo(conflict);
            assertThat(git.getDslAtHead("draft")).isEqualTo(model("mine", "second", "concurrent"));
            String kept = git.getHeadCommit("draft");
            long revision = stopped.get("revision").asLong();
            request(client, repository, "/decision", "POST",
                    Map.of("action", "SKIP", "sourceCommit", first, "expectedRevision", revision), 409);
            var skipped = request(client, repository, "/decision", "POST",
                    Map.of("action", "SKIP", "sourceCommit", conflict, "expectedRevision", revision), 200);
            assertThat(skipped.get("status").asString()).isEqualTo("SKIPPED");
            assertThat(git.getHeadCommit("draft")).isEqualTo(kept);
            assertThat(git.getHeadCommit("untouched")).isEqualTo(base);
            var disabled = request(client, repository, "", "PUT",
                    Map.of("enabled", false, "expectedRevision", skipped.get("revision").asLong()), 200);
            assertThat(disabled.get("enabled").booleanValue()).isFalse();
            var history = request(client, repository, "/history", "GET", Map.of(), 200);
            assertThat(history.valueStream().map(e -> e.get("status").asString()).toList())
                    .containsSubsequence("DISABLED", "SKIPPED", "CONFLICT", "APPLIED", "PREREQUISITE", "APPLIED", "ENABLED");
            var applied = history.valueStream().filter(e -> "APPLIED".equals(e.get("status").asString())).toList();
            assertThat(applied).extracting(e -> e.get("sourceCommit").asString()).containsExactly(second, first);
            assertThat(applied).extracting(e -> e.get("actor").asString()).containsOnly(actor);
            assertThat(applied.getLast().get("targetBefore").asString()).isEqualTo(draft);
            assertThat(applied.getFirst().get("targetAfter").asString()).isEqualTo(kept);
            request(client, repository, "", "PUT",
                    Map.of("enabled", true, "expectedRevision", disabled.get("revision").asLong()), 200);
            git.commitDsl("main", model("different", "third"), USER, "A future change");
            worker.checkEnabledRules();
            worker.checkEnabledRules();
            var ready = request(client, repository, "", "GET", Map.of(), 200);
            assertThat(ready.get("status").asString()).isEqualTo("PREPARED");
            var publicationReached = new java.util.concurrent.CountDownLatch(1);
            var allowPublication = new java.util.concurrent.CountDownLatch(1);
            var permissionChecks = new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(call -> {
                call.callRealMethod();
                if (permissionChecks.incrementAndGet() == 2) {
                    publicationReached.countDown();
                    assertThat(allowPublication.await(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                }
                return null;
            }).when(actors).checkPublication(any(), eq(USER), eq(RepositoryScope.CENTRAL_WRITE), eq(true));
            try (var threads = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var publishing = threads.submit(worker::checkEnabledRules);
                assertThat(publicationReached.await(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var stopping = threads.submit(() -> request(client, repository, "", "PUT",
                        Map.of("enabled", false, "expectedRevision", ready.get("revision").asLong()), 409));
                try {
                    assertThatThrownBy(() -> stopping.get(1, java.util.concurrent.TimeUnit.SECONDS))
                            .isInstanceOf(java.util.concurrent.TimeoutException.class);
                } finally {
                    allowPublication.countDown();
                }
                publishing.get(15, java.util.concurrent.TimeUnit.SECONDS);
                stopping.get(15, java.util.concurrent.TimeUnit.SECONDS);
            }
            var afterPublication = request(client, repository, "", "GET", Map.of(), 200);
            request(client, repository, "", "PUT",
                    Map.of("enabled", false, "expectedRevision", afterPublication.get("revision").asLong()), 200);
            String stoppedHead = git.getHeadCommit("draft");
            git.commitDsl("main", model("different", "fourth"), USER, "After disabled");
            worker.checkEnabledRules();
            assertThat(git.getHeadCommit("draft")).isEqualTo(stoppedHead);
        }
    }

    private JsonNode request(HttpClient client, String repository, String suffix, String method,
            Map<String, ?> body, int expected) throws Exception {
        URI uri = URI.create("http://localhost:" + port + "/api/workspace/adoption-rule" + suffix
                + "?repositoryId=" + repository + "&workspaceId=&workspaceScopeKey=__shared__&branch=draft");
        String login = Base64.getEncoder().encodeToString((USER + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Basic " + login).header("Accept", "application/json")
                .header("Content-Type", "application/json").header("X-Taxonomy-Workspace-Id", "")
                .method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s %s — %s", method, suffix, response.body()).isEqualTo(expected);
        if (expected == 200) {
            assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));
            return json.readTree(response.body());
        }
        return null;
    }

    private static String model(String a, String b) {
        return model(a, b, "base");
    }

    private static String model(String a, String b, String c) {
        return "element A type System {\n  title: \"" + a + "\";\n}\n"
                + "element B type System {\n  title: \"" + b + "\";\n}\n"
                + "element C type System {\n  title: \"" + c + "\";\n}\n";
    }
}
