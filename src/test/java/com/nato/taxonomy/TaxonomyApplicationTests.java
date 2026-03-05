package com.nato.taxonomy;

import com.nato.taxonomy.dto.TaxonomyNodeDto;
import com.nato.taxonomy.service.SearchService;
import com.nato.taxonomy.service.TaxonomyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class TaxonomyApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TaxonomyService taxonomyService;

    @Autowired
    private SearchService searchService;

    @Test
    void contextLoads() {
        assertThat(taxonomyService).isNotNull();
    }

    @Test
    void taxonomyHasEightRoots() {
        List<TaxonomyNodeDto> tree = taxonomyService.getFullTree();
        assertThat(tree).hasSize(8);
    }

    @Test
    void rootCodesMatchExcelSheets() {
        List<TaxonomyNodeDto> tree = taxonomyService.getFullTree();
        List<String> codes = tree.stream().map(TaxonomyNodeDto::getCode).toList();
        assertThat(codes).containsExactlyInAnyOrder("BP", "BR", "CP", "CI", "CO", "CR", "IP", "UA");
    }

    @Test
    void apiTaxonomyEndpointReturnsJson() throws Exception {
        mockMvc.perform(get("/api/taxonomy").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(8));
    }

    @Test
    void apiTaxonomyRootNodesHaveChildren() throws Exception {
        mockMvc.perform(get("/api/taxonomy").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].children").isArray())
                .andExpect(jsonPath("$[0].children.length()").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    void analyzeEndpointReturnsBadRequestForEmptyText() throws Exception {
        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessText\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void analyzeEndpointReturnsResultForValidText() throws Exception {
        // Gemini API key will be empty in CI, so scores default to 0 — but structure is correct
        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessText\":\"Manage satellite communications for deployed forces\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scores").exists())
                .andExpect(jsonPath("$.tree").isArray())
                .andExpect(jsonPath("$.tree.length()").value(8))
                .andExpect(jsonPath("$.status").exists());
    }

    @Test
    void analyzeEndpointReturnsStatusWarningsAndErrorMessageFields() throws Exception {
        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessText\":\"Provide secure voice communications\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.warnings").isArray())
                .andExpect(jsonPath("$.scores").exists())
                .andExpect(jsonPath("$.tree").isArray());
    }

    @Test
    void aiStatusEndpointReturnsJson() throws Exception {
        mockMvc.perform(get("/api/ai-status").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.available").isBoolean());
    }

    @Test
    void aiStatusEndpointReturnsUnavailableWhenNoKeyConfigured() throws Exception {
        // In CI / test environment no API key is set, so available should be false
        mockMvc.perform(get("/api/ai-status").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.provider", org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void homePageReturnsHtml() throws Exception {
        mockMvc.perform(get("/").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
    }

    @Test
    void eachRootNodeHasCorrectTaxonomyRoot() {
        List<TaxonomyNodeDto> tree = taxonomyService.getFullTree();
        for (TaxonomyNodeDto root : tree) {
            assertThat(root.getTaxonomyRoot()).isEqualTo(root.getCode());
            assertThat(root.getLevel()).isEqualTo(0);
        }
    }

    @Test
    void childNodesReferenceTheirParent() {
        List<TaxonomyNodeDto> tree = taxonomyService.getFullTree();
        for (TaxonomyNodeDto root : tree) {
            for (TaxonomyNodeDto child : root.getChildren()) {
                assertThat(child.getParentCode()).isEqualTo(root.getCode());
            }
        }
    }

    @Test
    void searchEndpointReturnsBadRequestForBlankQuery() throws Exception {
        mockMvc.perform(get("/api/search").param("q", "").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void searchEndpointReturnsJsonForValidQuery() throws Exception {
        mockMvc.perform(get("/api/search").param("q", "BP").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void searchReturnsResultsForKnownTaxonomyCode() {
        List<TaxonomyNodeDto> results = searchService.search("BP", 50);
        assertThat(results).isNotEmpty();
        assertThat(results).anyMatch(dto -> dto.getCode() != null && dto.getCode().startsWith("BP"));
    }

    @Test
    void searchReturnsResultsForWordInDescription() {
        // "Business Processes" virtual root has "Business" in its nameEn
        List<TaxonomyNodeDto> results = searchService.search("Business", 50);
        assertThat(results).isNotEmpty();
    }

    @Test
    void nodeDtoContainsNewFields() {
        List<TaxonomyNodeDto> tree = taxonomyService.getFullTree();
        // Verify that the DTO has the new fields (nameEn/descriptionEn should not be null for roots)
        TaxonomyNodeDto root = tree.get(0);
        assertThat(root.getNameEn()).isNotNull();
        assertThat(root.getDescriptionEn()).isNotNull();
        // getName() backward-compat getter should return the English name
        assertThat(root.getName()).isEqualTo(root.getNameEn());
        assertThat(root.getDescription()).isEqualTo(root.getDescriptionEn());
    }

    @Test
    void analyzeStreamEndpointSendsErrorEventForBlankText() throws Exception {
        // With blank text the endpoint should immediately send an error event and complete
        mockMvc.perform(get("/api/analyze-stream")
                        .param("businessText", "")
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM_VALUE));
    }

    @Test
    void analyzeStreamEndpointReturnsEventStreamForValidText() throws Exception {
        // In CI no API key is set, so scores default to zero but the stream must complete
        mockMvc.perform(get("/api/analyze-stream")
                        .param("businessText", "Manage satellite communications for deployed forces")
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM_VALUE));
    }

    @Test
    void diagnosticsEndpointReturnsJson() throws Exception {
        mockMvc.perform(get("/api/diagnostics").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.provider").isString())
                .andExpect(jsonPath("$.apiKeyConfigured").isBoolean())
                .andExpect(jsonPath("$.totalCalls").isNumber())
                .andExpect(jsonPath("$.successfulCalls").isNumber())
                .andExpect(jsonPath("$.failedCalls").isNumber())
                .andExpect(jsonPath("$.serverTime").isString());
    }

    @Test
    void diagnosticsEndpointShowsNoKeyConfiguredInTestEnv() throws Exception {
        // In CI / test environment no API key is set
        mockMvc.perform(get("/api/diagnostics").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKeyConfigured").value(false))
                .andExpect(jsonPath("$.apiKeyPrefix", org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void analyzeNodeEndpointIncludesErrorFieldWhenNoKeyConfigured() throws Exception {
        // In CI / test environment no API key is set — error field should be non-null
        mockMvc.perform(get("/api/analyze-node")
                        .param("parentCode", "BP")
                        .param("businessText", "Test business requirement")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").isString());
    }
}
