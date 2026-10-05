package com.taxonomy.dsl;

import com.taxonomy.architecture.repository.ArchitectureDslDocumentRepository;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dsl.export.TaxDslExportService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceContextResolver;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Replays the Preferences browser fixture through real MVC, JPA and database-backed Git.
 * No test transaction may defer the materialization commit until after the response.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:hsqldb:mem:dsl-workspace-materialize-repro;DB_CLOSE_DELAY=-1",
        "taxonomy.init.async=false", "taxonomy.git.bootstrap=false",
        "embedding.enabled=false", "embedding.allow-download=false", "llm.mock=true",
        "gemini.api.key=", "openai.api.key=", "deepseek.api.key=",
        "qwen.api.key=", "llama.api.key=", "mistral.api.key="
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DslMaterializeWorkspaceCommitReproductionTest {

    private static final Logger log = LoggerFactory.getLogger(DslMaterializeWorkspaceCommitReproductionTest.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String OWNER = "dsl-workspace-materialize-repro";
    private static final String HEADER = WorkspaceContextResolver.WORKSPACE_HEADER;
    private static final String PATH = "qa-preferences-hypotheses.tax";

    @Autowired private MockMvc mvc;
    @Autowired private TaxonomyService taxonomy;
    @Autowired private TaxDslExportService exports;
    @Autowired private SystemRepositoryService repositories;
    @Autowired private DslGitRepositoryFactory gitRepositories;
    @Autowired private ArchitectureDslDocumentRepository documents;

    @Test
    @Timeout(value = 90, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void newProvisionedWorkspaceCommitsTwoBrowserHypothesesAndReturnsViewContext() throws Exception {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(taxonomy.isInitialized()).as("real catalogue startup completed").isTrue();

        // Own a real source checkpoint in this isolated database. The production
        // bootstrap has a JVM-wide guard and may have run in an earlier test context.
        log.info("Backend materialization reproduction: preparing catalogue source checkpoint");
        var primary = repositories.getPrimaryRepository();
        var source = gitRepositories.getCentralRepository(primary.getRepositoryId());
        source.commitDsl(primary.getDefaultBranch(), exports.exportAll("backend-reproduction"),
                OWNER, "Catalogue source for isolated backend reproduction");

        log.info("Backend materialization reproduction: creating workspace");
        var created = mvc.perform(post("/api/workspace/create")
                        .with(user(OWNER).roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"QA Preferences hypothesis restore",
                                 "description":"Isolated backend materialization reproduction"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provisioningStatus").value("NOT_PROVISIONED"))
                .andReturn();
        String workspaceId = JSON.readTree(created.getResponse().getContentAsString())
                .path("workspaceId").asText();
        assertThat(workspaceId).isNotBlank();

        log.info("Backend materialization reproduction: provisioning selected workspace");
        mvc.perform(post("/api/workspace/provision").header(HEADER, workspaceId)
                        .with(user(OWNER).roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));
        mvc.perform(post("/api/workspace/{id}/switch", workspaceId).header(HEADER, workspaceId)
                        .with(user(OWNER).roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceId").value(workspaceId));
        mvc.perform(get("/api/workspace/current").header(HEADER, workspaceId)
                        .with(user(OWNER).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceId").value(workspaceId))
                .andExpect(jsonPath("$.provisioningStatus").value("READY"));

        log.info("Backend materialization reproduction: fetching real catalogue endpoints");
        var catalogue = mvc.perform(get("/api/taxonomy").header(HEADER, workspaceId)
                        .with(user(OWNER).roles("ADMIN")))
                .andExpect(status().isOk()).andReturn();
        List<CatalogueElement> all = new ArrayList<>();
        collectCatalogue(JSON.readTree(catalogue.getResponse().getContentAsString()), all);
        var processes = all.stream().filter(element -> element.code().startsWith("BP-"))
                .limit(2).toList();
        var capabilities = all.stream().filter(element -> element.code().startsWith("CP-"))
                .limit(1).toList();
        assertThat(processes).hasSize(2);
        assertThat(capabilities).hasSize(1);
        CatalogueElement capability = capabilities.getFirst();
        var fixture = new ArrayList<>(processes);
        fixture.add(capability);
        String dsl = browserFixture(fixture, processes, capability);

        var before = mvc.perform(get("/api/dsl/hypotheses").header(HEADER, workspaceId)
                        .with(user(OWNER).roles("ADMIN")))
                .andExpect(status().isOk()).andReturn();
        JsonNode initialHypotheses = JSON.readTree(before.getResponse().getContentAsString());
        assertThat(initialHypotheses.isArray()).isTrue();
        assertThat(initialHypotheses.isEmpty()).isTrue();

        log.info("Backend materialization reproduction: POST materialize (3 elements, 2 provisional hypotheses)");
        var materialized = mvc.perform(post("/api/dsl/materialize")
                        .param("path", PATH).header(HEADER, workspaceId)
                        .with(user(OWNER).roles("ADMIN")).with(csrf())
                        .contentType(MediaType.TEXT_PLAIN).content(dsl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.errors").isEmpty())
                .andExpect(jsonPath("$.warnings").isArray())
                .andExpect(jsonPath("$.relationsCreated").value(0))
                .andExpect(jsonPath("$.hypothesesCreated").value(2))
                .andExpect(jsonPath("$.documentId").isNumber())
                .andExpect(jsonPath("$.viewContext.basedOnBranch").value("draft"))
                .andExpect(jsonPath("$.viewContext.includesProvisionalRelations").value(true))
                .andExpect(jsonPath("$.viewContext.projectionStale").isBoolean())
                .andExpect(jsonPath("$.viewContext.indexStale").isBoolean())
                .andReturn();
        log.info("Backend materialization reproduction: materialize response returned after service commit");
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        JsonNode body = JSON.readTree(materialized.getResponse().getContentAsString());
        // Explicit provisioning creates main, while this compatibility endpoint
        // defaults its response context to draft when no branch was supplied.
        // Keep that exact existing behavior visible instead of supplying a branch.
        assertThat(body.path("viewContext")).isEqualTo(JSON.readTree("""
                {"basedOnCommit":null,"basedOnBranch":"draft","commitTimestamp":null,
                 "includesProvisionalRelations":true,"projectionStale":false,"indexStale":false}
                """));
        long documentId = body.path("documentId").asLong();
        assertThat(documents.findById(documentId)).hasValueSatisfying(document -> {
            assertThat(document.getPath()).isEqualTo(PATH);
            assertThat(document.getRawContent()).isEqualTo(dsl);
            assertThat(document.getBranch()).isNull();
            assertThat(document.getCommitId()).isNull();
        });

        // A new MVC request starts a new read-only service transaction and cannot
        // observe merely uncommitted entities from the preceding write request.
        log.info("Backend materialization reproduction: reading committed hypotheses in a fresh request");
        var after = mvc.perform(get("/api/dsl/hypotheses").header(HEADER, workspaceId)
                        .with(user(OWNER).roles("ADMIN")))
                .andExpect(status().isOk()).andReturn();
        JsonNode rows = JSON.readTree(after.getResponse().getContentAsString());
        assertThat(rows.isArray()).isTrue();
        assertThat(rows.size()).isEqualTo(2);
        Set<String> expectedSources = Set.of(processes.get(0).code(), processes.get(1).code());
        Set<String> observedSources = new java.util.HashSet<>();
        Set<Long> ids = new java.util.HashSet<>();
        for (JsonNode row : rows) {
            assertThat(row.path("id").asLong()).isPositive();
            ids.add(row.path("id").asLong());
            observedSources.add(row.path("sourceNodeId").asText());
            assertThat(row.path("targetNodeId").asText()).isEqualTo(capability.code());
            assertThat(row.path("relationType").asText()).isEqualTo("REALIZES");
            assertThat(row.path("status").asText()).isEqualTo("PROVISIONAL");
            assertThat(row.path("confidence").asDouble()).isEqualTo(0.82);
            assertThat(row.path("workspaceId").asText()).isEqualTo(workspaceId);
            assertThat(row.path("ownerUsername").asText()).isEqualTo(OWNER);
            assertThat(row.path("repositoryId").asText()).isEqualTo(primary.getRepositoryId());
        }
        assertThat(ids).hasSize(2);
        assertThat(observedSources).isEqualTo(expectedSources);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        log.info("Backend materialization reproduction: complete (2 committed workspace hypotheses)");
    }

    private static void collectCatalogue(JsonNode nodes, List<CatalogueElement> result) {
        for (JsonNode node : nodes) {
            String code = node.path("code").asText();
            String title = node.path("nameEn").asText();
            result.add(new CatalogueElement(code, title.isBlank() ? code : title,
                    code.startsWith("BP-") ? "Process" : "Capability"));
            collectCatalogue(node.path("children"), result);
        }
    }

    private static String browserFixture(List<CatalogueElement> elements,
                                         List<CatalogueElement> processes,
                                         CatalogueElement capability) throws Exception {
        StringBuilder dsl = new StringBuilder();
        for (CatalogueElement element : elements) {
            dsl.append("element ").append(element.code()).append(" type ").append(element.type())
                    .append(" {\n  title: ").append(JSON.writeValueAsString(element.title())).append(";\n}\n");
        }
        for (CatalogueElement process : processes) {
            dsl.append("relation ").append(process.code()).append(" REALIZES ").append(capability.code())
                    .append(" {\n  status: provisional;\n  confidence: 0.82;\n}\n");
        }
        return dsl.toString();
    }

    private record CatalogueElement(String code, String title, String type) { }
}
