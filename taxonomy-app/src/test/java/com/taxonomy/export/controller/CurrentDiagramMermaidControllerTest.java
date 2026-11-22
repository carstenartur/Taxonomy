package com.taxonomy.export.controller;

import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.dto.RequirementElementView;
import com.taxonomy.dto.RequirementRelationshipView;
import com.taxonomy.export.DiagramProjectionService;
import com.taxonomy.export.MermaidExportService;
import com.taxonomy.export.service.ExportFacade;
import com.taxonomy.export.service.ExportFormatExtensionRegistry;
import com.taxonomy.plugins.mermaid.MermaidExportExtension;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class CurrentDiagramMermaidControllerTest {
    @Test
    void currentMermaidUsesRequestedLocaleWithoutStartingAnalysis() throws Exception {
        var facade = new ExportFacade(null, null, new DiagramProjectionService(), null, null, null, null, null, null);
        var registry = new ExportFormatExtensionRegistry(List.of(new MermaidExportExtension(new MermaidExportService())));
        var controller = new ExportApiController(facade, registry);
        var view = new RequirementArchitectureView();
        view.setViewTitle("archview.policy.title.defaultImpact");
        view.setIncludedElements(List.of(element("fixture-source", "Leitstelle", "BP"),
                element("fixture-target", "Übergabenachweis", "IP")));
        var relation = new RequirementRelationshipView();
        relation.setSourceCode("fixture-source");
        relation.setTargetCode("fixture-target");
        relation.setRelationType("CONSUMES");
        relation.setPropagatedRelevance(.3);
        view.setIncludedRelationships(List.of(relation));

        var response = standaloneSetup(controller).build()
                .perform(post("/api/diagram/current/mermaid").param("locale", "de")
                        .contentType(MediaType.APPLICATION_JSON).content(new ObjectMapper().writeValueAsBytes(view)))
                .andExpect(status().isOk()).andReturn().getResponse();

        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains("Architektur-Wirkungsansicht", "Geschäftsprozesse", "Informationsprodukte", "-->|konsumiert|", "Übergabenachweis")
                .doesNotContain("archview.policy.title.");
        assertThat(facade.buildCurrentDiagram(view, "en").title()).isEqualTo("Architecture Impact View");
        assertThat(view.getViewTitle()).isEqualTo("archview.policy.title.defaultImpact");
        view.setViewTitle("Erfasste Übergabe");
        assertThat(facade.buildCurrentDiagram(view, "en").title()).isEqualTo("Erfasste Übergabe");
    }

    private static RequirementElementView element(String code, String title, String root) {
        var node = new RequirementElementView();
        node.setNodeCode(code);
        node.setTitle(title);
        node.setTaxonomySheet(root);
        node.setRelevance(.6);
        return node;
    }
}
