package com.taxonomy.architecture.decision;

import com.taxonomy.catalog.definition.RetainedCatalogueDefinitions;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.composition.analysis.SelectedArchitectureAdmissionModel;
import com.taxonomy.composition.architecture.ArchitectureEditorExportController;
import com.taxonomy.diagram.DiagramSceneEdge;
import com.taxonomy.diagram.DiagramSceneNode;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.dto.AnalysisScope;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.dto.TaxonomyResponsibilityLookup.Assignment;
import com.taxonomy.editor.ArchitectureCommandPort.Context;
import com.taxonomy.editor.ArchitectureEditorController;
import com.taxonomy.editor.ArchitectureEditorExportAdapter;
import com.taxonomy.editor.ArchitectureEditorProjection;
import com.taxonomy.editor.ArchitectureEditorService;
import com.taxonomy.export.ArchitecturePdfRenderer;
import com.taxonomy.export.LayeredDiagramLayoutService;
import com.taxonomy.export.SvgDiagramRenderer;
import com.taxonomy.extension.api.plugin.ExtensionCatalog;
import com.taxonomy.reporting.api.decision.DecisionReportOptions;
import com.taxonomy.reporting.api.document.DecisionTreeOverview.DecisionTreeRow;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Projection of the published synthetic BPMN process. Model inference and persistence are outside this test. */
class CivilApplicationProcessProjectionTest {
    private static final String COMMIT = "a".repeat(40);
    private static final RepositoryContext PROJECT = RepositoryContext.workspace("civil", "applications", "main", "reader");
    private static final List<String> HANDOFFS = List.of(
            "antragsteller ASSIGNED_TO erfassen", "antragsteller ASSIGNED_TO senden",
            "sachbearbeiter ASSIGNED_TO pruefen", "sachbearbeiter ASSIGNED_TO nachfordern", "sachbearbeiter ASSIGNED_TO buchen",
            "erfassen CONSUMES unterlagen", "erfassen RELATED_TO senden", "pruefen CONSUMES datensatz",
            "senden PRODUCES nachricht", "eingang CONSUMES nachricht", "eingang RELATED_TO pruefen",
            "pruefen RELATED_TO entscheidung", "entscheidung RELATED_TO nachfordern", "nachfordern RELATED_TO entscheidung",
            "entscheidung RELATED_TO buchen", "nachfordern PRODUCES nachforderung", "buchen PRODUCES bescheid");
    private static final Map<String, String> BRANCH_EVIDENCE = Map.of(
            "entscheidung RELATED_TO nachfordern", "Bei unvollständigen Unterlagen",
            "nachfordern RELATED_TO entscheidung", "Vollständigkeit erneut geprüft",
            "entscheidung RELATED_TO buchen", "Bei vollständigen Unterlagen");

