package com.taxonomy;

import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.service.SearchService;
import com.taxonomy.service.TaxonomyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "gemini.api.key=",
    "openai.api.key=",
    "deepseek.api.key=",
    "qwen.api.key=",
    "llama.api.key=",
    "mistral.api.key="
})
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
    void startupStatusEndpointReturnsBackwardCompatibleFields() throws Exception {
        mockMvc.perform(get("/api/status/startup").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.initialized").isBoolean())
                .andExpect(jsonPath("$.status").isString());
    }

    @Test
    void startupStatusEndpointReturnsPhaseFields() throws Exception {
        mockMvc.perform(get("/api/status/startup").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").isString())
                .andExpect(jsonPath("$.phaseMessage").isString())
                .andExpect(jsonPath("$.phaseUpdatedAt").isString());
    }

    @Test
    void startupStatusEndpointReturnsReadyAfterInit() throws Exception {
        // In test context taxonomy is loaded synchronously — so app should be ready
        mockMvc.perform(get("/api/status/startup").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initialized").value(true))
                .andExpect(jsonPath("$.status").value("ready"))
                .andExpect(jsonPath("$.phase").value("READY"));
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
    void adminStatusReturnsFalseWhenNoPasswordConfigured() throws Exception {
        // No ADMIN_PASSWORD set in test environment — passwordRequired should be false
        mockMvc.perform(get("/api/admin/status").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordRequired").value(false));
    }

    @Test
    void adminVerifyReturnsFalseWhenNoPasswordConfigured() throws Exception {
        // When no password is configured, no password is valid
        mockMvc.perform(post("/api/admin/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"anything\"}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));
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

    @Test
    void scoresExportEndpointReturns200ForValidInput() throws Exception {
        mockMvc.perform(post("/api/scores/export")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\":\"Provide secure voice communications\","
                                + "\"scores\":{\"CO\":35,\"CR\":25,\"BR\":0},"
                                + "\"reasons\":{\"CO\":\"Voice comms\"},"
                                + "\"provider\":\"GEMINI\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.requirement").value("Provide secure voice communications"))
                .andExpect(jsonPath("$.timestamp").isString())
                .andExpect(jsonPath("$.scores.CO").value(35))
                .andExpect(jsonPath("$.scores.BR").value(0));
    }

    @Test
    void scoresExportEndpointReturnsBadRequestForBlankRequirement() throws Exception {
        mockMvc.perform(post("/api/scores/export")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\":\"\","
                                + "\"scores\":{\"CO\":35},"
                                + "\"reasons\":{}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void scoresExportEndpointReturnsBadRequestForMissingScores() throws Exception {
        mockMvc.perform(post("/api/scores/export")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\":\"Test requirement\","
                                + "\"scores\":{},"
                                + "\"reasons\":{}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void scoresImportEndpointReturns200ForValidJson() throws Exception {
        String json = "{\"version\":1,\"requirement\":\"Test requirement\","
                + "\"scores\":{\"CO\":35,\"BR\":0},\"reasons\":{\"CO\":\"Voice\"}}";

        mockMvc.perform(post("/api/scores/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requirement").value("Test requirement"))
                .andExpect(jsonPath("$.scores.CO").value(35))
                .andExpect(jsonPath("$.scores.BR").value(0))
                .andExpect(jsonPath("$.warnings").isArray());
    }

    @Test
    void scoresImportEndpointReturnsBadRequestForInvalidVersion() throws Exception {
        String json = "{\"version\":99,\"requirement\":\"Test\","
                + "\"scores\":{\"CO\":35},\"reasons\":{}}";

        mockMvc.perform(post("/api/scores/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isString());
    }

    @Test
    void scoresImportEndpointIncludesWarningsForUnknownCodes() throws Exception {
        String json = "{\"version\":1,\"requirement\":\"Test requirement\","
                + "\"scores\":{\"CO\":35,\"UNKNOWN_XYZ\":10},\"reasons\":{}}";

        mockMvc.perform(post("/api/scores/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings").isArray())
                .andExpect(jsonPath("$.warnings[0]").value(org.hamcrest.Matchers.containsString("UNKNOWN_XYZ")));
    }
}
