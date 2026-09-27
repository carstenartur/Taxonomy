package com.taxonomy.portfolio.reformulation;

import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.reformulation.ReformulationBaseline;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.GapAnalysisView;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.dto.RequirementElementView;
import com.taxonomy.dto.RequirementRelationshipView;
import com.taxonomy.dto.ViewContext;
import com.taxonomy.portfolio.dto.PortfolioDtos.ElementMappingView;
import com.taxonomy.portfolio.dto.PortfolioDtos.RelationMappingView;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;
import com.taxonomy.portfolio.service.PortfolioScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Uses the real catalogue and stored snapshots; exporting must not invoke synthesis. */
@SpringBootTest(properties = "llm.mock=true")
@AutoConfigureMockMvc
@WithMockUser(username = "architect", roles = "ARCHITECT")
class ReformulationReportTest extends ReformulationWorkflowFixture {
    @Autowired TaxonomyService catalogue;
    @Autowired ReformulationReportService reports;
    @Autowired ReformulationAdoptionService adoptions;

    @Override
    String snapshot(RequirementView req) {
        var job = analyses.createOrReuseJob(project.id(), List.of(req.id()), null, 25,
                UUID.randomUUID().toString(), "architect", context);
        var root = catalogue.getFullTree().stream().filter(n -> "BP".equals(n.getCode())).findFirst().orElseThrow();
        var result = new AnalysisResult(Map.of(root.getCode(), 50), List.of(root));
        result.setStatus("SUCCESS");
        String id = UUID.randomUUID().toString();
        analyses.persistSnapshot(job.items().getFirst().id(), job.id(), project.id(), PortfolioScope.key("architect", context),
                id, "report-fixture", result, null, null, null, null, null,
                "authored-report-fixture", "real-catalogue", "architect", context, 1);
        return id;
    }

