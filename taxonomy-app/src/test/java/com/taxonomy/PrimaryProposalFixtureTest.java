package com.taxonomy;

import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.dsl.export.TaxDslExportService;
import com.taxonomy.dsl.validation.RelationMetamodel;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.service.ArchitectureRepositoryProvisioningService;
import com.taxonomy.workspace.service.RepositoryWorkspaceService;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/** The native driver's exact authored tuples must pass the productive metamodel and HTTP review path. */
@SpringBootTest(properties = {
        "taxonomy.embedding.enabled=false",
        "taxonomy.git.bootstrap=false",
        "spring.datasource.url=jdbc:hsqldb:mem:primary-proposal-fixture;shutdown=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("hsqldb")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PrimaryProposalFixtureTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Autowired private MockMvc mvc;
    @Autowired private TaxonomyNodeRepository nodes;
    @Autowired private TaxDslExportService catalogue;
    @Autowired private ArchitectureRepositoryProvisioningService provisioning;
    @Autowired private RepositoryWorkspaceService workspaces;
    @Autowired private DslGitRepositoryFactory git;

    static Stream<Arguments> authoredCandidates() throws Exception {
        return Arrays.stream(candidates()).map(tuple -> Arguments.of(tuple[0], tuple[1], tuple[2]));
    }

    @ParameterizedTest(name = "{0} {2} {1} is a legal authored native fixture")
    @MethodSource("authoredCandidates")
    void everyNativeCandidateMatchesTheRealCatalogueAndSharedMetamodel(String source, String target, String type) {
        var from = nodes.findByCode(source).orElseThrow();
        var to = nodes.findByCode(target).orElseThrow();
        assertThat(from.getLevel()).as("Source must be a concrete catalogue node, not a virtual category").isGreaterThan(0);
        assertThat(to.getLevel()).as("Target must be a concrete catalogue node, not a virtual category").isGreaterThan(0);
        assertThat(RelationMetamodel.isCompatible(from.getTaxonomyRoot(), to.getTaxonomyRoot(), type))
                .as("Native fixture %s %s %s must satisfy metamodel %s", source, type, target, RelationMetamodel.VERSION)
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ARCHITECT", "ADMIN"})
    void realRoleCanCreateAndReviewAtLeastFourDistinctNativeProposals(String role) throws Exception {
        var session = new Session(role);
        // Each role owns a fresh committed catalogue baseline and working copy.
        // Its HTTP contract is independent of the JVM-wide default bootstrap guard.
        session.json("GET", "/api/context/current", null, Map.of());
        assertThat(session.json("POST", "/api/workspace/provision", null, Map.of()).get("status")).isEqualTo("READY");
        var readiness = session.json("GET", "/api/architecture/relations/projection/readiness", null, Map.of());
        assertThat(readiness.get("workspaceId")).isNotNull();
        assertThat(readiness.get("currentHeadCommit")).isInstanceOf(String.class);
        session.scope = "?repositoryId=" + readiness.get("repositoryId") + "&workspaceId=" + readiness.get("workspaceId")
                + "&branch=" + readiness.get("branch");
        if (!"READY".equals(readiness.get("readinessState"))) {
            var rebuilt = session.json("POST", "/api/architecture/relations/projection/rebuild", null,
                    Map.of("If-Match", "\"" + readiness.get("currentHeadCommit") + "\""));
            assertThat(rebuilt.get("readinessState")).isEqualTo("READY");
        }

        var ids = new LinkedHashSet<Long>();
        for (String[] candidate : candidates()) {
            var proposal = session.json("POST", "/api/proposals/from-hypothesis", Map.of(
                    "sourceCode", candidate[0], "targetCode", candidate[1], "relationType", candidate[2],
                    "confidence", 0.73, "rationale", "Primary workflow " + String.join("-", candidate)), Map.of());
            assertThat(proposal.get("status")).isEqualTo("PENDING");
            assertThat(proposal.get("sourceCode")).isEqualTo(candidate[0]);
            assertThat(proposal.get("targetCode")).isEqualTo(candidate[1]);
            assertThat(proposal.get("relationType")).isEqualTo(candidate[2]);
            assertThat(ids.add(((Number) proposal.get("id")).longValue())).isTrue();
        }
        assertThat(ids).hasSizeGreaterThanOrEqualTo(4);
        var ordered = new ArrayList<>(ids);
        assertThat(session.review("/api/proposals/" + ordered.get(0) + "/accept", null).get("status")).isEqualTo("ACCEPTED");
        assertThat(session.review("/api/proposals/" + ordered.get(0) + "/revert", null).get("status")).isEqualTo("PENDING");
        assertThat(session.review("/api/proposals/" + ordered.get(1) + "/reject", null).get("status")).isEqualTo("REJECTED");
        var pending = JSON.readTree(session.call("GET", "/api/proposals/pending", null, Map.of()).getContentAsString());
        var pendingIds = new ArrayList<Long>();
        for (var proposal : pending) pendingIds.add(proposal.get("id").asLong());
        assertThat(pendingIds).hasSizeGreaterThanOrEqualTo(2).doesNotContain(ordered.get(1));
        var bulk = session.review("/api/proposals/bulk", Map.of("ids", pendingIds, "action", "accept"));
        assertThat(bulk.get("complete")).isEqualTo(true);
        assertThat(((Number) bulk.get("projected")).intValue()).isEqualTo(pendingIds.size());
        assertThat(((Number) bulk.get("failed")).intValue()).isZero();
        assertThat(JSON.readTree(session.call("GET", "/api/proposals/pending", null, Map.of()).getContentAsString()).size()).isZero();
    }

    private final class Session {
        private final String role;
        private final String username = "native-proposal-" + UUID.randomUUID();
        private String scope;

        private Session(String role) throws Exception {
            this.role = role;
            var source = provisioning.createRepository("Native proposal baseline", username,
                    "Exact catalogue baseline for proposal review", RepositoryVisibility.PRIVATE, username, "main");
            git.getCentralRepository(source.getRepositoryId()).commitDsl("main", catalogue.exportAll("default"),
                    username, "Native proposal catalogue baseline");
            var workspace = workspaces.createWorkingCopy(username, source.getRepositoryId(), "main",
                    "Native proposal review " + role, "Review lifecycle fixture");
            scope = "?repositoryId=" + source.getRepositoryId() + "&workspaceId=" + workspace.getWorkspaceId()
                    + "&branch=" + workspace.getCurrentBranch();
        }

        private MockHttpServletResponse call(String method, String path, Object body, Map<String, String> headers) throws Exception {
            String[] roles = role.equals("ADMIN") ? new String[]{"USER", "ARCHITECT", "ADMIN"} : new String[]{"USER", "ARCHITECT"};
            var input = request(HttpMethod.valueOf(method), path + scope).with(user(username).roles(roles));
            headers.forEach(input::header);
            if (body != null) input.contentType("application/json").content(JSON.writeValueAsString(body));
            var response = mvc.perform(input).andReturn().getResponse();
            assertThat(response.getStatus()).as("%s %s %s -> HTTP %s body=%s", role, method, path,
                    response.getStatus(), response.getContentAsString()).isBetween(200, 299);
            return response;
        }

        private Map<?, ?> json(String method, String path, Object body, Map<String, String> headers) throws Exception {
            return JSON.readValue(call(method, path, body, headers).getContentAsString(), Map.class);
        }

        private Map<?, ?> review(String path, Object body) throws Exception {
            String head = call("GET", "/api/proposals/head", null, Map.of()).getHeader("ETag");
            assertThat(head).matches("\"[a-f0-9]{40}\"");
            return json("POST", path, body, Map.of("If-Match", head, "Idempotency-Key", UUID.randomUUID().toString()));
        }
    }

    private static String[][] candidates() throws Exception {
        Path current = Path.of(System.getProperty("maven.multiModuleProjectDirectory", ".")).toAbsolutePath().normalize();
        while (current != null) {
            Path fixture = current.resolve(".github/fixtures/ui-primary-proposals.json");
            if (Files.isRegularFile(fixture)) return JSON.readValue(Files.readString(fixture), String[][].class);
            current = current.getParent();
        }
        throw new IllegalStateException("Native proposal fixture source was not found");
    }
}
