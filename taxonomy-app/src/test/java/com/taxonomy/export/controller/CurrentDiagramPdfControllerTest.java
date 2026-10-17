package com.taxonomy.export.controller;

import com.taxonomy.dto.*;
import com.taxonomy.export.*;
import com.taxonomy.export.service.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class CurrentDiagramPdfControllerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void literalPdfMappingExportsTheFullSuppliedViewWithoutAnLlmOrRegistryFormat() throws Exception {
        var view = view(500);
        byte[] before = JSON.writeValueAsBytes(view);
        var response = mvc().perform(post("/api/diagram/current/pdf").contentType(MediaType.APPLICATION_JSON).content(before))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"requirement-architecture.pdf\""))
                .andReturn().getResponse();
        byte[] bytes = response.getContentAsByteArray();
        assertThat(new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        try (var pdf = Loader.loadPDF(bytes)) {
            var text = new PDFTextStripper().getText(pdf);
            assertThat(text).contains("Client-provided working view", "Elements: 500", "Cross-page relationships: 1", "D1 -> D2",
                    "Relation ID: 9223372036854775807", "LLM_SUPPORTED", "Confidence: 0.125", "FRAMEWORK_SEED",
                    "HTTP-INCLUSION-END", "HTTP-DERIVATION-END", "HTTP-PRESENCE-END", "HTTP-CONTRIBUTION-END",
                    "HTTP-ORIGINAL-QUOTE-END", "HTTP-CONTRIBUTION-CONDITION-END", "HTTP-DECISION-CONTRIBUTION-END",
                    "HTTP-DECISION-QUOTE-END", "HTTP-DECISION-CONDITION-END", "HTTP-ALTERNATIVE-GROUP-END",
                    "HTTP-RATIONALE-END", "HTTP-QUESTION-END");
            for (int index = 0; index < 500; index++) assertThat(text).contains(String.format("HTTP-LABEL-%04d-END", index));
            pdf.getPages().forEach(page -> {
                assertThat(page.getMediaBox().getWidth()).isLessThanOrEqualTo(14_400);
                assertThat(page.getMediaBox().getHeight()).isLessThanOrEqualTo(14_400);
            });
            assertThat(text).doesNotContain("Git-authoritative", "Generated from persisted architecture snapshot");
        }
        assertThat(JSON.writeValueAsBytes(view)).isEqualTo(before);
    }

    @Test void malformedAndOversizedBodiesReturn400ThroughSpringMappings() throws Exception {
        var mvc = mvc();
        for (String body : List.of("{", "null", "{}", JSON.writeValueAsString(view(10_001))))
            mvc.perform(post("/api/diagram/current/pdf").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        var missingEndpoint = view(2); missingEndpoint.getIncludedRelationships().getFirst().setTargetCode("missing");
        mvc.perform(post("/api/diagram/current/pdf").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsBytes(missingEndpoint)))
                .andExpect(status().isBadRequest());
    }

    @Test void rendererFailureIsAnHonest500WithoutLeakingDetails() throws Exception {
        var renderer = org.mockito.Mockito.mock(ArchitecturePdfRenderer.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("PRIVATE_RENDERER_DETAIL"))
                .when(renderer).renderWorkingView(org.mockito.ArgumentMatchers.any(com.taxonomy.diagram.DiagramScene.class));
        mvc(renderer).perform(post("/api/diagram/current/pdf").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsBytes(view(2))))
                .andExpect(status().isInternalServerError()).andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("The architecture PDF could not be created. The working state was not changed."));
    }

    private static MockMvc mvc() throws Exception { return mvc(new ArchitecturePdfRenderer()); }
    private static MockMvc mvc(ArchitecturePdfRenderer renderer) throws Exception {
        // Null collaborators ensure any accidental analysis or catalogue lookup fails.
        var facade = new ExportFacade(null, null, new DiagramProjectionService(), null, null, null, null, null, null);
        var registry = new ExportFormatExtensionRegistry(List.of());
        var controller = new ExportApiController(facade, registry, new LayeredDiagramLayoutService(), renderer);
        assertThat(registry.findByFormatId("pdf")).isEmpty();
        return standaloneSetup(controller).build();
    }

    private static RequirementArchitectureView view(int count) {
        var view = new RequirementArchitectureView(); view.setViewTitle("Current working diagram");
        var nodes = new ArrayList<RequirementElementView>();
        for (int index = 0; index < count; index++) {
            var node = new RequirementElementView(); node.setNodeCode("NODE-" + index); node.setTaxonomySheet("CP");
            node.setTitle("W".repeat(100) + String.format(" HTTP-LABEL-%04d-END", index)); node.setRelevance(.8); nodes.add(node);
        }
        view.setIncludedElements(nodes);
        var edge = new RequirementRelationshipView(); edge.setSourceCode("NODE-0"); edge.setTargetCode("NODE-" + (count - 1));
        edge.setRelationType("SUPPORTS"); edge.setPropagatedRelevance(.8); edge.setOrigin(RelationOrigin.LLM_SUPPORTED);
        edge.setRelationId(Long.MAX_VALUE); edge.setIncludedBecause("HTTP-INCLUSION-END");
        edge.setSeedType(com.taxonomy.model.SeedType.FRAMEWORK_SEED);
        edge.setDerivationReason("HTTP-DERIVATION-END"); edge.setPresenceReason("HTTP-PRESENCE-END"); edge.setConfidence(.125);
        var source = new RelationSearchModel.Node("NODE-0", "CP", "Source", "Source description", false);
        var target = new RelationSearchModel.Node(edge.getTargetCode(), "CP", "Target", "Target description", false);
        var contribution = new RelationSearchModel.Contribution(source, "HTTP-CONTRIBUTION-END", "HTTP-ORIGINAL-QUOTE-END", "HTTP-CONTRIBUTION-CONDITION-END");
        var decision = new RelationSearchModel.Decision(target.id(), RelationSearchModel.Outcome.VERIFIED,
                "HTTP-DECISION-CONTRIBUTION-END", "HTTP-DECISION-QUOTE-END", RelationSearchModel.Necessity.ALTERNATIVE,
                "HTTP-DECISION-CONDITION-END", "HTTP-ALTERNATIVE-GROUP-END", "HTTP-RATIONALE-END", "HTTP-QUESTION-END");
        edge.setRequirementEvidence(List.of(new RelationSearchModel.Edge(contribution, target, "SUPPORTS", RelationSearchModel.Direction.OUTGOING, decision)));
        view.setIncludedRelationships(new ArrayList<>(List.of(edge))); return view;
    }
}