    @Test
    void exportsExactSavedRevisionWithoutChangingTheRequirement() throws Exception {
        var proposal = reformulations.create(project.id(), requirement.id(),
                new ReformulationDtos.CreateRequest(requirement.currentVersionId(), snapshot, "de"), "architect", context);
        String revision = base() + "/" + proposal.id() + "/revisions/1";
        mvc.perform(get(revision)).andExpect(status().isOk());
        var before = projects.getRequirement(project.id(), requirement.id(), "architect", context);
        // Compare persisted state on both sides: create() can retain sub-column timestamp precision.
        var beforeProposal = reformulations.get(project.id(), requirement.id(), proposal.id(), "architect", context);
        mvc.perform(get(revision + "/export").param("format", "json"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.schemaVersion").value("reformulation-report-v1"))
                .andExpect(jsonPath("$.source.originalText").value(ORIGINAL))
                .andExpect(jsonPath("$.proposal.revision").value(1));
        assertThat(projects.getRequirement(project.id(), requirement.id(), "architect", context)).isEqualTo(before);
        assertThat(reformulations.get(project.id(), requirement.id(), proposal.id(), "architect", context)).isEqualTo(beforeProposal);
        assertThat(reformulations.runs(project.id(), requirement.id(), proposal.id(), "architect", context)).isEmpty();
    }

    @Test
    void docxRevisionContainsFrozenSourceAndLiteralEvidenceWithBinaryDigest() throws Exception {
        var proposal = reformulations.create(project.id(), requirement.id(),
                new ReformulationDtos.CreateRequest(requirement.currentVersionId(), snapshot, "de"), "architect", context);
        var response = mvc.perform(get(base() + "/" + proposal.id() + "/revisions/1/export").param("format", "docx"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn().getResponse();
        assertThat(response.getContentType()).isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        byte[] bytes = response.getContentAsByteArray();
        assertThat(response.getHeader("X-Content-SHA256"))
                .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        try (var doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            var text = doc.getParagraphs().stream().map(p -> p.getText()).reduce("", (a, b) -> a + "\n" + b);
            assertThat(text).contains("Neuformulierungsangebot", ORIGINAL, "nicht übernommen", "Analysis snapshot");
            assertThat(text).contains("<img src=x onerror=alert(1)>");
        }
    }

    @Test
    void frozenGraphKeepsParallelDirectedMappingsWithoutDuplicatingTheirViewProjection() {
        var baseline = frozenGraphBaseline(true, true);
        var result = reports.frozenArchitecture(baseline);
        assertThat(result.graph().nodes()).extracting("id").containsExactly("BP-1", "BP-2");
        assertThat(result.graph().edges()).extracting("id").containsExactly("mapping-11", "mapping-12");
        assertThat(result.graph().edges()).allSatisfy(edge -> {
            assertThat(edge.sourceId()).isEqualTo("BP-1");
            assertThat(edge.targetId()).isEqualTo("BP-2");
            assertThat(edge.relationType()).isEqualTo("FLOW");
        });
        assertThat(result.gapAnalysisAvailable()).isTrue();
        assertThat(result.gaps()).isEmpty();
        assertThat(result.identity()).containsEntry("Offer workspace branch", "main")
                .containsEntry("Analysis based-on branch", "draft");
    }

    @Test
    void unavailableGapAnalysisIsNotAnExplicitlyEmptySavedInventory() {
        var missing = reports.frozenArchitecture(frozenGraphBaseline(false, false));
        var empty = reports.frozenArchitecture(frozenGraphBaseline(false, true));
        assertThat(missing.gapAnalysisAvailable()).isFalse();
        assertThat(empty.gapAnalysisAvailable()).isTrue();
        assertThat(missing.gaps()).isEmpty();
        assertThat(empty.gaps()).isEmpty();
    }

    @Test
    void tamperedFrozenSnapshotAndDanglingDirectedEndpointAreRejected() {
        var baseline = frozenGraphBaseline(false, true);
        var broken = new java.util.HashMap<>(baseline.frozenContext());
        var detail = (ObjectNode) json.readTree(broken.get("snapshotDetail"));
        ((ObjectNode) detail.get("summary")).put("requirementVersionId", baseline.sourceVersionId() + 100);
        broken.put("snapshotDetail", json.writeValueAsString(detail));
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> reports.frozenArchitecture(copy(baseline, broken))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("source identity");
        var graph = frozenGraphBaseline(true, true);
        var dangling = new java.util.HashMap<>(graph.frozenContext());
        var mappings = (ArrayNode) json.readTree(dangling.get("relationMappings"));
        ((ObjectNode) mappings.get(0)).put("targetCode", "UNKNOWN-NODE");
        dangling.put("relationMappings", json.writeValueAsString(mappings));
        var graphDetail = (ObjectNode) json.readTree(dangling.get("snapshotDetail"));
        graphDetail.set("relationMappings", mappings);
        dangling.put("snapshotDetail", json.writeValueAsString(graphDetail));
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> reports.frozenArchitecture(copy(graph, dangling))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("directed frozen relation");
    }

    @Test
    void realSavedGraphDocxUsesCanonicalEdgeReferenceAndEmbedsFigure() throws Exception {
        var root = catalogue.getFullTree().stream().filter(n -> "BP".equals(n.getCode())).findFirst().orElseThrow();
        var first = root.getChildren().get(0);
        var second = root.getChildren().get(1);
        var view = new RequirementArchitectureView();
        for (var node : List.of(first, second)) {
            var element = new RequirementElementView();
            element.setNodeCode(node.getCode()); element.setTitle(node.getNameEn());
            element.setTaxonomySheet("BP"); element.setRelevance(0.6);
            view.getIncludedElements().add(element);
        }
        var relation = new RequirementRelationshipView();
        relation.setRelationId(999L); relation.setSourceCode(first.getCode()); relation.setTargetCode(second.getCode());
        relation.setRelationType("FLOW"); relation.setPropagatedRelevance(0.6);
        view.getIncludedRelationships().add(relation);
        var analysis = new AnalysisResult(Map.of(first.getCode(), 60, second.getCode(), 60), List.of(root));
        analysis.setStatus("SUCCESS"); analysis.setArchitectureView(view);
        analysis.setViewContext(new ViewContext("frozen-commit", "draft", null, false, false, false));
        var job = analyses.createOrReuseJob(project.id(), List.of(requirement.id()), null, 25,
                UUID.randomUUID().toString(), "architect", context);
        String savedSnapshot = UUID.randomUUID().toString();
        analyses.persistSnapshot(job.items().getFirst().id(), job.id(), project.id(), PortfolioScope.key("architect", context),
                savedSnapshot, "frozen-graph-test", analysis, new GapAnalysisView(), null, null, null, null,
                "prompt", "catalogue", "architect", context, 1);
        var proposal = reformulations.create(project.id(), requirement.id(),
                new ReformulationDtos.CreateRequest(requirement.currentVersionId(), savedSnapshot, "en"), "architect", context);
        var graph = reports.frozenArchitecture(proposal.baseline()).graph();
        long mappingId = json.readTree(proposal.baseline().frozenContext().get("relationMappings")).get(0).path("id").asLong();
        assertThat(graph.nodes()).hasSize(2);
        assertThat(graph.edges()).extracting("id").containsExactly("edge-" + mappingId);
        var response = mvc.perform(get(base() + "/" + proposal.id() + "/revisions/1/export").param("format", "docx"))
                .andExpect(status().isOk()).andReturn().getResponse();
        try (var doc = new XWPFDocument(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(doc.getAllPictures()).isNotEmpty();
            var text = doc.getParagraphs().stream().map(p -> p.getText()).reduce("", (a, b) -> a + "\n" + b);
            assertThat(text).contains("Reformulation offer", first.getNameEn(), second.getNameEn(),
                    "Analysis based-on branch: draft", "Gap analysis recorded");
        }
    }

    @Test
    void exactAdoptionReceiptIsBinaryDocxAndDoesNotRetroactivelyAdoptRevision() throws Exception {
        var proposal = seed();
        var preview = adoptions.preview(project.id(), requirement.id(), proposal.id(), proposal.currentRevision().number(),
                "architect", context);
        String command = UUID.randomUUID().toString();
        adoptions.adopt(project.id(), requirement.id(), proposal.id(), proposal.currentRevision().number(),
                new ReformulationAdoptionDtos.ConfirmRequest(command, preview.content().id(), preview.hash(), true, true,
                        "Reviewed draft"), "architect", context);
        var receipt = mvc.perform(get(base() + "/" + proposal.id() + "/adoptions/" + command + "/export")
                .param("format", "docx")).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("adoption-" + command)))
                .andReturn().getResponse();
        var revision = mvc.perform(get(base() + "/" + proposal.id() + "/revisions/2/export")
                .param("format", "docx")).andExpect(status().isOk()).andReturn().getResponse();
        try (var adopted = new XWPFDocument(new ByteArrayInputStream(receipt.getContentAsByteArray()));
             var saved = new XWPFDocument(new ByteArrayInputStream(revision.getContentAsByteArray()))) {
            assertThat(adopted.getParagraphs().stream().map(p -> p.getText()).toList())
                    .anySatisfy(p -> assertThat(p).contains("Übernahmebeleg"));
            assertThat(saved.getParagraphs().stream().map(p -> p.getText()).toList())
                    .anySatisfy(p -> assertThat(p).contains("nicht übernommen"));
        }
    }

    private ReformulationBaseline frozenGraphBaseline(boolean edges, boolean gaps) {
        var proposal = reformulations.create(project.id(), requirement.id(),
                new ReformulationDtos.CreateRequest(requirement.currentVersionId(), snapshot, "de"), "architect", context);
        var original = proposal.baseline();
        var frozen = new java.util.HashMap<>(original.frozenContext());
        var detail = (ObjectNode) json.readTree(frozen.get("snapshotDetail"));
        var analysis = (ObjectNode) json.readTree(original.snapshotPayload());
        var root = new TaxonomyNodeDto(); root.setCode("BP"); root.setNameDe("Prozesse");
        var first = new TaxonomyNodeDto(); first.setCode("BP-1"); first.setNameDe("Erfassung"); first.setParentCode("BP");
        var second = new TaxonomyNodeDto(); second.setCode("BP-2"); second.setNameDe("Abrechnung"); second.setParentCode("BP");
        root.setChildren(List.of(first, second));
        var catalogueTree = json.readTree(json.writeValueAsString(List.of(root)));
        analysis.set("tree", catalogueTree);
        var viewContext = json.createObjectNode();
        viewContext.put("basedOnBranch", "draft");
        viewContext.put("basedOnCommit", "captured-commit");
        viewContext.put("includesProvisionalRelations", false);
        viewContext.put("projectionStale", false);
        viewContext.put("indexStale", false);
        analysis.set("viewContext", viewContext);
        var view = json.createObjectNode();
        var included = view.putArray("includedRelationships");
        if (edges) for (int id : new int[] {99, 100}) {
            var relation = included.addObject();
            relation.put("relationId", id).put("sourceCode", "BP-1").put("targetCode", "BP-2")
                    .put("relationType", "FLOW").put("propagatedRelevance", 0.5);
        }
        analysis.set("architectureView", view);
        detail.set("analysis", analysis);
        ((ObjectNode) detail.get("summary")).put("branchName", "draft");
        var elements = json.createArrayNode();
        for (int id : new int[] {1, 2}) {
            elements.add(json.readTree(json.writeValueAsString(new ElementMappingView((long) id,
                    original.snapshotId(), "BP-" + id, "Frozen " + id, "BP", 50, 0.5, 0.7,
                    null, "BP > BP-" + id, "Saved mapping", false,
                    null, null, null, null, null, null))));
        }
        detail.set("elementMappings", elements);
        var relations = json.createArrayNode();
        if (edges) for (int id : new int[] {11, 12}) {
            relations.add(json.readTree(json.writeValueAsString(new RelationMappingView((long) id,
                    original.snapshotId(), "BP-1", "BP-2", "FLOW", "SAVED", "impact", 0.5, 0.7,
                    "Saved directed relation", null, null, null, null))));
        }
        detail.set("relationMappings", relations);
        if (gaps) detail.set("gapAnalysis", json.readTree(json.writeValueAsString(new GapAnalysisView())));
        else detail.putNull("gapAnalysis");
        frozen.put("elementMappings", json.writeValueAsString(elements));
        frozen.put("relationMappings", json.writeValueAsString(relations));
        frozen.put("catalogue", json.writeValueAsString(catalogueTree));
        frozen.put("snapshotDetail", json.writeValueAsString(detail));
        return new ReformulationBaseline(new ReformulationBaseline.Scope(original.scope().repositoryId(),
                original.scope().workspaceId(), "main", project.id(), requirement.id()), original.sourceVersionId(),
                original.originalText(), original.originalTextHash(), original.snapshotId(),
                json.writeValueAsString(analysis), frozen, "de", original.algorithmVersion());
    }

    private ReformulationBaseline copy(ReformulationBaseline baseline, java.util.Map<String, String> frozen) {
        return new ReformulationBaseline(baseline.scope(), baseline.sourceVersionId(), baseline.originalText(),
                baseline.originalTextHash(), baseline.snapshotId(), baseline.snapshotPayload(), frozen,
                baseline.language(), baseline.algorithmVersion());
    }
}
