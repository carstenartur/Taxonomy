package com.taxonomy;

import com.taxonomy.dto.GraphSearchResult;
import com.taxonomy.relations.service.GraphSearchService;
import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.repository.SystemRepositoryRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceContextResolver;
import com.taxonomy.workspace.service.WorkspaceManager;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for the graph-semantic search endpoint introduced as part of the
 * Hibernate Search migration.
 *
 * <p>Embeddings are explicitly disabled in this fixture. Unavailable graph
 * searches fail explicitly; zero requested results remain a valid empty result.</p>
 */
@SpringBootTest(properties = "embedding.enabled=false")
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class GraphSearchTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GraphSearchService graphSearchService;

    @Autowired
    private LocalEmbeddingService embeddingService;

    @Autowired private UserWorkspaceRepository workspaceRows;
    @Autowired private SystemRepositoryRepository repositoryRows;
    @Autowired private WorkspaceManager workspaceManager;
    @Autowired private SystemRepositoryService repositories;
    @Autowired private WorkspaceContextResolver contexts;
    @Autowired private DslGitRepositoryFactory git;

    @AfterEach
    void evictGraphReadNavigation() {
        List.of("graph-reader-central", "graph-admin-central", "graph-reader-ready", "graph-user-central",
                "graph-user-unready", "graph-reader-unready")
                .forEach(workspaceManager::evictWorkspace);
    }

    @Test
    void graphSearchDoesNotFabricateCompletedResultsWhenModelIsUnavailable() {
        assertThat(embeddingService.isAvailable()).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> graphSearchService.graphSearch(
                "satellite communications", 10, RepositoryContext.centralRead("fixture-repository", "draft", "system")))
                .isInstanceOf(com.taxonomy.error.SearchUnavailableException.class);
    }

    @Test
    void zeroRequestedResultsRetainAllRequiredGraphFields() {
        GraphSearchResult result = graphSearchService.graphSearch(
                "business process management", 0, primaryReadContext());
        assertThat(result.getMatchedNodes()).isNotNull();
        assertThat(result.getRelationCountByRoot()).isNotNull();
        assertThat(result.getTopRelationTypes()).isNotNull();
        assertThat(result.getSummary()).isNotNull();
    }

    @Test
    void zeroRequestedResultsExplainThatNoSearchWasRequested() {
        GraphSearchResult result = graphSearchService.graphSearch("anything", 0, primaryReadContext());
        assertThat(result.getSummary()).isEqualTo("No graph search results were requested.");
        assertThat(result.getMatchedNodes()).isEmpty();
    }

    @Test
    void negativeRequestedResultsRemainEmptyWithoutInference() {
        GraphSearchResult result = graphSearchService.graphSearch("network services", -1, primaryReadContext());
        assertThat(result.getMatchedNodes()).isEmpty();
        assertThat(result.getRelationCountByRoot()).isEmpty();
    }

    @Test
    void graphSearchEndpointReturnsBadRequestForBlankQuery() throws Exception {
        mockMvc.perform(get("/api/search/graph").param("q", "").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void graphSearchEndpointReportsUnavailableModelForValidQuery() throws Exception {
        mockMvc.perform(get("/api/search/graph").param("q", "communications")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.matchedNodes").doesNotExist());
    }

    @Test
    void graphSearchEndpointFailsClosedWithDefaultMaxResults() throws Exception {
        mockMvc.perform(get("/api/search/graph").param("q", "satellite")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    void graphSearchEndpointRespectsMaxResultsParameter() throws Exception {
        mockMvc.perform(get("/api/search/graph").param("q", "BP").param("maxResults", "5")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.matchedNodes").doesNotExist());
    }

    @Test
    void graphSearchEndpointRequiresQueryParam() throws Exception {
        mockMvc.perform(get("/api/search/graph").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }
    @Test
    @WithMockUser(username = "graph-reader-central", roles = "READER")
    void readerCanSearchCentralSourceWithoutWorkspaceAllocation() throws Exception {
        mockMvc.perform(get("/api/search/graph").param("q", "communications").param("maxResults", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedNodes").isEmpty());
        assertNoWorkspaceAllocation("graph-reader-central");
    }

    @Test
    @WithMockUser(username = "graph-admin-central", roles = "ADMIN")
    void administratorAloneCanSearchCentralSourceWithoutWorkspaceAllocation() throws Exception {
        mockMvc.perform(get("/api/search/graph").param("q", "communications").param("maxResults", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedNodes").isEmpty());
        assertNoWorkspaceAllocation("graph-admin-central");
    }

    @Test
    @Transactional
    @WithMockUser(username = "graph-user-central", roles = "USER")
    void graphReadDoesNotProvisionAWorkspaceForAnAuthor() throws Exception {
        mockMvc.perform(get("/api/search/graph").param("q", "communications").param("maxResults", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedNodes").isEmpty());
        assertNoWorkspaceAllocation("graph-user-central");
    }

    @Test
    @Transactional
    @WithMockUser(username = "graph-user-unready", roles = "USER")
    void graphReadDoesNotProvisionAnAuthorsExistingDefault() throws Exception {
        var workspace = saveUnprovisionedDefault("graph-user-unready");
        assertThat(workspaceManager.mayProvisionDefaultWorkspace("graph-user-unready")).isTrue();

        var response = mockMvc.perform(get("/api/search/graph")
                .param("q", "communications").param("maxResults", "0"));

        assertStillUnprovisioned(workspace.getWorkspaceId());
        response.andExpect(status().isConflict());
    }

    @Test
    @Transactional
    @WithMockUser(username = "graph-reader-unready", roles = "READER")
    void readerWithAnUnreadyDefaultCanSearchCentralUnlessExplicitlyPinned() throws Exception {
        var workspace = saveUnprovisionedDefault("graph-reader-unready");
        assertThat(workspaceManager.mayProvisionDefaultWorkspace("graph-reader-unready")).isFalse();

        mockMvc.perform(get("/api/search/graph").param("q", "communications").param("maxResults", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedNodes").isEmpty());
        mockMvc.perform(get("/api/search/graph").param("q", "communications").param("maxResults", "0")
                        .header(WorkspaceContextResolver.WORKSPACE_HEADER, workspace.getWorkspaceId()))
                .andExpect(status().isConflict());
        assertStillUnprovisioned(workspace.getWorkspaceId());
    }

    @Test
    @Transactional
    @WithMockUser(username = "graph-reader-central", roles = "READER")
    void graphReadsRejectMissingForeignAndArchivedWorkspacePins() throws Exception {
        var foreign = saveUnprovisionedDefault("graph-foreign-owner");
        var archived = saveUnprovisionedDefault("graph-reader-central");
        archived.setArchived(true);
        workspaceRows.saveAndFlush(archived);

        for (String pin : List.of("graph-missing-workspace", foreign.getWorkspaceId(), archived.getWorkspaceId())) {
            mockMvc.perform(get("/api/search/graph").param("q", "communications").param("maxResults", "0")
                            .header(WorkspaceContextResolver.WORKSPACE_HEADER, pin))
                    .andExpect(status().isForbidden());
        }

        assertStillUnprovisioned(foreign.getWorkspaceId());
        assertStillUnprovisioned(archived.getWorkspaceId());
    }

    @Test
    @Transactional
    @WithMockUser(username = "graph-reader-ready", roles = "READER")
    void readerRetainsExistingReadyWorkingCopyForSearch() throws Exception {
        var workspace = new UserWorkspace();
        workspace.setUsername("graph-reader-ready");
        workspace.setWorkspaceId("graph-reader-ready-existing");
        workspace.setDisplayName("Existing graph workspace");
        workspace.setSourceRepositoryId(repositories.getPrimaryRepository().getRepositoryId());
        workspace.setProvisioningStatus(WorkspaceProvisioningStatus.READY);
        workspace.setCreatedAt(Instant.now());
        workspaceRows.saveAndFlush(workspace);

        mockMvc.perform(get("/api/search/graph").param("q", "communications"))
                .andExpect(status().isServiceUnavailable());
        assertThat(contexts.resolveRepositoryContextForUser("graph-reader-ready").scope())
                .isEqualTo(RepositoryScope.WORKSPACE);
        assertThat(workspaceRows.findByUsernameAndSharedFalse("graph-reader-ready").orElseThrow().getWorkspaceId())
                .isEqualTo(workspace.getWorkspaceId());
        assertThat(workspace.getProvisioningStatus()).isEqualTo(WorkspaceProvisioningStatus.READY);
        assertThat(workspace.getCurrentCommit()).isNull();
    }

    @Test
    @WithMockUser(username = "graph-source-reader", roles = "READER")
    void graphSearchRequiresAnUnambiguousRepositoryAndValidatesTheSelectedRepresentation() throws Exception {
        var row = repositories.createCentralRepository("Graph reader repository", "search-" + UUID.randomUUID(),
                null, RepositoryVisibility.PUBLIC, "graph-source-owner", "main");
        String id = row.getRepositoryId();
        try {
            repositories.markProvisioningReady(id);
            var primary = repositories.getPrimaryRepository();
            String head = git.getCentralRepository(primary.getRepositoryId()).getHeadCommit(primary.getDefaultBranch());
            var selected = get("/api/search/graph").param("q", "care").param("maxResults", "0")
                    .param("repositoryId", primary.getRepositoryId()).param("workspaceId", "")
                    .param("branch", primary.getDefaultBranch());

            mockMvc.perform(get("/api/search/graph").param("q", "care").param("maxResults", "0"))
                    .andExpect(status().isConflict());
            mockMvc.perform(get("/api/search/graph/readiness")).andExpect(status().isConflict());
            mockMvc.perform(get("/api/search/graph/readiness").param("repositoryId", primary.getRepositoryId())
                            .param("workspaceId", "").param("branch", primary.getDefaultBranch()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(false))
                    .andExpect(jsonPath("$.vectorSource").value("GLOBAL_INDEX")).andExpect(jsonPath("$.sourceCommit").value(head));
            var response = mockMvc.perform(selected)
                    .andExpect(status().isOk()).andExpect(jsonPath("$.source.repositoryId").value(primary.getRepositoryId()))
                    .andExpect(jsonPath("$.source.branch").value(primary.getDefaultBranch())).andExpect(jsonPath("$.source.sourceCommit").value(head))
                    .andExpect(jsonPath("$.source.vectorSource").value("NO_INFERENCE"))
                    .andExpect(jsonPath("$.source.generation").doesNotExist())
                    .andExpect(header().string("X-Taxonomy-Commit", head))
                    .andExpect(header().string("X-Taxonomy-Source-State", "READY")).andReturn().getResponse();
            String etag = response.getHeader("ETag");
            assertThat(etag).isNotBlank();
            mockMvc.perform(selected.header("If-None-Match", etag)).andExpect(status().isNotModified());
            mockMvc.perform(get("/api/search/graph").param("q", "care").param("maxResults", "0")
                            .param("repositoryId", id).param("workspaceId", "").param("branch", "main")
                            .header("If-None-Match", etag))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.matchedNodes").doesNotExist());
            mockMvc.perform(selected.param("commitId", "0".repeat(40)))
                    .andExpect(status().isConflict()).andExpect(header().string("X-Taxonomy-Source-State", "STALE"))
                    .andExpect(header().string("X-Taxonomy-Commit", head)).andExpect(content().string(""));
            assertThat(workspaceRows.findByUsernameAndSharedFalse("graph-source-reader")).isEmpty();
        } finally {
            repositoryRows.deleteById(row.getId());
        }
    }

    private RepositoryContext primaryReadContext() {
        var primary = repositories.getPrimaryRepository();
        return RepositoryContext.centralRead(primary.getRepositoryId(), primary.getDefaultBranch(), "system");
    }

    private void assertNoWorkspaceAllocation(String username) {
        assertThat(workspaceRows.findByUsernameAndSharedFalse(username)).isEmpty();
        assertThat(workspaceManager.getWorkspace(username)).isNull();
        assertThat(contexts.resolveRepositoryContextForUser(username).scope())
                .isEqualTo(RepositoryScope.CENTRAL_READ);
    }

    private UserWorkspace saveUnprovisionedDefault(String username) throws Exception {
        var primary = repositories.getPrimaryRepository();
        var branch = primary.getDefaultBranch();
        assertThat(git.getCentralRepository(primary.getRepositoryId()).getHeadCommit(branch))
                .as("The primary checkpoint is ready for a real workspace allocation")
                .isNotBlank();
        var workspace = new UserWorkspace();
        workspace.setUsername(username);
        workspace.setWorkspaceId(username + "-default");
        workspace.setDisplayName("Unprovisioned graph workspace");
        workspace.setDefault(true);
        workspace.setCurrentBranch(branch);
        workspace.setSourceRepositoryId(primary.getRepositoryId());
        workspace.setSourceBranch(branch);
        workspace.setProvisioningStatus(WorkspaceProvisioningStatus.NOT_PROVISIONED);
        workspace.setCreatedAt(Instant.now());
        return workspaceRows.saveAndFlush(workspace);
    }

    private void assertStillUnprovisioned(String workspaceId) {
        var workspace = workspaceRows.findByWorkspaceId(workspaceId).orElseThrow();
        assertThat(workspace.getProvisioningStatus()).isEqualTo(WorkspaceProvisioningStatus.NOT_PROVISIONED);
        assertThat(workspace.getBaseCommit()).isNull();
        assertThat(workspace.getCurrentCommit()).isNull();
        assertThat(workspace.getProvisionedAt()).isNull();
    }

}
