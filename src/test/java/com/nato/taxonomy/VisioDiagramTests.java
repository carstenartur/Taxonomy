package com.nato.taxonomy;

import com.nato.taxonomy.diagram.DiagramEdge;
import com.nato.taxonomy.diagram.DiagramModel;
import com.nato.taxonomy.diagram.DiagramNode;
import com.nato.taxonomy.dto.RequirementArchitectureView;
import com.nato.taxonomy.dto.RequirementElementView;
import com.nato.taxonomy.dto.RequirementRelationshipView;
import com.nato.taxonomy.service.DiagramProjectionService;
import com.nato.taxonomy.service.VisioDiagramService;
import com.nato.taxonomy.service.VisioPackageBuilder;
import com.nato.taxonomy.visio.VisioDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class VisioDiagramTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DiagramProjectionService projectionService;

    @Autowired
    private VisioDiagramService visioDiagramService;

    @Autowired
    private VisioPackageBuilder visioPackageBuilder;

    // ── DiagramProjectionService Tests ──────────────────────────────────────

    @Test
    void projectReturnsEmptyModelForNullView() {
        DiagramModel model = projectionService.project(null, "Test");
        assertThat(model.nodes()).isEmpty();
        assertThat(model.edges()).isEmpty();
        assertThat(model.title()).isEqualTo("Test");
        assertThat(model.layout().direction()).isEqualTo("LR");
    }

    @Test
    void projectReturnsEmptyModelForEmptyView() {
        RequirementArchitectureView view = new RequirementArchitectureView();
        DiagramModel model = projectionService.project(view, "Empty");
        assertThat(model.nodes()).isEmpty();
        assertThat(model.edges()).isEmpty();
    }

    @Test
    void projectMapsElementsToNodes() {
        RequirementArchitectureView view = new RequirementArchitectureView();

        RequirementElementView el = new RequirementElementView();
        el.setNodeCode("BP");
        el.setTitle("Business Processes");
        el.setTaxonomySheet("Business Processes");
        el.setRelevance(0.91);
        el.setAnchor(true);
        el.setHopDistance(0);
        view.getIncludedElements().add(el);

        DiagramModel model = projectionService.project(view, "Test");

        assertThat(model.nodes()).hasSize(1);
        DiagramNode node = model.nodes().get(0);
        assertThat(node.id()).isEqualTo("BP");
        assertThat(node.label()).isEqualTo("Business Processes");
        assertThat(node.anchor()).isTrue();
        assertThat(node.layer()).isEqualTo(2); // Business Processes → layer 2
    }

    @Test
    void projectAssignsCorrectLayers() {
        RequirementArchitectureView view = new RequirementArchitectureView();

        var sheets = List.of("Capabilities", "Business Processes", "Services",
                             "Applications", "Information Products",
                             "Business Roles", "COI Services", "Core Services",
                             "User Applications", "Communications Services");
        var expectedLayers = List.of(1, 2, 3, 4, 5, 2, 3, 3, 4, 6);

        for (int i = 0; i < sheets.size(); i++) {
            RequirementElementView el = new RequirementElementView();
            el.setNodeCode("N" + i);
            el.setTitle(sheets.get(i));
            el.setTaxonomySheet(sheets.get(i));
            el.setRelevance(0.80);
            el.setAnchor(true);
            view.getIncludedElements().add(el);
        }

        DiagramModel model = projectionService.project(view, "Layers");

        assertThat(model.nodes()).hasSize(10);
        for (int i = 0; i < sheets.size(); i++) {
            final int idx = i;
            DiagramNode node = model.nodes().stream()
                    .filter(n -> n.id().equals("N" + idx))
                    .findFirst().orElseThrow();
            assertThat(node.layer()).isEqualTo(expectedLayers.get(idx));
        }
    }

    @Test
    void projectFiltersLowRelevanceNonAnchors() {
        RequirementArchitectureView view = new RequirementArchitectureView();

        RequirementElementView anchor = new RequirementElementView();
        anchor.setNodeCode("A1");
        anchor.setTitle("Anchor");
        anchor.setRelevance(0.90);
        anchor.setAnchor(true);
        view.getIncludedElements().add(anchor);

        RequirementElementView lowRel = new RequirementElementView();
        lowRel.setNodeCode("L1");
        lowRel.setTitle("Low Relevance");
        lowRel.setRelevance(0.20);
        lowRel.setAnchor(false);
        view.getIncludedElements().add(lowRel);

        DiagramModel model = projectionService.project(view, "Filter");

        assertThat(model.nodes()).hasSize(1);
        assertThat(model.nodes().get(0).id()).isEqualTo("A1");
    }

    @Test
    void projectLimitsNodesTo25() {
        RequirementArchitectureView view = new RequirementArchitectureView();

        for (int i = 0; i < 30; i++) {
            RequirementElementView el = new RequirementElementView();
            el.setNodeCode("N" + i);
            el.setTitle("Node " + i);
            el.setRelevance(0.90 - i * 0.01);
            el.setAnchor(true);
            view.getIncludedElements().add(el);
        }

        DiagramModel model = projectionService.project(view, "Limit");

        assertThat(model.nodes()).hasSize(25);
    }

    @Test
    void projectMapsRelationshipsToEdges() {
        RequirementArchitectureView view = new RequirementArchitectureView();

        RequirementElementView el1 = new RequirementElementView();
        el1.setNodeCode("BP");
        el1.setTitle("BP");
        el1.setRelevance(0.91);
        el1.setAnchor(true);
        view.getIncludedElements().add(el1);

        RequirementElementView el2 = new RequirementElementView();
        el2.setNodeCode("CP");
        el2.setTitle("CP");
        el2.setRelevance(0.68);
        el2.setAnchor(false);
        view.getIncludedElements().add(el2);

        RequirementRelationshipView rel = new RequirementRelationshipView();
        rel.setSourceCode("BP");
        rel.setTargetCode("CP");
        rel.setRelationType("SUPPORTS");
        rel.setPropagatedRelevance(0.68);
        view.getIncludedRelationships().add(rel);

        DiagramModel model = projectionService.project(view, "Edges");

        assertThat(model.edges()).hasSize(1);
        DiagramEdge edge = model.edges().get(0);
        assertThat(edge.sourceId()).isEqualTo("BP");
        assertThat(edge.targetId()).isEqualTo("CP");
        assertThat(edge.relationType()).isEqualTo("SUPPORTS");
    }

    @Test
    void projectExcludesEdgesWhoseNodesAreFiltered() {
        RequirementArchitectureView view = new RequirementArchitectureView();

        RequirementElementView el1 = new RequirementElementView();
        el1.setNodeCode("A");
        el1.setRelevance(0.91);
        el1.setAnchor(true);
        view.getIncludedElements().add(el1);

        // B has low relevance and is not an anchor → should be filtered
        RequirementElementView el2 = new RequirementElementView();
        el2.setNodeCode("B");
        el2.setRelevance(0.10);
        el2.setAnchor(false);
        view.getIncludedElements().add(el2);

        RequirementRelationshipView rel = new RequirementRelationshipView();
        rel.setSourceCode("A");
        rel.setTargetCode("B");
        rel.setRelationType("SUPPORTS");
        view.getIncludedRelationships().add(rel);

        DiagramModel model = projectionService.project(view, "Filter edges");

        assertThat(model.edges()).isEmpty();
    }

    // ── VisioDiagramService Tests ───────────────────────────────────────────

    @Test
    void convertCreatesPageWithShapes() {
        DiagramModel model = new DiagramModel("Test",
                List.of(new DiagramNode("N1", "Node 1", "Capabilities", 0.9, true, 1),
                        new DiagramNode("N2", "Node 2", "Services", 0.7, false, 3)),
                List.of(new DiagramEdge("e1", "N1", "N2", "SUPPORTS", 0.7)),
                new com.nato.taxonomy.diagram.DiagramLayout("LR", true));

        VisioDocument doc = visioDiagramService.convert(model);

        assertThat(doc.getPages()).hasSize(1);
        assertThat(doc.getPages().get(0).getShapes()).hasSize(2);
        assertThat(doc.getPages().get(0).getConnects()).hasSize(1);
    }

    @Test
    void convertHandlesEmptyModel() {
        DiagramModel model = new DiagramModel("Empty", List.of(), List.of(),
                new com.nato.taxonomy.diagram.DiagramLayout("LR", true));

        VisioDocument doc = visioDiagramService.convert(model);

        assertThat(doc.getPages()).hasSize(1);
        assertThat(doc.getPages().get(0).getShapes()).isEmpty();
        assertThat(doc.getPages().get(0).getConnects()).isEmpty();
    }

    // ── VisioPackageBuilder Tests ───────────────────────────────────────────

    @Test
    void buildProducesValidZipWithExpectedEntries() throws Exception {
        DiagramModel model = new DiagramModel("Zip Test",
                List.of(new DiagramNode("N1", "Node 1", "Capabilities", 0.9, true, 1)),
                List.of(),
                new com.nato.taxonomy.diagram.DiagramLayout("LR", true));

        VisioDocument doc = visioDiagramService.convert(model);
        byte[] vsdx = visioPackageBuilder.build(doc);

        assertThat(vsdx).isNotEmpty();

        // Verify it's a valid ZIP containing expected entries
        List<String> entryNames = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(vsdx))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entryNames.add(entry.getName());
            }
        }

        assertThat(entryNames).contains(
                "[Content_Types].xml",
                "_rels/.rels",
                "visio/document.xml",
                "visio/_rels/document.xml.rels",
                "visio/pages/pages.xml",
                "visio/pages/page1.xml",
                "visio/pages/_rels/pages.xml.rels");
    }

    @Test
    void buildContainsShapeDataInPageXml() throws Exception {
        DiagramModel model = new DiagramModel("Shape Test",
                List.of(new DiagramNode("N1", "Test Node", "Capabilities", 0.9, true, 1)),
                List.of(),
                new com.nato.taxonomy.diagram.DiagramLayout("LR", true));

        VisioDocument doc = visioDiagramService.convert(model);
        byte[] vsdx = visioPackageBuilder.build(doc);

        // Extract page1.xml and verify it contains the shape text
        String page1Xml = extractEntry(vsdx, "visio/pages/page1.xml");
        assertThat(page1Xml).contains("Test Node");
        assertThat(page1Xml).contains("PageContents");
    }

    @Test
    void buildEscapesSpecialCharactersInXml() throws Exception {
        DiagramModel model = new DiagramModel("Test & <Special>",
                List.of(new DiagramNode("N1", "Node \"quoted\" & <tag>", "Capabilities", 0.9, true, 1)),
                List.of(),
                new com.nato.taxonomy.diagram.DiagramLayout("LR", true));

        VisioDocument doc = visioDiagramService.convert(model);
        byte[] vsdx = visioPackageBuilder.build(doc);

        String page1Xml = extractEntry(vsdx, "visio/pages/page1.xml");
        assertThat(page1Xml).contains("Node &quot;quoted&quot; &amp; &lt;tag&gt;");
        assertThat(page1Xml).doesNotContain("<tag>");
    }

    // ── API Integration Tests ───────────────────────────────────────────────

    @Test
    void visioEndpointRejectsMissingBusinessText() throws Exception {
        mockMvc.perform(post("/api/diagram/visio")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessText\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void visioEndpointRejectsNullBusinessText() throws Exception {
        mockMvc.perform(post("/api/diagram/visio")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void visioEndpointReturnsVsdxFile() throws Exception {
        byte[] result = mockMvc.perform(post("/api/diagram/visio")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessText\":\"Provide secure voice and data communications\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"requirement-architecture.vsdx\""))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        // Verify it's a valid ZIP
        List<String> entryNames = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(result))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entryNames.add(entry.getName());
            }
        }
        assertThat(entryNames).contains("[Content_Types].xml", "visio/document.xml");
    }

    @Test
    void analyzeWithoutVisioFlagStillWorks() throws Exception {
        // Regression: existing analyze endpoint should be unaffected
        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessText\":\"Provide secure voice communications\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scores").exists())
                .andExpect(jsonPath("$.tree").isArray());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String extractEntry(byte[] zip, String entryName) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(entryName)) {
                    return new String(zis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("Entry not found: " + entryName);
    }
}
