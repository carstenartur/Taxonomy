package com.taxonomy;

import com.taxonomy.analysis.relations.RequirementRelationSearch;
import com.taxonomy.catalog.definition.BundledCatalogue;
import com.taxonomy.catalog.service.TaxonomyService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import com.taxonomy.architecture.pipeline.ArchitecturePipelineInvariantValidator;
import com.taxonomy.architecture.pipeline.ArchitecturePipelineStepRegistry;
import com.taxonomy.architecture.pipeline.ArchitectureViewPipeline;
import com.taxonomy.architecture.service.RequirementArchitectureViewService;
import com.taxonomy.dto.RelationSearchModel.Limits;
import com.taxonomy.dto.RelationSearchModel.Node;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/** Runs production relation search and required-view projection over an explicitly bounded catalogue. */
final class AltscherbitzArchitectureStudy {
    static final String INPUT = "/scenarios/altscherbitz-architecture-input-v1.json";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private AltscherbitzArchitectureStudy() { }

    static List<Map<String, Object>> run(Path output, Function<String, String> complete, String evidenceKind) throws IOException {
        JsonNode inputs = AltscherbitzArchitectureQa.resource(INPUT);
        JsonNode requirements = AltscherbitzArchitectureQa.resource(AltscherbitzArchitectureQa.REQUIREMENTS);
        JsonNode reference = AltscherbitzArchitectureQa.resource(AltscherbitzArchitectureQa.ARCHITECTURE);
        if (!reference.required("requirementsReference").equals(requirements.required("referenceId"))
                || !inputs.required("catalogueSha256").equals(requirements.required("catalogueSha256")))
            throw new IllegalArgumentException("Incompatible source revisions");
        var catalogue = new StudyCatalogue(inputs);
        var search = new RequirementRelationSearch(catalogue, new RelationCompatibilityMatrix(), complete, () -> {}, evidenceKind);
        var projector = new RequirementArchitectureViewService(new ArchitectureViewPipeline(
                new ArchitecturePipelineStepRegistry(List.of()), new ArchitecturePipelineInvariantValidator()));
        Files.createDirectories(output);
        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode stage : requirements.required("cases")) {
            String id = stage.required("id").asString();
            String text = stage.required("requirement").asString();
            JsonNode recorded = inputs.required("historicalReports").required(id);
            if (!AltscherbitzArchitectureQa.sha256(text).equals(recorded.required("requirementSha256").asString()))
                throw new IllegalArgumentException("Historical source mismatch for " + id);
            Map<String, Double> scores = new TreeMap<>();
            JsonNode historicalScores = recorded.required("historicalEffectiveScores");
            historicalScores.properties().forEach(entry -> scores.put(entry.getKey(), entry.getValue().asDouble()));
            var report = search.search(text, scores, new RequirementRelationSearch.Options(new Limits(256, 4, 12, 1000), 12));
            var view = projector.buildFromEvidence(scores, 30, report);
            var observation = JSON.createObjectNode().put("evidenceKind", evidenceKind);
            observation.put("inputCatalogueSha256", inputs.required("catalogueSha256").asString());
            String status = report.isSearchExhausted() ? "SUCCESS" : "PARTIAL";
            observation.putObject("summary").put("id", "focused-" + id).put("status", status);
            observation.set("historicalInput", recorded);
            var analysis = observation.putObject("analysis").put("status", status);
            analysis.putObject("modelBinding").putObject("scope").put("mode", "FOCUSED_RELATION_STUDY");
            analysis.putArray("warnings");
            analysis.set("relationSearchReport", JSON.valueToTree(report));
            analysis.set("architectureView", JSON.valueToTree(view));
            JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve(id + "-observation.json").toFile(), observation);
            var evaluation = AltscherbitzArchitectureQa.evaluate(reference, stage, observation, evidenceKind);
            JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve(id + "-evaluation.json").toFile(), evaluation);
            results.add(evaluation);
        }
        var summary = new LinkedHashMap<String, Object>();
        summary.put("scope", "FOCUSED_RELATION_STUDY");
        summary.put("evidenceKind", evidenceKind);
        summary.put("source", requirements.required("source"));
        summary.put("historicalReportsArchiveSha256", inputs.required("archiveSha256"));
        summary.put("offeredCatalogueNodes", inputs.required("candidates"));
        summary.put("stages", results);
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("study-summary.json").toFile(), summary);
        return List.copyOf(results);
    }

    private static final class StudyCatalogue implements RequirementRelationSearch.InputCatalogue {
        private final Map<String, Node> nodes = new LinkedHashMap<>();
        private final List<Node> roots;

        StudyCatalogue(JsonNode input) throws IOException {
            try (var source = AltscherbitzArchitectureStudy.class.getResourceAsStream("/" + BundledCatalogue.BILINGUAL_PATH)) {
                if (source == null) throw new IOException("Missing source catalogue");
                byte[] bytes = source.readAllBytes();
                if (!AltscherbitzArchitectureQa.sha256(bytes).equals(input.required("catalogueSha256").asString()))
                    throw new IllegalArgumentException("Historical analysis used a different catalogue");
                try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                    var workbook = TaxonomyService.readWorkbookNodes(book).nodes();
                    var selectedRoots = new ArrayList<Node>();
                    for (JsonNode code : input.required("roots")) {
                        var root = workbook.get(code.asString());
                        selectedRoots.add(new Node(root.getCode(), root.getTaxonomyRoot(), root.getNameEn(), "", true));
                    }
                    roots = List.copyOf(selectedRoots);
                    for (JsonNode code : input.required("candidates")) {
                        var entry = workbook.get(code.asString());
                        var node = new Node(entry.getCode(), entry.getTaxonomyRoot(), entry.getNameEn(), entry.getDescriptionEn(), false);
                        nodes.put(node.id(), node);
                    }
                }
            }
        }

        @Override public Node find(String id) { return nodes.get(id); }
        @Override public List<Node> roots() { return roots; }
        @Override public List<Node> children(Node root) {
            return root.container() ? nodes.values().stream().filter(n -> n.root().equals(root.root())).toList() : List.of();
        }
    }
}
