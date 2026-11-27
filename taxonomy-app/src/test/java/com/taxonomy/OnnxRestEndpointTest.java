package com.taxonomy;

import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.dsl.export.TaxDslExportService;
import com.taxonomy.dto.CatalogueAnalysisPreview;
import com.taxonomy.search.LocalOnnxIndexInitializer;
import com.taxonomy.testsupport.LocalTestLogin;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceContextResolver;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * REST endpoint tests for embedding and semantic-search APIs with a real pinned
 * ONNX model. Ordinary and database-focused Maven suites exclude the
 * {@code onnx} group; the {@code ci} and {@code onnx} profiles execute it
 * deliberately with an explicitly provisioned model directory.
 */
@Tag("onnx")
@Tag("ci-app-shard-recovery")
@SpringBootTest(properties = {
        "llm.provider=LOCAL_ONNX",
        "embedding.enabled=true",
        "embedding.allow-download=false"
})
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class OnnxRestEndpointTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration SEMANTIC_READY_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration READINESS_POLL_INTERVAL = Duration.ofMillis(250);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext application;

    @Autowired
    private LocalEmbeddingService embeddingService;

    @Autowired
    private LocalOnnxIndexInitializer indexInitializer;

    @BeforeEach
    void awaitSemanticIndexReadiness() throws Exception {
        long deadline = System.nanoTime() + SEMANTIC_READY_TIMEOUT.toNanos();
        while (!indexInitializer.isNodeSearchReady()) {
            if (indexInitializer.getState() == LocalOnnxIndexInitializer.State.FAILED) {
                throw new AssertionError("LOCAL_ONNX semantic index failed before REST verification: "
                        + indexInitializer.getDetail()
                        + "; indexedNodes=" + embeddingService.indexedNodeCount());
            }
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("LOCAL_ONNX semantic index did not become ready within "
                        + SEMANTIC_READY_TIMEOUT
                        + "; state=" + indexInitializer.getState()
                        + "; detail=" + indexInitializer.getDetail()
                        + "; indexedNodes=" + embeddingService.indexedNodeCount());
            }
            Thread.sleep(READINESS_POLL_INTERVAL.toMillis());
        }
    }

    @Test
    void embeddingStatusEndpointIsOk() throws Exception {
        mockMvc.perform(get("/api/embedding/status").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.modelAvailable").value(true))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.semanticReady").value(true))
                .andExpect(jsonPath("$.indexedNodes").value(
                        org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.indexedNodesAtReadiness").value(
                        org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    void semanticSearchReturnsArray() throws Exception {
        mockMvc.perform(get("/api/search/semantic")
                        .param("q", "communications")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void hybridSearchReturnsResults() throws Exception {
        mockMvc.perform(get("/api/search/hybrid")
                        .param("q", "satellite communications")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    @Test
    void findSimilarReturnsArray() throws Exception {
        // CO is one of the 8 taxonomy roots (BP, BR, CP, CI, CO, CR, IP, UA)
        mockMvc.perform(get("/api/search/similar/CO")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void changingAnOfficeRequirementChangesTheOnnxPreviewAndScoredArchitecture() throws Exception {
        // Another Spring context in the shared test JVM may already have consumed the startup bootstrap.
        var primary = application.getBean(SystemRepositoryService.class).getPrimaryRepository();
        var source = application.getBean(DslGitRepositoryFactory.class).getCentralRepository(primary.getRepositoryId());
        if (source.getHeadCommit(primary.getDefaultBranch()) == null) {
            var project = application.getBean(TaxDslExportService.class).newProject("onnx-office-test");
            source.commitDsl(primary.getDefaultBranch(), project, "onnx-office-workspace", "Initialize office catalogue source");
        }
        LocalTestLogin.login(application, "onnx-office-workspace", "USER", "ARCHITECT", "ADMIN");
        var workspace = JSON.readTree(mockMvc.perform(post("/api/workspace/create").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"ONNX office requirements\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
                .required("workspaceId").asString();
        mockMvc.perform(post("/api/workspace/provision").with(csrf()).header(WorkspaceContextResolver.WORKSPACE_HEADER, workspace))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));
        var projection = "/api/architecture/relations/projection";
        var readiness = mockMvc.perform(get(projection + "/readiness").header(WorkspaceContextResolver.WORKSPACE_HEADER, workspace))
                .andExpect(status().isOk()).andExpect(header().exists("ETag")).andReturn().getResponse();
        var commit = JSON.readTree(readiness.getContentAsByteArray()).required("currentHeadCommit").asString();
        mockMvc.perform(post(projection + "/rebuild").with(csrf()).header("If-Match", readiness.getHeader("ETag"))
                        .header(WorkspaceContextResolver.WORKSPACE_HEADER, workspace))
                .andExpect(status().isOk());

        var roots = Set.of("UA");
        mockMvc.perform(post("/api/analysis/preview/prepare").with(csrf()).header(WorkspaceContextResolver.WORKSPACE_HEADER, workspace)
                        .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsBytes(
                                new CatalogueAnalysisPreview.Request(null, roots, commit, null))))
                .andExpect(status().isNoContent());

        String mail = "Send and receive electronic mail.";
        String payroll = "Manage payroll and salary payments.";
        var before = await().pollInSameThread().atMost(SEMANTIC_READY_TIMEOUT).pollInterval(Duration.ofSeconds(2))
                .until(() -> preview(workspace, commit, mail), result -> "CATALOGUE_ONNX".equals(result.method()));
        var after = preview(workspace, commit, payroll);
        assertThat(after.method()).isEqualTo("CATALOGUE_ONNX");
        assertThat(after.catalogueRevision()).isNotBlank().isEqualTo(before.catalogueRevision());
        assertThat(after.semanticRevision()).isEqualTo(before.semanticRevision());
        var mailMatches = directMatches(before, commit);
        var payrollMatches = directMatches(after, commit);
        assertThat(mailMatches).contains("UA-1222"); // Email Applications
        assertThat(payrollMatches).contains("UA-1604"); // Payroll Applications
        assertThat(payrollMatches).isNotEqualTo(mailMatches);

        var mailAnchors = scoredArchitecture(workspace, commit, mail);
        var payrollAnchors = scoredArchitecture(workspace, commit, payroll);
        assertThat(payrollAnchors).isNotEqualTo(mailAnchors);
    }

    private CatalogueAnalysisPreview preview(String workspace, String commit, String text) throws Exception {
        var result = postJson(workspace, "/api/analysis/preview",
                new CatalogueAnalysisPreview.Request(text, Set.of("UA"), commit, null));
        return JSON.treeToValue(result, CatalogueAnalysisPreview.class);
    }

    private static Set<String> directMatches(CatalogueAnalysisPreview preview, String commit) {
        assertThat(preview.commit()).isEqualTo(commit);
        var codes = new HashSet<String>();
        var direct = new HashSet<String>();
        for (var node : preview.nodes()) {
            assertThat(node.taxonomyRoot()).isEqualTo("UA");
            assertThat(codes.add(node.code())).isTrue();
            if (node.score() != null) {
                assertThat(node.score()).isBetween(0.0, 100.0);
                direct.add(node.code());
            }
        }
        for (var node : preview.nodes()) {
            if (node.parentCode() != null) assertThat(codes).contains(node.parentCode());
        }
        assertThat(direct).isNotEmpty();
        return direct;
    }

    private Set<String> scoredArchitecture(String workspace, String commit, String text) throws Exception {
        var result = postJson(workspace, "/api/analyze", Map.of("businessText", text, "provider", "LOCAL_ONNX",
                "expectedCommit", commit, "includeArchitectureView", true, "maxArchitectureNodes", 20,
                "analysisScope", Map.of("taxonomyRoots", Set.of("UA"), "mode", "TAXONOMIES_ONLY")));
        assertThat(result.required("status").asString()).isEqualTo("SUCCESS");
        assertThat(result.required("analysisProviderId").asString()).isEqualTo("LOCAL_ONNX");
        assertThat(result.required("modelBinding").required("commit").asString()).isEqualTo(commit);
        var scores = result.required("scores");
        var view = result.required("architectureView");
        assertThat(view.required("originalRequirementText").asString()).isEqualTo(text);
        var anchors = new HashSet<String>();
        for (var anchor : view.required("anchors")) {
            String code = anchor.required("nodeCode").asString();
            assertThat(code).startsWith("UA-");
            assertThat(anchor.required("directScore").asDouble())
                    .isBetween(0.0, 100.0).isEqualTo(scores.required(code).asDouble());
            assertThat(anchors.add(code)).isTrue();
        }
        assertThat(anchors).hasSizeBetween(1, 20);
        var elements = new HashSet<String>();
        for (var element : view.required("includedElements")) elements.add(element.required("nodeCode").asString());
        assertThat(elements).containsAll(anchors);
        for (var edge : view.required("includedRelationships")) {
            assertThat(elements).contains(edge.required("sourceCode").asString(), edge.required("targetCode").asString());
        }
        return anchors;
    }

    private JsonNode postJson(String workspace, String path, Object body) throws Exception {
        var response = mockMvc.perform(post(path).with(csrf()).header(WorkspaceContextResolver.WORKSPACE_HEADER, workspace)
                        .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsBytes(body)))
                .andExpect(status().isOk()).andReturn().getResponse();
        return JSON.readTree(response.getContentAsByteArray());
    }

    @Test
    void graphSearchReturnsStructuredResult() throws Exception {
        long deadline = System.nanoTime() + SEMANTIC_READY_TIMEOUT.toNanos();
        while (indexInitializer.getState() != LocalOnnxIndexInitializer.State.READY) {
            var state = indexInitializer.getState();
            if (state == LocalOnnxIndexInitializer.State.PARTIAL || state == LocalOnnxIndexInitializer.State.FAILED) {
                throw new AssertionError("LOCAL_ONNX graph index failed before REST verification: "
                        + indexInitializer.getDetail());
            }
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("LOCAL_ONNX graph index did not become ready within "
                        + SEMANTIC_READY_TIMEOUT + "; state=" + state);
            }
            Thread.sleep(READINESS_POLL_INTERVAL.toMillis());
        }
        // The global relation index belongs to the primary central source;
        // an implicit workspace has no proven branch projection yet.
        mockMvc.perform(get("/api/search/graph")
                        .header(WorkspaceContextResolver.WORKSPACE_HEADER, "")
                        .param("q", "communications")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedNodes").isArray())
                .andExpect(jsonPath("$.summary").isString())
                .andExpect(jsonPath("$.source.vectorSource").value("GLOBAL_INDEX"))
                .andExpect(jsonPath("$.source.modelKey").value(embeddingService.embeddingIndexKey()))
                .andExpect(jsonPath("$.source.sourceCommit").isString())
                .andExpect(header().exists("ETag"));
    }
}
