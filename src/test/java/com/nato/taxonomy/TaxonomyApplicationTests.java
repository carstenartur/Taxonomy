package com.nato.taxonomy;

import com.nato.taxonomy.dto.TaxonomyNodeDto;
import com.nato.taxonomy.service.TaxonomyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
                .andExpect(jsonPath("$.tree.length()").value(8));
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
                .andExpect(jsonPath("$.provider").isEmpty());
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
}
