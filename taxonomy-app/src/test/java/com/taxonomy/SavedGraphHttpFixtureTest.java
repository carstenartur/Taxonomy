package com.taxonomy;

import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.ArchitectureRepositoryProvisioningService;
import com.taxonomy.workspace.service.RepositoryWorkspaceService;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceManager;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises the same screenshot fixture against real controllers, repository routing and Git storage. */
@SpringBootTest(properties = {
        "taxonomy.features.multi-repository-api.enabled=true",
        "taxonomy.admin-password=local-graph-fixture-password",
        "spring.datasource.url=jdbc:hsqldb:mem:saved-graph-http-fixture;shutdown=true"
})
@ActiveProfiles("hsqldb")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@WithUserDetails(SavedGraphHttpFixtureTest.USER)
class SavedGraphHttpFixtureTest {
    static final String USER = "admin";
    private static final String DSL = """
            meta {
              language: "taxdsl";
              version: "2.0";
              namespace: "default";
            }
            element CR-1047 type CoreService {
              title: "Real catalogue service";
            }
            element BP-1327 type Process {
              title: "Real catalogue process";
            }
            relation CR-1047 SUPPORTS BP-1327 {
              status: accepted;
            }
            """;

    @Autowired private MockMvc mvc;
    @Autowired private ArchitectureRepositoryProvisioningService provisioning;
    @Autowired private RepositoryWorkspaceService workspaces;
    @Autowired private WorkspaceManager workspaceManager;
    @Autowired private SystemRepositoryService repositories;
    @Autowired private RepositoryMembershipService membership;
    @Autowired private DslGitRepositoryFactory git;
    @Autowired private UserWorkspaceRepository workspaceRepository;
    @Autowired private DataSource dataSource;
    private final ObjectMapper json = new ObjectMapper();
    private UserWorkspace active;

