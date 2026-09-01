package com.taxonomy;

import com.taxonomy.architecture.decision.DecisionChapterDiagramRenderer;
import com.taxonomy.architecture.decision.DecisionRationaleReport;
import com.taxonomy.architecture.decision.DecisionRationaleReportService;
import com.taxonomy.architecture.decision.DecisionRationaleReportService.AnalysisSnapshotProvenance;
import com.taxonomy.architecture.decision.DecisionRationaleReportPlugin;
import com.taxonomy.architecture.decision.DecisionReportTemplateHeaders;
import com.taxonomy.architecture.decision.DecisionReportTemplateProvenance;
import com.taxonomy.architecture.report.ReportRendererRegistry;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.ProductCoverageGap;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.dto.ViewContext;
import com.taxonomy.extension.api.report.ReportRenderContext;
import com.taxonomy.extension.api.report.ReportRenderResult;
import com.taxonomy.extension.api.report.ReportRendererExtension;
import com.taxonomy.portfolio.service.PortfolioFingerprintService;
import com.taxonomy.templates.DecisionRationaleTemplateContract;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "decision-auditor", roles = "ADMIN")
class DecisionRationaleReportTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private TaxonomyService taxonomyService;
    @Autowired
    private DecisionRationaleReportService reportService;
    @Autowired
    private DecisionChapterDiagramRenderer diagramRenderer;
    @Autowired
    private ReportRendererRegistry reportRendererRegistry;
    @Autowired
    private PortfolioFingerprintService fingerprintService;

    @Test
    void reportBuildsCompletePathChaptersAndActualLeadingLeaf() {
        Fixture fixture = completeFixture();
        DecisionRationaleReport report = generate(fixture);

        assertThat(report.status()).isIn(
                DecisionRationaleReport.ReportStatus.FINAL,
                DecisionRationaleReport.ReportStatus.FINAL_WITH_WARNINGS);
        assertThat(report.executiveSummary().leadingLeaf()).isNotNull();
        assertThat(report.executiveSummary().leadingLeaf().code()).isEqualTo(fixture.leafCode());
        assertThat(report.executiveSummary().path())
                .extracting(DecisionRationaleReport.PathStep::code)
                .containsExactlyElementsOf(fixture.pathCodes());
        assertThat(report.chapters()).isNotEmpty();
        assertThat(report.chapters())
                .allSatisfy(chapter -> assertThat(chapter.children()).isNotEmpty());
        assertThat(report.chapters())
                .allSatisfy(chapter -> assertThat(chapter.children())
                        .anyMatch(child -> child.absoluteScore() != null
                                && child.absoluteScore() > 0));
        assertThat(report.metadata().generatedBy()).isEqualTo("decision-auditor");
        assertThat(report.metadata().taxonomyDataFingerprintSha256()).hasSize(64);
        assertThat(report.metadata().analysisSnapshotFingerprintSha256()).hasSize(64);
        assertThat(report.metadata().completenessPercent()).isEqualTo(100.0);
    }

    @Test
    void evaluatedProductCoverageGapIsCompleteAndFinalWithWarnings() {
        Fixture fixture = completeFixture();
        Map<String, List<TaxonomyNode>> childrenMap = taxonomyService.getChildrenMap();
        Map<String, Integer> scores = new LinkedHashMap<>(fixture.scores());
        TaxonomyNode productFamily = taxonomyService.getRootNodes().stream()
      .filter(root -> scores.getOrDefault(root.getCode(), 0) == 0)
      .filter(root -> !childrenMap.getOrDefault(root.getCode(), List.of()).isEmpty())
      .findFirst()
      .orElseThrow();
        List<String> candidateCodes = childrenMap.get(productFamily.getCode()).stream()
      .map(TaxonomyNode::getCode)
      .toList();
        scores.put(productFamily.getCode(), 40);
        candidateCodes.forEach(code -> scores.put(code, 0));
        ProductCoverageGap gap = new ProductCoverageGap(
      productFamily.getCode(),
      productFamily.getName(),
      40,
      candidateCodes,
      "Every concrete candidate remained below the suitability threshold.");

        DecisionRationaleReport report = reportService.generate(
      new DecisionRationaleReportService.DecisionAnalysisInput(
              fixture.requirement(), scores, fixture.reasons(),
              "MOCK", "SUCCESS", List.of(), List.of(gap)),
      new WorkspaceContext(
              "decision-auditor", "test-workspace", "main", "test-repository"),
      viewContext(),
      Locale.GERMAN);

        assertThat(report.status()).isEqualTo(
      DecisionRationaleReport.ReportStatus.FINAL_WITH_WARNINGS);
        assertThat(report.metadata().completenessPercent()).isEqualTo(100.0);
        assertThat(report.productCoverageGaps()).containsExactly(gap);
        assertThat(report.warnings())
      .anyMatch(warning -> warning.contains(productFamily.getCode())
              && warning.contains("Produktabdeckungslücke"));
    }

    @Test
    void missingChildScoreProducesDraftAndDoesNotTreatMissingAsZero() {
        Fixture fixture = completeFixture();
        Map<String, Integer> incompleteScores = new LinkedHashMap<>(fixture.scores());
        String parent = fixture.pathCodes().get(Math.min(1, fixture.pathCodes().size() - 2));
        String missingChild = taxonomyService.getChildrenOf(parent).stream()
                .map(TaxonomyNode::getCode)
                .filter(code -> !code.equals(fixture.pathCodes().get(
                        fixture.pathCodes().indexOf(parent) + 1)))
                .findFirst()
                .orElseGet(() -> taxonomyService.getChildrenOf(parent).get(0).getCode());
        incompleteScores.remove(missingChild);

        DecisionRationaleReport report = reportService.generate(
                new DecisionRationaleReportService.DecisionAnalysisInput(
                        fixture.requirement(), incompleteScores, fixture.reasons(),
                        "MOCK", "SUCCESS", List.of()),
                new WorkspaceContext("decision-auditor", "test-workspace", "main", "test-repository"),
                viewContext(),
                Locale.GERMAN);

        assertThat(report.status()).isEqualTo(
                DecisionRationaleReport.ReportStatus.DRAFT_INCOMPLETE);
        assertThat(report.metadata().completenessPercent()).isLessThan(100.0);
        assertThat(report.chapters().stream()
                .flatMap(chapter -> chapter.children().stream())
                .filter(child -> child.code().equals(missingChild))
                .findFirst()).get()
                .extracting(DecisionRationaleReport.ChildDecision::disposition)
                .isEqualTo(DecisionRationaleReport.Disposition.NOT_EVALUATED);
    }

    @Test
    void immutableSnapshotUsesFrozenHierarchyAndPreservesSnapshotProvenance() {
        Fixture fixture = completeFixture();
        List<TaxonomyNodeDto> frozenTree = taxonomyService.getFullTree();
        String taxonomyFingerprint = fingerprintService.taxonomyFingerprint();
        AnalysisSnapshotProvenance provenance = new AnalysisSnapshotProvenance(
                "snapshot-123", 41L, 42L, 43L, 7,
                Instant.parse("2026-08-20T10:15:30Z"),
                "analysis-author", "mock-model",
                taxonomyFingerprint, "prompt-fingerprint");

        DecisionRationaleReport report = reportService.generate(
                new DecisionRationaleReportService.DecisionAnalysisInput(
                        fixture.requirement(), fixture.scores(), fixture.reasons(),
                        "MOCK", "SUCCESS", List.of(), frozenTree, provenance),
                new WorkspaceContext(
                        "decision-auditor", "test-workspace", "main", "test-repository"),
                viewContext(),
                Locale.GERMAN);

        assertThat(report.metadata().hierarchyFromImmutableSnapshot()).isTrue();
        assertThat(report.metadata().analysisSnapshotId()).isEqualTo("snapshot-123");
        assertThat(report.metadata().requirementVersionNumber()).isEqualTo(7);
        assertThat(report.metadata().analysisCreatedBy()).isEqualTo("analysis-author");
        assertThat(report.metadata().analysisModel()).isEqualTo("mock-model");
        assertThat(report.metadata().taxonomyDataFingerprintSha256())
                .isEqualTo(taxonomyFingerprint);
        assertThat(report.metadata().recordedTaxonomyFingerprintSha256())
                .isEqualTo(taxonomyFingerprint);
        assertThat(report.warnings())
                .noneMatch(warning -> warning.contains("Fingerabdruck stimmt nicht"));
    }

    @Test
    void rendererExtensionsProduceProfessionalSelfContainedArtifacts() throws Exception {
        DecisionRationaleReport report = generate(completeFixture());
        assertThat(reportRendererRegistry.listReportTypeIds())
                .contains("architecture", DecisionRationaleReportPlugin.REPORT_TYPE_ID);
        assertThat(reportRendererRegistry.listDescriptors(
                DecisionRationaleReportPlugin.REPORT_TYPE_ID))
                .extracting(descriptor -> descriptor.id())
                .containsExactly("docx", "html", "json");

        ReportRendererExtension htmlExtension = reportRendererRegistry.getRequired(
                DecisionRationaleReportPlugin.REPORT_TYPE_ID, "html");
        assertThat(htmlExtension.id()).isEqualTo("decision-rationale:html");
        assertThat(htmlExtension.reportModelType()).isEqualTo(DecisionRationaleReport.class);
        String html = htmlExtension.render(ReportRenderContext.ofPayload(report)).utf8();
        assertThat(html)
                .contains("<!doctype html>", "title-page", "running-footer", "<svg")
                .contains("decision-auditor", report.metadata().taxonomyDataVersion())
                .doesNotContain("<script src=");

        ReportRenderResult docxArtifact = reportRendererRegistry.getRequired(
                        DecisionRationaleReportPlugin.REPORT_TYPE_ID, "docx")
                .render(ReportRenderContext.ofPayload(report));
        assertThat(docxArtifact.artifactMetadata())
                .containsKeys(
                        DecisionReportTemplateProvenance.METADATA_TEMPLATE_ID,
                        DecisionReportTemplateProvenance.METADATA_TEMPLATE_COMMIT,
                        DecisionReportTemplateProvenance.METADATA_TEMPLATE_SHA256,
                        DecisionReportTemplateProvenance.METADATA_TEMPLATE_SCHEMA_VERSION);
        byte[] docx = docxArtifact.bytes();
        assertThat(docx).hasSizeGreaterThan(10_000);
        assertThat(docx[0]).isEqualTo((byte) 0x50);
        assertThat(docx[1]).isEqualTo((byte) 0x4B);
        Map<String, byte[]> entries = unzip(docx);
        assertThat(entries.keySet()).contains("word/document.xml", "word/footer1.xml");
        assertThat(entries.keySet()).anyMatch(name -> name.startsWith("word/media/")
                && name.endsWith(".png"));
        String documentXml = new String(entries.get("word/document.xml"), StandardCharsets.UTF_8);
        String footerXml = new String(entries.get("word/footer1.xml"), StandardCharsets.UTF_8);
        assertThat(documentXml).contains(report.title(), "decision-auditor");
        assertThat(footerXml).contains("PAGE", "NUMPAGES", "decision-auditor");

        byte[] json = reportRendererRegistry.getRequired(
                        DecisionRationaleReportPlugin.REPORT_TYPE_ID, "json")
                .render(ReportRenderContext.ofPayload(report)).bytes();
        assertThat(objectMapper.readTree(json).get("metadata").get("generatedBy").asText())
                .isEqualTo("decision-auditor");
    }

    @Test
    @WithMockUser(username = "decision-user", roles = "USER")
    void ordinaryAuthenticatedUserMayGenerateNonMutatingDecisionReport() throws Exception {
        Fixture fixture = completeFixture();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("scores", fixture.scores());
        request.put("reasons", fixture.reasons());
        request.put("businessText", fixture.requirement());
        request.put("provider", "MOCK");
        request.put("analysisStatus", "SUCCESS");
        request.put("language", "de");

        mockMvc.perform(post("/api/decision-report/json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metadata.generatedBy").value("decision-user"));
    }

    @Test
    void diagramSplitsLargeSiblingGroupsAndPreservesEveryChild() {
        DecisionRationaleReport.DecisionChapter chapter = new DecisionRationaleReport.DecisionChapter(
                1, "P", "Parent", "", 100, 0, true, "decision", "comparison",
                java.util.stream.IntStream.range(0, 13)
                        .mapToObj(index -> new DecisionRationaleReport.ChildDecision(
                                "C" + index, "Child " + index, "", index, (double) index,
                                index + 1, index == 12,
                                DecisionRationaleReport.Disposition.LEAF_CANDIDATE,
                                "reason", DecisionRationaleReport.ReasonSource.AI_SCORING, true))
                        .toList(),
                List.of());
        List<DecisionChapterDiagramRenderer.DiagramPanel> panels = diagramRenderer.render(chapter, "de");
        assertThat(panels).hasSize(3);
        String combined = panels.stream()
                .map(DecisionChapterDiagramRenderer.DiagramPanel::svg)
                .reduce("", String::concat);
        for (int index = 0; index < 13; index++) {
            assertThat(combined).contains("C" + index);
        }
    }

    @Test
    void endpointsDeriveAccountAndReturnDocxHtmlAndJson() throws Exception {
        Fixture fixture = completeFixture();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("scores", fixture.scores());
        request.put("reasons", fixture.reasons());
        request.put("businessText", fixture.requirement());
        request.put("provider", "MOCK");
        request.put("analysisStatus", "SUCCESS");
        request.put("language", "de");
        String json = objectMapper.writeValueAsString(request);

        mockMvc.perform(get("/api/decision-report/formats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'docx')]").exists())
                .andExpect(jsonPath("$[?(@.id == 'html')]").exists())
                .andExpect(jsonPath("$[?(@.id == 'json')]").exists());

        mockMvc.perform(post("/api/decision-report/docx")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"taxonomy-decision-rationale-report.docx\""))
                .andExpect(header().exists("X-Taxonomy-Data-SHA256"))
                .andExpect(header().exists("X-Taxonomy-Analysis-SHA256"))
                .andExpect(header().string(
                        DecisionReportTemplateHeaders.HEADER_TEMPLATE_ID,
                        DecisionRationaleTemplateContract.TEMPLATE_ID))
                .andExpect(header().string(
                        DecisionReportTemplateHeaders.HEADER_TEMPLATE_COMMIT,
                        matchesPattern("[0-9a-f]{40}")))
                .andExpect(header().string(
                        DecisionReportTemplateHeaders.HEADER_TEMPLATE_SHA256,
                        matchesPattern("[0-9a-f]{64}")))
                .andExpect(header().string(
                        DecisionReportTemplateHeaders.HEADER_TEMPLATE_SCHEMA_VERSION,
                        "1"));
        mockMvc.perform(post("/api/decision-report/html")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"taxonomy-decision-rationale-report.html\""))
                .andExpect(header().exists("X-Taxonomy-Data-SHA256"))
                .andExpect(header().exists("X-Taxonomy-Analysis-SHA256"));
        mockMvc.perform(post("/api/decision-report/json")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metadata.generatedBy").value("decision-auditor"))
                .andExpect(jsonPath("$.executiveSummary.leadingLeaf.code")
                        .value(fixture.leafCode()))
                .andExpect(jsonPath("$.chapters").isArray())
                .andExpect(jsonPath("$.metadata.analysisSnapshotFingerprintSha256").isString());

        mockMvc.perform(post("/api/decision-report/docx")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scores\":{},\"businessText\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adHocEndpointRejectsUnboundedOrInvalidEvidence() throws Exception {
        Map<String, Object> invalidScore = new LinkedHashMap<>();
        invalidScore.put("scores", Map.of("CP", 101));
        invalidScore.put("businessText", "bounded requirement");
        mockMvc.perform(post("/api/decision-report/json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidScore)))
                .andExpect(status().isBadRequest());

        Map<String, Object> oversizedReason = new LinkedHashMap<>();
        oversizedReason.put("scores", Map.of("CP", 100));
        oversizedReason.put("reasons", Map.of("CP", "x".repeat(50_001)));
        oversizedReason.put("businessText", "bounded requirement");
        mockMvc.perform(post("/api/decision-report/json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(oversizedReason)))
                .andExpect(status().isBadRequest());


        Map<String, Object> invalidGap = new LinkedHashMap<>();
        invalidGap.put("scores", Map.of("CP", 100));
        invalidGap.put("businessText", "bounded requirement");
        invalidGap.put("productCoverageGaps", List.of(Map.of(
      "productFamilyCode", "CP",
      "productFamilyName", "Capability",
      "familyScore", 100,
      "candidateCodes", List.of(),
      "reason", "No suitable product")));
        mockMvc.perform(post("/api/decision-report/json")
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(invalidGap)))
      .andExpect(status().isBadRequest());

        Map<String, Object> oversizedNodeCode = new LinkedHashMap<>();
        oversizedNodeCode.put("scores", Map.of("X".repeat(257), 100));
        oversizedNodeCode.put("businessText", "bounded requirement");
        mockMvc.perform(post("/api/decision-report/json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(oversizedNodeCode)))
                .andExpect(status().isBadRequest());
    }

    private DecisionRationaleReport generate(Fixture fixture) {
        return reportService.generate(
                new DecisionRationaleReportService.DecisionAnalysisInput(
                        fixture.requirement(), fixture.scores(), fixture.reasons(),
                        "MOCK", "SUCCESS", List.of()),
                new WorkspaceContext("decision-auditor", "test-workspace", "main", "test-repository"),
                viewContext(),
                Locale.GERMAN);
    }

    private ViewContext viewContext() {
        return new ViewContext(
                "0123456789abcdef0123456789abcdef01234567",
                "main",
                Instant.parse("2026-08-20T12:00:00Z"),
                false,
                false,
                false);
    }

    private Fixture completeFixture() {
        Map<String, List<TaxonomyNode>> childrenMap = taxonomyService.getChildrenMap();
        List<TaxonomyNode> roots = taxonomyService.getRootNodes();
        TaxonomyNode selectedRoot = roots.stream()
                .filter(root -> !childrenMap.getOrDefault(root.getCode(), List.of()).isEmpty())
                .findFirst()
                .orElseThrow();

        List<TaxonomyNode> path = new ArrayList<>();
        path.add(selectedRoot);
        TaxonomyNode current = selectedRoot;
        Set<String> visited = new java.util.HashSet<>();
        visited.add(current.getCode());
        while (!childrenMap.getOrDefault(current.getCode(), List.of()).isEmpty()) {
            TaxonomyNode next = childrenMap.get(current.getCode()).stream()
                    .filter(child -> !visited.contains(child.getCode()))
                    .findFirst()
                    .orElseThrow();
            path.add(next);
            current = next;
            visited.add(current.getCode());
        }

        Map<String, Integer> scores = new LinkedHashMap<>();
        Map<String, String> reasons = new HashMap<>();
        for (TaxonomyNode root : roots) {
            scores.put(root.getCode(), root.getCode().equals(selectedRoot.getCode()) ? 90 : 0);
        }
        reasons.put(selectedRoot.getCode(),
                "Die Anforderung wird in diesem Taxonomiebereich am unmittelbarsten adressiert.");

        int currentScore = 90;
        for (int index = 0; index < path.size() - 1; index++) {
            TaxonomyNode parent = path.get(index);
            TaxonomyNode selectedChild = path.get(index + 1);
            for (TaxonomyNode child : childrenMap.getOrDefault(parent.getCode(), List.of())) {
                scores.put(child.getCode(), child.getCode().equals(selectedChild.getCode())
                        ? currentScore : 0);
            }
            reasons.put(selectedChild.getCode(),
                    "Dieser Schritt konkretisiert die Anforderung gegenüber den direkten Alternativen am stärksten.");
        }

        return new Fixture(
                "Bereitstellung einer sicheren integrierten Kommunikationsfähigkeit für behördliche Einsatzteams.",
                scores,
                reasons,
                path.stream().map(TaxonomyNode::getCode).toList(),
                path.get(path.size() - 1).getCode());
    }

    private Map<String, byte[]> unzip(byte[] bytes) throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                entries.put(entry.getName(), input.readAllBytes());
            }
        }
        return entries;
    }

    private record Fixture(
            String requirement,
            Map<String, Integer> scores,
            Map<String, String> reasons,
            List<String> pathCodes,
            String leafCode) {
    }
}
