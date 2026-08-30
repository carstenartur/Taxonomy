package com.taxonomy.architecture.decision;

import com.taxonomy.templates.DecisionRationaleTemplateContract;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateManifest;
import com.taxonomy.templates.DocumentTemplateService;
import com.taxonomy.templates.DocumentTemplateService.TemplateFile;
import com.taxonomy.templates.OoxmlTemplatePackageCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DecisionRationaleTemplateRendererTest {

    private static final String TEMPLATE_COMMIT =
            "0123456789abcdef0123456789abcdef01234567";

    @Mock
    private DocumentTemplateService templates;

    @Mock
    private DecisionChapterDiagramRenderer diagrams;

    @Test
    void materializesTheReportIntoTheVersionedTemplateAndEmitsAGenuineDocx()
            throws Exception {
        OoxmlTemplatePackageCodec codec = new OoxmlTemplatePackageCodec();
        byte[] dotx;
        try (InputStream input = DecisionRationaleTemplateRendererTest.class
                .getResourceAsStream(
                        "/" + DecisionRationaleTemplateContract.DEFAULT_RESOURCE)) {
            assertThat(input).isNotNull();
            Map<String, byte[]> parts = new LinkedHashMap<>(
                    codec.unpack(input).parts());
            String footer = new String(
                    parts.get("word/footer1.xml"),
                    StandardCharsets.UTF_8);
            footer = footer.replace(
                    " · template {{taxonomy.template.id}}@"
                            + "{{taxonomy.template.commit}}",
                    "");
            parts.put(
                    "word/footer1.xml",
                    footer.getBytes(StandardCharsets.UTF_8));
            dotx = codec.pack(parts);
        }
        TemplateManifest manifest = new TemplateManifest(
                1,
                DecisionRationaleTemplateContract.TEMPLATE_ID,
                DecisionRationaleTemplateContract.DISPLAY_NAME,
                DecisionRationaleTemplateContract.TEMPLATE_ID + ".dotx",
                OoxmlTemplatePackageCodec.DOTX_MEDIA_TYPE,
                "2026-08-22T16:00:00Z",
                "taxonomy-bootstrap",
                4_096,
                10,
                "package-sha");
        when(templates.downloadCurrentValidated(
                DecisionRationaleTemplateContract.TEMPLATE_ID))
                .thenReturn(new TemplateFile(
                        manifest,
                        TEMPLATE_COMMIT,
                        dotx,
                        Instant.parse("2026-08-22T16:00:00Z")));

        DecisionRationaleDocxRenderer delegate =
                new DecisionRationaleDocxRenderer(diagrams);
        DecisionRationaleTemplateRenderer renderer =
                new DecisionRationaleTemplateRenderer(
                        templates,
                        new DecisionRationaleTemplateContract());
        DecisionRationaleReport report = report();

        byte[] docx = renderer.render(delegate, report);
        Map<String, byte[]> entries = unzip(docx);

        String contentTypes = text(entries, "[Content_Types].xml");
        assertThat(contentTypes)
                .contains("wordprocessingml.document.main+xml")
                .doesNotContain("wordprocessingml.template.main+xml");

        String documentXml = text(entries, "word/document.xml");
        assertThat(documentXml)
                .contains("TAXONOMY · DECISION EVIDENCE")
                .contains(report.title())
                .contains(report.requirement())
                .contains("Executive summary")
                .doesNotContain(DecisionRationaleTemplateContract.BODY_MARKER);

        String headerXml = text(entries, "word/header1.xml");
        assertThat(headerXml)
                .contains(report.title())
                .contains(report.metadata().taxonomyDataVersion());

        String footerXml = text(entries, "word/footer1.xml");
        assertThat(footerXml)
                .contains(report.metadata().generatedBy())
                .doesNotContain(TEMPLATE_COMMIT)
                .contains("PAGE")
                .contains("NUMPAGES");

        String customProperties = text(entries, "docProps/custom.xml");
        assertThat(customProperties)
                .contains("Taxonomy.Template.Id")
                .contains(DecisionRationaleTemplateContract.TEMPLATE_ID)
                .contains("Taxonomy.Template.Commit")
                .contains(TEMPLATE_COMMIT)
                .contains("Taxonomy.Template.PackageSha256")
                .contains("package-sha");

        entries.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith(".xml")
                        || entry.getKey().endsWith(".rels"))
                .forEach(entry -> assertThat(
                        new String(entry.getValue(), StandardCharsets.UTF_8))
                        .as(entry.getKey())
                        .doesNotContain("{{taxonomy."));
    }

    private static DecisionRationaleReport report() {
        Instant generatedAt = Instant.parse("2026-08-22T14:30:00Z");
        DecisionRationaleReport.ReportMetadata metadata =
                new DecisionRationaleReport.ReportMetadata(
                        generatedAt,
                        "template-user",
                        "1.4.0",
                        "build-commit",
                        "catalogue.xlsx",
                        "2026-08",
                        "source-sha",
                        "data-sha",
                        "analysis-sha",
                        "Bundled test catalogue",
                        10,
                        2,
                        "repository",
                        "workspace",
                        "main",
                        "based-on-commit",
                        generatedAt,
                        false,
                        false,
                        "MOCK",
                        "SUCCESS",
                        "mock-model",
                        "snapshot",
                        1L,
                        2L,
                        3L,
                        4,
                        generatedAt,
                        "analysis-author",
                        "recorded-taxonomy-sha",
                        "prompt-sha",
                        true,
                        "Europe/Berlin",
                        4,
                        10,
                        3,
                        100.0);
        return new DecisionRationaleReport(
                "Template-backed decision report",
                "en",
                "Provide a secure architecture decision.",
                DecisionRationaleReport.ReportStatus.FINAL,
                metadata,
                new DecisionRationaleReport.ExecutiveSummary(
                        null,
                        List.of(),
                        "No leading leaf is needed for this template contract test.",
                        "The dynamic report body is rendered after the editable cover."),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null);
    }

    private static Map<String, byte[]> unzip(byte[] archive) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    entries.put(entry.getName(), zip.readAllBytes());
                }
                zip.closeEntry();
            }
        }
        return entries;
    }

    private static String text(Map<String, byte[]> entries, String path) {
        assertThat(entries).containsKey(path);
        return new String(entries.get(path), StandardCharsets.UTF_8);
    }
}
