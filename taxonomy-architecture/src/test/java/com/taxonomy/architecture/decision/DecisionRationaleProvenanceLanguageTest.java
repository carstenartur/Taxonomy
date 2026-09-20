package com.taxonomy.architecture.decision;

import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.dto.TaxonomyDataFingerprint;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DecisionRationaleProvenanceLanguageTest {
    @ParameterizedTest
    @ValueSource(strings = {"en", "de"})
    void frozenMetadataUsesLocalizedGeneratedProseAndPreservesEvidence(String language) throws Exception {
        var taxonomy = mock(TaxonomyService.class);
        var catalogue = mock(TaxonomyCatalogueMetadataService.class);
        var build = mock(DecisionReportBuildMetadataService.class);
        when(catalogue.getMetadata()).thenReturn(new TaxonomyCatalogueMetadataService.CatalogueMetadata(
                "live.xml", "live version", "live-sha", "live source", null, null, null, null));
        when(build.current()).thenReturn(new DecisionReportBuildMetadataService.BuildMetadata("1", "build"));
        var node = new TaxonomyNodeDto();node.setCode("A");node.setNameEn("Saved English label");
        node.setChildren(List.of());
        var tree = List.of(node);
        String fingerprint = TaxonomyDataFingerprint.sha256(tree);
        var provenance = new DecisionRationaleReportService.AnalysisSnapshotProvenance(
                "saved-snapshot", 1L, 2L, 3L, 4, Instant.EPOCH, "saved author", "saved model",
                fingerprint, "saved prompt hash");
        var report = new DecisionRationaleReportService(taxonomy, catalogue, build, "UTC").generate(
                new DecisionRationaleReportService.DecisionAnalysisInput("Saved English requirement",
                        Map.of("A", 100), Map.of("A", "Saved English reason"), "MOCK", "SUCCESS",
                        List.of(), tree, provenance),
                new WorkspaceContext("auditor", "workspace", "main", "repo"), null,
                Locale.forLanguageTag(language));
        assertThat(report.metadata().taxonomyDataVersion()).startsWith(
                language.equals("de") ? "Snapshot-Fingerabdruck " : "snapshot fingerprint ");
        assertThat(report.metadata().taxonomyCatalogueResourceSha256()).isEqualTo(
                language.equals("de") ? "Im historischen Snapshot nicht separat gespeichert"
                        : "not persisted separately in the historical snapshot");
        assertThat(report.metadata().recordedTaxonomyFingerprintSha256()).isEqualTo(fingerprint);
        assertThat(report.metadata().analysisSnapshotId()).isEqualTo("saved-snapshot");
        assertThat(report.requirement()).isEqualTo("Saved English requirement");
        assertThat(report.leadingLeaves()).extracting(DecisionRationaleReport.LeafCandidate::title)
                .contains("Saved English label");
        byte[] docx = new DecisionRationaleDocxRenderer(new DecisionChapterDiagramRenderer()).render(report);
        try (var document = new org.apache.poi.xwpf.usermodel.XWPFDocument(new java.io.ByteArrayInputStream(docx))) {
            String text = new org.apache.poi.xwpf.extractor.XWPFWordExtractor(document).getText();
            assertThat(text).contains(report.metadata().taxonomyDataVersion(),
                    report.metadata().taxonomyCatalogueResourceSha256(), "Saved English requirement");
            if (language.equals("de")) assertThat(text).doesNotContain("snapshot fingerprint ",
                    "not persisted separately in the historical snapshot");
        }
        var qa = java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/final-word-review"));
        java.nio.file.Files.write(qa.resolve("generated-provenance-" + language + ".docx"), docx);
        verifyNoInteractions(taxonomy);
    }
}
