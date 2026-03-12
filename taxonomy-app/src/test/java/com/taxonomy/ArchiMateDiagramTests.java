package com.taxonomy;

import com.taxonomy.archimate.ArchiMateElement;
import com.taxonomy.archimate.ArchiMateModel;
import com.taxonomy.archimate.ArchiMateRelationship;
import com.taxonomy.archimate.ArchiMateViewNode;
import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.export.ArchiMateDiagramService;
import com.taxonomy.export.ArchiMateXmlExporter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ArchiMateDiagramTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ArchiMateDiagramService archiMateDiagramService;

    @Autowired
    private ArchiMateXmlExporter archiMateXmlExporter;

    // ── ArchiMateDiagramService – type mapping ──────────────────────────────

    @Test
    void typeMapping_Capabilities() {
        assertThat(ArchiMateDiagramService.toArchiMateType("Capabilities"))
                .isEqualTo("Capability");
    }

    @Test
    void typeMapping_BusinessProcesses() {
        assertThat(ArchiMateDiagramService.toArchiMateType("Business Processes"))
                .isEqualTo("BusinessProcess");
    }

    @Test
    void typeMapping_Services() {
        assertThat(ArchiMateDiagramService.toArchiMateType("Services"))
                .isEqualTo("ApplicationService");
    }

    @Test
    void typeMapping_Applications() {
        assertThat(ArchiMateDiagramService.toArchiMateType("Applications"))
                .isEqualTo("ApplicationComponent");
    }

    @Test
    void typeMapping_InformationProducts() {
        assertThat(ArchiMateDiagramService.toArchiMateType("Information Products"))
                .isEqualTo("DataObject");
    }

    @Test
    void typeMapping_Unknown_defaultsToBusinessObject() {
        assertThat(ArchiMateDiagramService.toArchiMateType("SomethingElse"))
                .isEqualTo("BusinessObject");
        assertThat(ArchiMateDiagramService.toArchiMateType(null))
                .isEqualTo("BusinessObject");
    }

    @Test
    void typeMapping_BusinessRoles() {
        assertThat(ArchiMateDiagramService.toArchiMateType("Business Roles"))
                .isEqualTo("BusinessRole");
    }

    @Test
    void typeMapping_COIServices() {
        assertThat(ArchiMateDiagramService.toArchiMateType("COI Services"))
                .isEqualTo("BusinessService");
    }

    @Test
    void typeMapping_CommunicationsServices() {
        assertThat(ArchiMateDiagramService.toArchiMateType("Communications Services"))
                .isEqualTo("CommunicationNetwork");
    }

    @Test
    void typeMapping_CoreServices() {
        assertThat(ArchiMateDiagramService.toArchiMateType("Core Services"))
                .isEqualTo("TechnologyService");
    }

    @Test
    void typeMapping_UserApplications() {
        assertThat(ArchiMateDiagramService.toArchiMateType("User Applications"))
                .isEqualTo("ApplicationComponent");
    }

    // ── ArchiMateDiagramService – relationship mapping ──────────────────────

    @Test
    void relMapping_SUPPORTS_isServing() {
        assertThat(ArchiMateDiagramService.toArchiMateRelType("SUPPORTS")).isEqualTo("Serving");
    }

    @Test
    void relMapping_ENABLES_isServing() {
        assertThat(ArchiMateDiagramService.toArchiMateRelType("ENABLES")).isEqualTo("Serving");
    }

    @Test
    void relMapping_PRODUCES_isAccess() {
        assertThat(ArchiMateDiagramService.toArchiMateRelType("PRODUCES")).isEqualTo("Access");
        assertThat(ArchiMateDiagramService.toAccessType("PRODUCES")).isEqualTo("Write");
    }

    @Test
    void relMapping_CONSUMES_isAccess() {
        assertThat(ArchiMateDiagramService.toArchiMateRelType("CONSUMES")).isEqualTo("Access");
        assertThat(ArchiMateDiagramService.toAccessType("CONSUMES")).isEqualTo("Read");
    }

    @Test
    void relMapping_IMPLEMENTS_isRealization() {
        assertThat(ArchiMateDiagramService.toArchiMateRelType("IMPLEMENTS"))
                .isEqualTo("Realization");
    }

    @Test
    void relMapping_PART_OF_isComposition() {
        assertThat(ArchiMateDiagramService.toArchiMateRelType("PART_OF"))
                .isEqualTo("Composition");
    }

    @Test
    void relMapping_unknown_defaultsToAssociation() {
        assertThat(ArchiMateDiagramService.toArchiMateRelType("SOMETHING")).isEqualTo("Association");
        assertThat(ArchiMateDiagramService.toArchiMateRelType(null)).isEqualTo("Association");
    }

    @Test
    void relMapping_REALIZES_isRealization() {
        assertThat(ArchiMateDiagramService.toArchiMateRelType("REALIZES")).isEqualTo("Realization");
    }

    @Test
    void relMapping_USES_isServing() {
        assertThat(ArchiMateDiagramService.toArchiMateRelType("USES")).isEqualTo("Serving");
    }

    @Test
    void relMapping_FULFILLS_isRealization() {
        assertThat(ArchiMateDiagramService.toArchiMateRelType("FULFILLS")).isEqualTo("Realization");
    }

    @Test
    void relMapping_ASSIGNED_TO_isAssignment() {
        assertThat(ArchiMateDiagramService.toArchiMateRelType("ASSIGNED_TO")).isEqualTo("Assignment");
    }

    @Test
    void relMapping_DEPENDS_ON_isServing() {
        assertThat(ArchiMateDiagramService.toArchiMateRelType("DEPENDS_ON")).isEqualTo("Serving");
    }

    @Test
    void relMapping_COMMUNICATES_WITH_isFlow() {
        assertThat(ArchiMateDiagramService.toArchiMateRelType("COMMUNICATES_WITH")).isEqualTo("Flow");
    }

    @Test
    void relMapping_RELATED_TO_isAssociation() {
        assertThat(ArchiMateDiagramService.toArchiMateRelType("RELATED_TO")).isEqualTo("Association");
    }

    @Test
    void accessType_isNullForNonAccessRels() {
        assertThat(ArchiMateDiagramService.toAccessType("SUPPORTS")).isNull();
        assertThat(ArchiMateDiagramService.toAccessType(null)).isNull();
    }

    // ── ArchiMateDiagramService – convert ───────────────────────────────────

    @Test
    void convertEmptyModel() {
        DiagramModel model = new DiagramModel("Empty", List.of(), List.of(),
                new DiagramLayout("LR", true));

        ArchiMateModel result = archiMateDiagramService.convert(model);

        assertThat(result.title()).isEqualTo("Empty");
        assertThat(result.elements()).isEmpty();
        assertThat(result.relationships()).isEmpty();
        assertThat(result.organizations()).isEmpty();
        assertThat(result.view().nodes()).isEmpty();
        assertThat(result.view().connections()).isEmpty();
    }

    @Test
    void convertMapsNodesToElements() {
        DiagramModel model = new DiagramModel("Test",
                List.of(
                        new DiagramNode("N1", "Capability A", "Capabilities", 0.9, true, 1),
                        new DiagramNode("N2", "Process B", "Business Processes", 0.7, false, 2)),
                List.of(),
                new DiagramLayout("LR", true));

        ArchiMateModel result = archiMateDiagramService.convert(model);

        assertThat(result.elements()).hasSize(2);
        ArchiMateElement el1 = result.elements().get(0);
        assertThat(el1.id()).isEqualTo("N1");
        assertThat(el1.label()).isEqualTo("Capability A");
        assertThat(el1.archiMateType()).isEqualTo("Capability");
        assertThat(el1.documentation()).contains("0.90");
    }

    @Test
    void convertMapsEdgesToRelationships() {
        DiagramModel model = new DiagramModel("Rels",
                List.of(
                        new DiagramNode("A", "A", "Capabilities", 0.9, true, 1),
                        new DiagramNode("B", "B", "Services", 0.8, false, 3)),
                List.of(new DiagramEdge("e1", "A", "B", "SUPPORTS", 0.8)),
                new DiagramLayout("LR", true));

        ArchiMateModel result = archiMateDiagramService.convert(model);

        assertThat(result.relationships()).hasSize(1);
        ArchiMateRelationship rel = result.relationships().get(0);
        assertThat(rel.id()).isEqualTo("e1");
        assertThat(rel.sourceId()).isEqualTo("A");
        assertThat(rel.targetId()).isEqualTo("B");
        assertThat(rel.archiMateType()).isEqualTo("Serving");
        assertThat(rel.accessType()).isNull();
        assertThat(rel.name()).isEqualTo("SUPPORTS");
    }

    @Test
    void convertProducesRelHasWriteAccessType() {
        DiagramModel model = new DiagramModel("Access",
                List.of(
                        new DiagramNode("A", "A", "Services", 0.9, true, 3),
                        new DiagramNode("B", "B", "Information Products", 0.8, false, 5)),
                List.of(new DiagramEdge("e1", "A", "B", "PRODUCES", 0.8)),
                new DiagramLayout("LR", true));

        ArchiMateModel result = archiMateDiagramService.convert(model);

        ArchiMateRelationship rel = result.relationships().get(0);
        assertThat(rel.archiMateType()).isEqualTo("Access");
        assertThat(rel.accessType()).isEqualTo("Write");
    }

    @Test
    void convertGroupsOrganizationsByType() {
        DiagramModel model = new DiagramModel("Org",
                List.of(
                        new DiagramNode("N1", "Cap 1", "Capabilities", 0.9, true, 1),
                        new DiagramNode("N2", "Cap 2", "Capabilities", 0.8, false, 1),
                        new DiagramNode("N3", "Svc 1", "Services", 0.7, false, 3)),
                List.of(),
                new DiagramLayout("LR", true));

        ArchiMateModel result = archiMateDiagramService.convert(model);

        assertThat(result.organizations()).containsKey("Capabilities");
        assertThat(result.organizations()).containsKey("Services");
        assertThat(result.organizations().get("Capabilities")).containsExactly("N1", "N2");
        assertThat(result.organizations().get("Services")).containsExactly("N3");
    }

    @Test
    void convertAnchorNodeHasLineWidth3() {
        DiagramModel model = new DiagramModel("Anchor",
                List.of(
                        new DiagramNode("N1", "Anchor", "Capabilities", 0.9, true, 1),
                        new DiagramNode("N2", "Regular", "Capabilities", 0.7, false, 1)),
                List.of(),
                new DiagramLayout("LR", true));

        ArchiMateModel result = archiMateDiagramService.convert(model);

        ArchiMateViewNode anchorNode = result.view().nodes().stream()
                .filter(n -> n.id().equals("N1")).findFirst().orElseThrow();
        ArchiMateViewNode regularNode = result.view().nodes().stream()
                .filter(n -> n.id().equals("N2")).findFirst().orElseThrow();

        assertThat(anchorNode.lineWidth()).isEqualTo(3);
        assertThat(regularNode.lineWidth()).isEqualTo(1);
    }

    @Test
    void convertCapabilitiesColorIsYellow() {
        DiagramModel model = new DiagramModel("Color",
                List.of(new DiagramNode("N1", "Cap", "Capabilities", 0.9, true, 1)),
                List.of(),
                new DiagramLayout("LR", true));

        ArchiMateModel result = archiMateDiagramService.convert(model);
        ArchiMateViewNode node = result.view().nodes().get(0);

        assertThat(node.r()).isEqualTo(255);
        assertThat(node.g()).isEqualTo(255);
        assertThat(node.b()).isEqualTo(181);
    }

    @Test
    void convertServicesColorIsBlue() {
        int[] color = ArchiMateDiagramService.toColor("Services");
        assertThat(color).containsExactly(181, 255, 255);
    }

    @Test
    void convertInformationProductsColorIsGreen() {
        int[] color = ArchiMateDiagramService.toColor("Information Products");
        assertThat(color).containsExactly(204, 255, 204);
    }

    @Test
    void convertDefaultColorIsGray() {
        int[] color = ArchiMateDiagramService.toColor("Unknown");
        assertThat(color).containsExactly(224, 224, 224);
    }

    @Test
    void convertBusinessRolesColorIsLightOrange() {
        int[] color = ArchiMateDiagramService.toColor("Business Roles");
        assertThat(color).containsExactly(255, 224, 181);
    }

    @Test
    void convertCOIServicesColorIsBlue() {
        int[] color = ArchiMateDiagramService.toColor("COI Services");
        assertThat(color).containsExactly(181, 255, 255);
    }

    @Test
    void convertCommunicationsServicesColorIsLightBlue() {
        int[] color = ArchiMateDiagramService.toColor("Communications Services");
        assertThat(color).containsExactly(204, 224, 255);
    }

    @Test
    void convertCoreServicesColorIsBlue() {
        int[] color = ArchiMateDiagramService.toColor("Core Services");
        assertThat(color).containsExactly(181, 255, 255);
    }

    @Test
    void convertUserApplicationsColorIsBlue() {
        int[] color = ArchiMateDiagramService.toColor("User Applications");
        assertThat(color).containsExactly(181, 255, 255);
    }

    // ── ArchiMateXmlExporter ────────────────────────────────────────────────

    @Test
    void exportEmptyModelProducesWellFormedXml() {
        DiagramModel model = new DiagramModel("Empty Model", List.of(), List.of(),
                new DiagramLayout("LR", true));
        ArchiMateModel archiModel = archiMateDiagramService.convert(model);

        byte[] xml = archiMateXmlExporter.export(archiModel);
        String xmlStr = new String(xml, StandardCharsets.UTF_8);

        assertThat(xmlStr).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        assertThat(xmlStr).contains("<model ");
        assertThat(xmlStr).contains("</model>");
        assertThat(xmlStr).contains("xmlns=\"http://www.opengroup.org/xsd/archimate/3.0/\"");
        assertThat(xmlStr).contains("identifier=\"id-model-1\"");
        assertThat(xmlStr).contains("<name xml:lang=\"en\">Empty Model</name>");
        assertThat(xmlStr).contains("<elements>");
        assertThat(xmlStr).contains("</elements>");
        assertThat(xmlStr).contains("<relationships>");
        assertThat(xmlStr).contains("</relationships>");
    }

    @Test
    void exportContainsElements() {
        DiagramModel model = new DiagramModel("Model",
                List.of(new DiagramNode("N1", "Test Node", "Capabilities", 0.85, true, 1)),
                List.of(),
                new DiagramLayout("LR", true));
        ArchiMateModel archiModel = archiMateDiagramService.convert(model);

        String xmlStr = new String(archiMateXmlExporter.export(archiModel), StandardCharsets.UTF_8);

        assertThat(xmlStr).contains("identifier=\"id-N1\"");
        assertThat(xmlStr).contains("xsi:type=\"Capability\"");
        assertThat(xmlStr).contains("<name xml:lang=\"en\">Test Node</name>");
        assertThat(xmlStr).contains("<documentation xml:lang=\"en\">Relevance:");
    }

    @Test
    void exportContainsRelationships() {
        DiagramModel model = new DiagramModel("Model",
                List.of(
                        new DiagramNode("A", "A", "Capabilities", 0.9, true, 1),
                        new DiagramNode("B", "B", "Services", 0.8, false, 3)),
                List.of(new DiagramEdge("e1", "A", "B", "SUPPORTS", 0.8)),
                new DiagramLayout("LR", true));
        ArchiMateModel archiModel = archiMateDiagramService.convert(model);

        String xmlStr = new String(archiMateXmlExporter.export(archiModel), StandardCharsets.UTF_8);

        assertThat(xmlStr).contains("identifier=\"id-rel-e1\"");
        assertThat(xmlStr).contains("xsi:type=\"Serving\"");
        assertThat(xmlStr).contains("source=\"id-A\"");
        assertThat(xmlStr).contains("target=\"id-B\"");
    }

    @Test
    void exportAccessRelationshipContainsAccessType() {
        DiagramModel model = new DiagramModel("Model",
                List.of(
                        new DiagramNode("A", "A", "Services", 0.9, true, 3),
                        new DiagramNode("B", "B", "Information Products", 0.8, false, 5)),
                List.of(new DiagramEdge("e1", "A", "B", "PRODUCES", 0.8)),
                new DiagramLayout("LR", true));
        ArchiMateModel archiModel = archiMateDiagramService.convert(model);

        String xmlStr = new String(archiMateXmlExporter.export(archiModel), StandardCharsets.UTF_8);

        assertThat(xmlStr).contains("accessType=\"Write\"");
    }

    @Test
    void exportContainsOrganizations() {
        DiagramModel model = new DiagramModel("Model",
                List.of(new DiagramNode("N1", "Cap", "Capabilities", 0.9, true, 1)),
                List.of(),
                new DiagramLayout("LR", true));
        ArchiMateModel archiModel = archiMateDiagramService.convert(model);

        String xmlStr = new String(archiMateXmlExporter.export(archiModel), StandardCharsets.UTF_8);

        assertThat(xmlStr).contains("<organizations>");
        assertThat(xmlStr).contains("<label xml:lang=\"en\">Capabilities</label>");
        assertThat(xmlStr).contains("identifierRef=\"id-N1\"");
    }

    @Test
    void exportContainsViewNodes() {
        DiagramModel model = new DiagramModel("Model",
                List.of(new DiagramNode("N1", "Cap", "Capabilities", 0.9, true, 1)),
                List.of(),
                new DiagramLayout("LR", true));
        ArchiMateModel archiModel = archiMateDiagramService.convert(model);

        String xmlStr = new String(archiMateXmlExporter.export(archiModel), StandardCharsets.UTF_8);

        assertThat(xmlStr).contains("<views>");
        assertThat(xmlStr).contains("<view ");
        assertThat(xmlStr).contains("viewpoint=\"Layered\"");
        assertThat(xmlStr).contains("identifier=\"id-vn-N1\"");
        assertThat(xmlStr).contains("elementRef=\"id-N1\"");
        assertThat(xmlStr).contains("<fillColor ");
        assertThat(xmlStr).contains("<lineWidth>3</lineWidth>");
    }

    @Test
    void exportContainsViewConnections() {
        DiagramModel model = new DiagramModel("Model",
                List.of(
                        new DiagramNode("A", "A", "Capabilities", 0.9, true, 1),
                        new DiagramNode("B", "B", "Services", 0.8, false, 3)),
                List.of(new DiagramEdge("e1", "A", "B", "SUPPORTS", 0.8)),
                new DiagramLayout("LR", true));
        ArchiMateModel archiModel = archiMateDiagramService.convert(model);

        String xmlStr = new String(archiMateXmlExporter.export(archiModel), StandardCharsets.UTF_8);

        assertThat(xmlStr).contains("identifier=\"id-vc-e1\"");
        assertThat(xmlStr).contains("relationshipRef=\"id-rel-e1\"");
        assertThat(xmlStr).contains("source=\"id-vn-A\"");
        assertThat(xmlStr).contains("target=\"id-vn-B\"");
    }

    @Test
    void exportEscapesSpecialCharacters() {
        DiagramModel model = new DiagramModel("Title & <Test>",
                List.of(new DiagramNode("N1", "Node \"quoted\" & <tag>", "Capabilities", 0.9, true, 1)),
                List.of(),
                new DiagramLayout("LR", true));
        ArchiMateModel archiModel = archiMateDiagramService.convert(model);

        String xmlStr = new String(archiMateXmlExporter.export(archiModel), StandardCharsets.UTF_8);

        assertThat(xmlStr).contains("Title &amp; &lt;Test&gt;");
        assertThat(xmlStr).contains("Node &quot;quoted&quot; &amp; &lt;tag&gt;");
        assertThat(xmlStr).doesNotContain("<tag>");
        assertThat(xmlStr).doesNotContain("\"quoted\"");
    }

    @Test
    void exportNullTitleHandled() {
        DiagramModel model = new DiagramModel(null, List.of(), List.of(),
                new DiagramLayout("LR", true));
        ArchiMateModel archiModel = archiMateDiagramService.convert(model);

        String xmlStr = new String(archiMateXmlExporter.export(archiModel), StandardCharsets.UTF_8);

        // Should not throw, title should be substituted
        assertThat(xmlStr).contains("<name xml:lang=\"en\">");
        assertThat(xmlStr).contains("</model>");
    }

    // ── API Integration Tests ───────────────────────────────────────────────

    @Test
    void archiMateEndpointRejectsMissingBusinessText() throws Exception {
        mockMvc.perform(post("/api/diagram/archimate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessText\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void archiMateEndpointRejectsNullBusinessText() throws Exception {
        mockMvc.perform(post("/api/diagram/archimate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void archiMateEndpointReturnsXmlFile() throws Exception {
        byte[] result = mockMvc.perform(post("/api/diagram/archimate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessText\":\"Provide secure voice and data communications\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"requirement-architecture.xml\""))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        String xmlStr = new String(result, StandardCharsets.UTF_8);
        assertThat(xmlStr).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        assertThat(xmlStr).contains("<model ");
        assertThat(xmlStr).contains("xmlns=\"http://www.opengroup.org/xsd/archimate/3.0/\"");
        assertThat(xmlStr).contains("</model>");
    }
}
