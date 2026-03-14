package com.taxonomy;

import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.export.MermaidExportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.security.test.context.support.WithMockUser;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class MermaidExportTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MermaidExportService mermaidExportService;

    // ── MermaidExportService Unit Tests ────────────────────────────────────

    @Test
    void exportWithNullModelReturnsEmptyFlowchart() {
        String result = mermaidExportService.export(null);
        assertThat(result).startsWith("flowchart LR");
        assertThat(result).contains("No data");
    }

    @Test
    void exportWithEmptyNodesReturnsEmptyFlowchart() {
        DiagramModel model = new DiagramModel("Test", List.of(), List.of(),
                new DiagramLayout("LR", true));
        String result = mermaidExportService.export(model);
        assertThat(result).startsWith("flowchart LR");
        assertThat(result).contains("No data");
    }

    @Test
    void exportProducesValidFlowchartWithNodes() {
        DiagramNode node1 = new DiagramNode("CP-001", "Capability One", "Capabilities", 0.9, true, 1);
        DiagramNode node2 = new DiagramNode("BP-001", "Process One", "Business Processes", 0.7, false, 2);
        DiagramEdge edge = new DiagramEdge("e1", "CP-001", "BP-001", "SUPPORTS", 0.8);

        DiagramModel model = new DiagramModel("Test Diagram",
                List.of(node1, node2), List.of(edge),
                new DiagramLayout("LR", true));

        String result = mermaidExportService.export(model);

        assertThat(result).startsWith("flowchart LR");
        assertThat(result).contains("CP_001");
        assertThat(result).contains("BP_001");
        assertThat(result).contains("SUPPORTS");
        assertThat(result).contains("Capabilities");
        assertThat(result).contains("Business Processes");
    }

    @Test
    void exportGroupsNodesByType() {
        DiagramNode n1 = new DiagramNode("CP-001", "Cap One", "Capabilities", 0.9, true, 1);
        DiagramNode n2 = new DiagramNode("CP-002", "Cap Two", "Capabilities", 0.8, false, 1);
        DiagramNode n3 = new DiagramNode("BP-001", "Proc One", "Business Processes", 0.7, false, 2);

        DiagramModel model = new DiagramModel("Test",
                List.of(n1, n2, n3), List.of(),
                new DiagramLayout("LR", true));

        String result = mermaidExportService.export(model);

        // Should have subgraph for Capabilities and Business Processes
        assertThat(result).contains("subgraph Capabilities");
        assertThat(result).contains("subgraph Business_Processes");
        assertThat(result).contains("end");
    }

    @Test
    void exportIncludesClassDefs() {
        DiagramNode node = new DiagramNode("CP-001", "Cap", "Capabilities", 0.9, true, 1);
        DiagramModel model = new DiagramModel("Test",
                List.of(node), List.of(),
                new DiagramLayout("LR", true));

        String result = mermaidExportService.export(model);

        assertThat(result).contains("classDef cap fill:#4A90D9");
        assertThat(result).contains("classDef proc fill:#27AE60");
        assertThat(result).contains("classDef svc fill:#F39C12");
        assertThat(result).contains("classDef app fill:#8E44AD");
    }

    @Test
    void exportMarksAnchorsWithStar() {
        DiagramNode anchor = new DiagramNode("CP-001", "Cap", "Capabilities", 0.9, true, 1);
        DiagramNode nonAnchor = new DiagramNode("BP-001", "Proc", "Business Processes", 0.7, false, 2);

        DiagramModel model = new DiagramModel("Test",
                List.of(anchor, nonAnchor), List.of(),
                new DiagramLayout("LR", true));

        String result = mermaidExportService.export(model);

        assertThat(result).contains("★");
    }

    @Test
    void exportIncludesRelevancePercentage() {
        DiagramNode node = new DiagramNode("CP-001", "Cap", "Capabilities", 0.85, false, 1);
        DiagramModel model = new DiagramModel("Test",
                List.of(node), List.of(),
                new DiagramLayout("LR", true));

        String result = mermaidExportService.export(model);

        assertThat(result).contains("[85%]");
    }

    @Test
    void sanitizeIdReplacesSpecialChars() {
        // Test sanitization indirectly through export
        DiagramNode node = new DiagramNode("CP-001", "Cap", "Capabilities", 0.9, false, 1);
        DiagramModel model = new DiagramModel("Test",
                List.of(node), List.of(),
                new DiagramLayout("LR", true));

        String result = mermaidExportService.export(model);
        // CP-001 should become CP_001 (hyphens replaced)
        assertThat(result).contains("CP_001");
    }

    @Test
    void escapeHandlesSpecialCharacters() {
        // Test escape indirectly through export with special characters
        DiagramNode node = new DiagramNode("N1", "Test \"quoted\"", "Capabilities", 0.9, false, 1);
        DiagramModel model = new DiagramModel("Test",
                List.of(node), List.of(),
                new DiagramLayout("LR", true));

        String result = mermaidExportService.export(model);
        assertThat(result).contains("&quot;");
    }

    // ── API Endpoint Tests ────────────────────────────────────────────────

    @Test
    void mermaidEndpointRejectsMissingText() throws Exception {
        mockMvc.perform(post("/api/diagram/mermaid")
                        .contentType("application/json")
                        .content("{\"businessText\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mermaidEndpointRejectsEmptyBody() throws Exception {
        mockMvc.perform(post("/api/diagram/mermaid")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ── Proposal Bulk/Revert Endpoint Tests ───────────────────────────────

    @Test
    void bulkEndpointRejectsMissingIds() throws Exception {
        mockMvc.perform(post("/api/proposals/bulk")
                        .contentType("application/json")
                        .content("{\"action\":\"ACCEPT\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void bulkEndpointRejectsMissingAction() throws Exception {
        mockMvc.perform(post("/api/proposals/bulk")
                        .contentType("application/json")
                        .content("{\"ids\":[1,2,3]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void bulkEndpointRejectsEmptyIds() throws Exception {
        mockMvc.perform(post("/api/proposals/bulk")
                        .contentType("application/json")
                        .content("{\"ids\":[],\"action\":\"ACCEPT\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void bulkEndpointRejectsInvalidAction() throws Exception {
        mockMvc.perform(post("/api/proposals/bulk")
                        .contentType("application/json")
                        .content("{\"ids\":[1],\"action\":\"INVALID\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void bulkEndpointHandlesNonexistentIds() throws Exception {
        mockMvc.perform(post("/api/proposals/bulk")
                        .contentType("application/json")
                        .content("{\"ids\":[99999],\"action\":\"ACCEPT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.success").value(0));
    }

    @Test
    void revertEndpointRejectsNonexistentProposal() throws Exception {
        mockMvc.perform(post("/api/proposals/99999/revert")
                        .contentType("application/json"))
                .andExpect(status().isBadRequest());
    }
}