    @BeforeEach
    void useARealActivePersonalWorkspace() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:hsqldb:mem:");
        }
        var source = provisioning.createRepository("Unrelated personal source", "graph-active-" + UUID.randomUUID(),
                "Tests central fixture isolation", RepositoryVisibility.PRIVATE, USER, "main");
        active = workspaces.createWorkingCopy(USER, source.getRepositoryId(), "main", "Active personal model " + UUID.randomUUID(), "Unrelated");
        workspaceManager.switchWorkspace(USER, active.getWorkspaceId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ARCHITECT", "ADMIN"})
    void publishedFixtureIsReadableInTheExactCentralScopeByTheOriginalBrowserRole(String role) throws Exception {
        String branch = "graph-fixture-" + UUID.randomUUID();
        String privateHead = git.openWorkspaceRepository(active.getWorkspaceId()).getHeadCommit("draft");
        var publishedCommit = new AtomicReference<String>();
        var saved = SavedGraphHttpFixture.create((url, method, type, body, headers) -> {
            Map<?, ?> response = http(url, method, type, body, headers);
            if (url.startsWith("/api/workspace/publish")) publishedCommit.set((String) response.get("mergeCommit"));
            return response;
        }, branch, DSL, 1);
        try {
            assertThat(saved.repositoryId()).isNotEqualTo(repositories.getPrimaryRepository().getRepositoryId())
                    .isNotEqualTo(active.getSourceRepositoryId());
            assertThat(repositories.getRepository(saved.repositoryId()).getVisibility()).isEqualTo(RepositoryVisibility.PUBLIC);
            assertThat(saved.commitId()).isEqualTo(publishedCommit.get());
            assertThat(git.getCentralRepository(saved.repositoryId()).getHeadCommit(branch)).isEqualTo(saved.commitId());
            assertThat(git.openWorkspaceRepository(active.getWorkspaceId()).getHeadCommit(branch)).isNull();
            assertThat(git.openWorkspaceRepository(active.getWorkspaceId()).getHeadCommit("draft")).isEqualTo(privateHead);
            var response = mvc.perform(request(HttpMethod.GET, URI.create("/api/graph/node/BP-1327/upstream?"
                            + saved.scope() + "&commitId=" + saved.commitId()))
                            .with(user("graph-fixture-browser").roles(role)))
                    .andExpect(status().isOk()).andReturn().getResponse();
            Map<?, ?> graph = json.readValue(response.getContentAsString(), Map.class);
            Map<?, ?> evidence = (Map<?, ?>) graph.get("evidence");
            assertThat(evidence.get("commitId")).isEqualTo(saved.commitId());
            assertThat(evidence.get("repositoryId")).isEqualTo(saved.repositoryId());
            assertThat(evidence.get("workspaceScopeKey")).isEqualTo("__shared__");
            assertThat(evidence.get("branch")).isEqualTo(branch);
            assertThat(json.writeValueAsString(graph.get("neighbors"))).contains("CR-1047");
            assertThat(json.writeValueAsString(graph.get("traversedRelationships"))).contains("accepted");
        } finally {
            SavedGraphHttpFixture.remove(this::http, saved);
        }
        assertThat(workspaceRepository.findByWorkspaceId(saved.workspaceId())).isEmpty();
        assertThat(workspaceManager.findActiveWorkspace(USER).getWorkspaceId()).isEqualTo(active.getWorkspaceId());
        assertThat(git.getCentralRepository(saved.repositoryId()).getHeadCommit(branch)).isEqualTo(saved.commitId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"projection", "commit-response", "publish-response"})
    void failedSetupRemovesOnlyItsOwnedPrivateWorkspace(String failurePoint) throws Exception {
        String branch = "graph-fixture-" + UUID.randomUUID();
        String privateHead = git.openWorkspaceRepository(active.getWorkspaceId()).commitDsl(branch, DSL, USER, "Unrelated branch");
        var fixtureWorkspace = new AtomicReference<String>();
        var failure = catchThrowable(() -> SavedGraphHttpFixture.create((url, method, type, body, headers) -> {
            if (failurePoint.equals("projection") && url.startsWith("/api/architecture/relations/projection/rebuild")) {
                return http(url, method, type, body, Map.of("X-Taxonomy-Workspace-Id", "", "If-Match", "\"" + "1".repeat(40) + "\""));
            }
            Map<?, ?> response = http(url, method, type, body, headers);
            if (url.startsWith("/api/architecture/editor/workspaces")) fixtureWorkspace.set((String) response.get("workspaceId"));
            if (failurePoint.equals("commit-response") && url.startsWith("/api/dsl/commit")) {
                throw new IllegalStateException("Commit response lost after persistence");
            }
            if (failurePoint.equals("publish-response") && url.startsWith("/api/workspace/publish")) {
                throw new IllegalStateException("Publication response lost after persistence");
            }
            return response;
        }, branch, DSL, 1));
        if (failurePoint.equals("projection")) assertThat(failure).isInstanceOf(AssertionError.class).hasMessageContaining("412");
        else assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessageContaining("response lost after persistence");
        assertThat(failure.getSuppressed()).isEmpty();
        assertThat(fixtureWorkspace.get()).isNotBlank();
        assertThat(workspaceRepository.findByWorkspaceId(fixtureWorkspace.get())).isEmpty();
        assertThat(git.getCentralRepository(repositories.getPrimaryRepository().getRepositoryId()).getHeadCommit(branch)).isNull();
        assertThat(git.openWorkspaceRepository(active.getWorkspaceId()).getHeadCommit(branch)).isEqualTo(privateHead);
        assertThat(workspaceManager.findActiveWorkspace(USER).getWorkspaceId()).isEqualTo(active.getWorkspaceId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ARCHITECT"})
    void aReaderCannotTurnACentralReadSelectionIntoAWrite(String role) throws Exception {
        String branch = "graph-fixture-" + UUID.randomUUID();
        String repositoryId = publicRepositoryWithoutReaderMembership();
        try {
            mvc.perform(request(HttpMethod.POST, URI.create("/api/dsl/commit?workspaceId=&repositoryId="
                            + repositoryId + "&branch=" + branch))
                            .with(user("graph-fixture-reader").roles(role))
                            .contentType("text/plain").content(DSL))
                    .andExpect(status().isForbidden());
            assertThat(git.getCentralRepository(repositoryId).getHeadCommit(branch)).isNull();
        } finally {
            git.getCentralRepository(repositoryId).deleteBranch(branch);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ARCHITECT"})
    void aReaderCannotDeleteACentralBranch(String role) throws Exception {
        String branch = "graph-delete-" + UUID.randomUUID();
        String repositoryId = publicRepositoryWithoutReaderMembership();
        var central = git.getCentralRepository(repositoryId);
        String head = central.commitDsl(branch, DSL, USER, "Published model");
        try {
            mvc.perform(request(HttpMethod.DELETE, URI.create("/api/dsl/branch?workspaceId=&repositoryId="
                            + repositoryId + "&branch=" + branch + "&name=" + branch))
                            .with(user("graph-fixture-reader").roles(role)))
                    .andExpect(status().isForbidden());
            assertThat(central.getHeadCommit(branch)).isEqualTo(head);
        } finally {
            central.deleteBranch(branch);
        }
    }

    private String publicRepositoryWithoutReaderMembership() {
        var repository = provisioning.createRepository("Published read-only fixture", "graph-reader-" + UUID.randomUUID(),
                "Reader has visibility without repository write authority", RepositoryVisibility.PUBLIC, USER, "main");
        assertThat(membership.canRead(repository, "graph-fixture-reader")).isTrue();
        assertThat(membership.canMaintain(repository, "graph-fixture-reader")).isFalse();
        assertThat(membership.effectiveRole(repository, "graph-fixture-reader")).isEmpty();
        return repository.getRepositoryId();
    }

    private Map<?, ?> http(String url, String method, String type, String body, Map<String, String> headers) {
        try {
            var input = request(HttpMethod.valueOf(method), URI.create(url));
            headers.forEach(input::header);
            if (body != null) input.contentType(type).content(body);
            var response = mvc.perform(input).andReturn().getResponse();
            assertThat(response.getStatus()).as("%s %s: %s", method, url, response.getContentAsString()).isBetween(200, 299);
            return json.readValue(response.getContentAsString(), Map.class);
        } catch (Exception failure) {
            throw new IllegalStateException("Fixture HTTP request failed: " + method + " " + url, failure);
        }
    }
}