    @Test
    void preservesSourceResponsibilitiesAndDirectedHandoffsAcrossTreeEditorAndExports() throws Exception {
        String dsl;
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/scenarios/antragsbearbeitung-v1.taxdsl"))) {
            dsl = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        new ArchitectureDslCommands().validateReconciledSource(dsl);
        var repository = mock(ArchitectureEditorService.class);
        var resolver = mock(WorkspaceResolver.class);
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(PROJECT);
        when(resolver.resolveCurrentRepositoryContextForRead()).thenReturn(PROJECT);
        when(repository.read(PROJECT, COMMIT, null)).thenReturn(new ArchitectureEditorService.Document(Context.version(PROJECT, COMMIT),
                dsl, "HISTORICAL", List.of(), List.of(), 0, null, "GIT_CHECKPOINT"));
        var projection = new ArchitectureEditorProjection(new LayeredDiagramLayoutService());
        var editor = new ArchitectureEditorController(repository, projection, resolver);
        var view = Objects.requireNonNull(editor.read("civil", "applications", "main", COMMIT, null).getBody());
        var sceneNodes = view.scene().nodes().stream().collect(Collectors.toMap(DiagramSceneNode::id, Function.identity()));
        var expectedOwners = new HashMap<String, Assignment>();
        for (String id : List.of("erfassen", "senden")) expectedOwners.put(id, new Assignment("antragstellende", "Antragsteller", id));
        for (String id : List.of("eingang", "pruefen", "entscheidung", "nachfordern", "buchen"))
            expectedOwners.put(id, new Assignment("fachstelle", "Sachgebiet", id));
        assertThat(sceneNodes.values().stream().filter(node -> node.responsibility() != null)
                .collect(Collectors.toMap(DiagramSceneNode::id, DiagramSceneNode::responsibility))).isEqualTo(expectedOwners);
        assertThat(view.scene().edges()).extracting(CivilApplicationProcessProjectionTest::directedRelation)
                .containsExactlyInAnyOrderElementsOf(HANDOFFS);

        var evidence = view.model().getEvidence().stream().collect(Collectors.toMap(
                witness -> String.join(" ", witness.getForRelationSource(), witness.getForRelationType(), witness.getForRelationTarget()),
                witness -> witness.getSummary()));
        BRANCH_EVIDENCE.forEach((edge, words) -> assertThat(evidence.get(edge)).as(edge).contains(words));

        var workspace = new WorkspaceContext("reader", "applications", "main", "civil");
        var lookup = new SelectedArchitectureAdmissionModel(resolver, repository, mock(RetainedCatalogueDefinitions.class));
        var binding = lookup.capture(workspace, COMMIT, null, AnalysisScope.full());
        var reports = new DecisionRationaleReportService(mock(TaxonomyService.class), mock(TaxonomyCatalogueMetadataService.class),
                new DecisionReportBuildMetadataService("test", COMMIT), "UTC");
        reports.setResponsibilityLookup(lookup);
        var tree = localCatalogue(view.model());
        // Fixed input scores isolate projection correctness from generative model behavior.
        var scores = new LinkedHashMap<String, Double>();
        tree.forEach(root -> scores.put(root.getCode(), 100d));
        sceneNodes.keySet().forEach(id -> scores.put(id, 100d));
        var input = new DecisionRationaleReportService.DecisionAnalysisInput(view.model().getRequirements().getFirst().getText(),
                scores, Map.of(), "QA_FIXTURE", "SUCCESS", List.of(), tree, null);
        var options = new DecisionReportOptions(null, null, null, null, null, true);
        var decision = reports.generate(input, workspace, null, Locale.GERMAN, options, binding, false).scope().decisionTree();
        assertThat(decision.responsibilitiesByCode()).isEqualTo(expectedOwners);
        assertThat(decision.rows()).filteredOn(row -> row.depth() == 1).extracting(DecisionTreeRow::title)
                .contains("Antrag senden", "Antrag eingegangen", "Unterlagen anfordern", "Antrag buchen",
                        "Antragsunterlagen", "Antrag (Nachricht)", "Antrag (Datensatz)", "Bescheid");

        var exports = new ArchitectureEditorExportController(new ArchitectureEditorExportAdapter(repository, projection), resolver,
                new SvgDiagramRenderer(), new ArchitecturePdfRenderer(), mock(ExtensionCatalog.class));
        var svg = Objects.requireNonNull(exports.svg("civil", "applications", "main", COMMIT, null).getBody());
        var xml = DocumentBuilderFactory.newNSInstance().newDocumentBuilder().parse(new InputSource(new StringReader(svg)));
        var nodes = new HashMap<String, String>();
        var edges = new HashMap<String, String>();
        var groups = xml.getElementsByTagNameNS("http://www.w3.org/2000/svg", "g");
        for (int i = 0; i < groups.getLength(); i++) {
            var group = (Element) groups.item(i);
            if (group.hasAttribute("data-node-id")) nodes.put(group.getAttribute("data-node-id"), group.getTextContent());
            if (group.hasAttribute("data-edge-id")) edges.put(group.getAttribute("data-edge-id"), group.getTextContent());
        }
        assertThat(nodes.keySet()).containsExactlyInAnyOrderElementsOf(sceneNodes.keySet());
        expectedOwners.forEach((id, owner) -> assertThat(nodes.get(id)).as(id).contains(owner.name()));
        assertThat(nodes.get("bescheid")).contains("Bescheid").doesNotContain("Sachgebiet", "Antragsteller");
        assertThat(edges.keySet()).containsExactlyInAnyOrderElementsOf(HANDOFFS);
        BRANCH_EVIDENCE.forEach((edge, words) -> assertThat(edges.get(edge)).as(edge).contains(words));
        assertThat(edges.get("entscheidung RELATED_TO buchen")).doesNotContain("Bei unvollständigen Unterlagen");
        assertThat(edges.get("entscheidung RELATED_TO nachfordern")).doesNotContain("Bei vollständigen Unterlagen");

        var pdf = exports.pdf("civil", "applications", "main", COMMIT, null);
        try (var document = Loader.loadPDF(Objects.requireNonNull(pdf.getBody()))) {
            var text = new PDFTextStripper().getText(document).replaceAll("\\s+", " ");
            assertThat(text).contains("Sachgebiet", "Antragsteller", "Antrag (Nachricht)", "Bescheid");
            BRANCH_EVIDENCE.values().forEach(words -> assertThat(text).contains(words));
        }
    }

    private static String directedRelation(DiagramSceneEdge edge) {
        return String.join(" ", edge.sourceId(), edge.relationType(), edge.targetId());
    }

    /** Project-local catalogue identities are deliberately distinct from NATO C3 catalogue codes. */
    private static List<TaxonomyNodeDto> localCatalogue(CanonicalArchitectureModel model) {
        var roots = new LinkedHashMap<String, TaxonomyNodeDto>();
        for (var element : model.getElements()) {
            var root = roots.computeIfAbsent(element.getTaxonomy(), code -> node(code, code, null));
            root.getChildren().add(node(element.getId(), element.getTitle(), root.getCode()));
        }
        return List.copyOf(roots.values());
    }

    private static TaxonomyNodeDto node(String id, String title, String parent) {
        var node = new TaxonomyNodeDto();
        node.setCode(id);
        node.setNameEn(title);
        node.setNameDe(title);
        node.setLevel(parent == null ? 0 : 1);
        node.setParentCode(parent);
        node.setTaxonomyRoot(parent == null ? id : parent);
        return node;
    }
}
