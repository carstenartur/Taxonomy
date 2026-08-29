package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogueOverlayProposalGeneratorTest {

    private static final List<String> HEADERS = List.of(
            "Page", "UUID", "Title", "Description", "Parent", "Dataset",
            "ExternalID", "Source", "Reference", "Order", "State", "Level");

    @Test
    void generatesByteIdenticalReviewArtifactsAndNeverChangesReviewedMappings(
            @TempDir Path root) throws Exception {
        Path catalogue = root.resolve("fixture.xlsx");
        Path overlay = root.resolve("overlay.json");
        writeWorkbook(catalogue, fixtureRows());
        Files.writeString(overlay, overlay("Medical Treatment Report"), StandardCharsets.UTF_8);
        byte[] overlayBefore = Files.readAllBytes(overlay);

        Path proposalA = root.resolve("first/proposal.json");
        Path reportA = root.resolve("first/review.md");
        Path proposalB = root.resolve("second/proposal.json");
        Path reportB = root.resolve("second/review.md");

        CatalogueOverlayProposalGenerator.Result first =
                CatalogueOverlayProposalGenerator.generate(
                        catalogue, overlay, proposalA, reportA);
        CatalogueOverlayProposalGenerator.Result second =
                CatalogueOverlayProposalGenerator.generate(
                        catalogue, overlay, proposalB, reportB);

        assertThat(first.proposalSha256()).isEqualTo(second.proposalSha256());
        assertThat(Files.readAllBytes(proposalA)).isEqualTo(Files.readAllBytes(proposalB));
        assertThat(Files.readAllBytes(reportA)).isEqualTo(Files.readAllBytes(reportB));
        assertThat(Files.readAllBytes(overlay)).isEqualTo(overlayBefore);
        assertThat(first.strictNodeCount()).isEqualTo(3);
        assertThat(first.semanticChangeCount()).isEqualTo(2);
        assertThat(first.unresolvedCount()).isZero();

        Map<String, Object> document = FlatJson.parseObject(
                Files.readString(proposalA, StandardCharsets.UTF_8));
        assertThat(document.get("automaticPromotionAllowed")).isEqualTo(false);
        assertThat(document.get("algorithmVersion"))
                .isEqualTo(CatalogueOverlayProposalGenerator.ALGORITHM_VERSION);

        Map<String, Map<String, Object>> proposals = proposalsByCode(document);
        assertThat(proposals.get("IP-3000"))
                .containsEntry("status", "REVIEWED_LOCKED")
                .containsEntry("decisionAuthority", "REVIEWED_OVERLAY")
                .containsEntry("proposedParentCode", "IP-2100")
                .containsEntry("reviewRequired", false);
        assertThat(proposals.get("IP-3001"))
                .containsEntry("status", "REVIEW_REQUIRED_CHANGE")
                .containsEntry("currentOverlayParentCode", "IP-2100")
                .containsEntry("proposedParentCode", "IP-2200")
                .containsEntry("reviewRequired", true);
        assertThat(proposals.get("IP-3002"))
                .containsEntry("status", "NEW_MAPPING")
                .containsEntry("sourceParentCode", "source-parent-not-resolved")
                .containsEntry("currentOverlayParentCode", null)
                .containsEntry("proposedParentCode", "IP-2100")
                .containsEntry("reviewRequired", true);

        String report = Files.readString(reportA, StandardCharsets.UTF_8);
        assertThat(report)
                .contains("Review-only artifact")
                .contains("IP-3001")
                .contains("IP-3002")
                .contains("runtime unknown-parent, source-drift, cross-root, cycle");
    }

    @Test
    void failsClosedWhenTheWorkbookTitleDriftsFromTheReviewedOverlay(
            @TempDir Path root) throws Exception {
        Path catalogue = root.resolve("fixture.xlsx");
        Path overlay = root.resolve("overlay.json");
        writeWorkbook(catalogue, fixtureRows());
        Files.writeString(overlay, overlay("Different reviewed title"), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> CatalogueOverlayProposalGenerator.generate(
                catalogue,
                overlay,
                root.resolve("proposal.json"),
                root.resolve("review.md")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected title")
                .hasMessageContaining("IP-3000");
    }

    @Test
    void refusesToOverwriteEitherAuthoritativeInput(@TempDir Path root) throws Exception {
        Path catalogue = root.resolve("fixture.xlsx");
        Path overlay = root.resolve("overlay.json");
        writeWorkbook(catalogue, fixtureRows());
        Files.writeString(overlay, overlay("Medical Treatment Report"), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> CatalogueOverlayProposalGenerator.generate(
                catalogue, overlay, overlay, root.resolve("review.md")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reviewed overlay is read-only input");
        assertThatThrownBy(() -> CatalogueOverlayProposalGenerator.generate(
                catalogue, overlay, root.resolve("proposal.json"), catalogue))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("catalogue workbook is read-only input");
    }


    @Test
    void leavesLowEvidenceNewNodesUnresolvedInsteadOfInventingAParent(
            @TempDir Path root) throws Exception {
        Path catalogue = root.resolve("fixture.xlsx");
        Path overlay = root.resolve("overlay.json");
        List<List<String>> rows = new ArrayList<>(fixtureRows());
        rows.add(row("IP-3999", "u3999", "Opaque Datum", "Unclassified payload",
                "unknown-source-parent", "draft", "3"));
        writeWorkbook(catalogue, rows);
        Files.writeString(overlay, overlay("Medical Treatment Report"), StandardCharsets.UTF_8);

        CatalogueOverlayProposalGenerator.Result result =
                CatalogueOverlayProposalGenerator.generate(
                        catalogue,
                        overlay,
                        root.resolve("proposal.json"),
                        root.resolve("review.md"));

        Map<String, Object> document = FlatJson.parseObject(
                Files.readString(root.resolve("proposal.json"), StandardCharsets.UTF_8));
        assertThat(proposalsByCode(document).get("IP-3999"))
                .containsEntry("status", "NEW_UNRESOLVED")
                .containsEntry("proposedParentCode", null)
                .containsEntry("unresolved", true);
        assertThat(result.unresolvedCount()).isEqualTo(1);
    }

    @Test
    void rejectsAProductClassificationThatWouldHaveChildren(@TempDir Path root)
            throws Exception {
        Path catalogue = root.resolve("fixture.xlsx");
        Path overlay = root.resolve("overlay.json");
        writeWorkbook(catalogue, fixtureRows());
        String invalidOverlay = overlay("Medical Treatment Report")
                .replaceFirst("\"analysisRole\": \"PRODUCT_FAMILY\"",
                        "\"analysisRole\": \"PRODUCT\"");
        Files.writeString(overlay, invalidOverlay, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> CatalogueOverlayProposalGenerator.generate(
                catalogue,
                overlay,
                root.resolve("proposal.json"),
                root.resolve("review.md")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-leaf nodes as PRODUCT")
                .hasMessageContaining("IP-2100");
    }

    @Test
    void commandLineUsesRepositoryRelativeDefaultsAndReportsTheDigest(
            @TempDir Path root) throws Exception {
        Path data = root.resolve("taxonomy-app/src/main/resources/data");
        Files.createDirectories(data);
        Path catalogue = data.resolve("C3_Taxonomy_Catalogue_25AUG2025.xlsx");
        Path overlay = data.resolve("nato-taxonomy.json");
        writeWorkbook(catalogue, fixtureRows());
        Files.writeString(
                overlay,
                overlay("Medical Treatment Report")
                        .replace("fixture.xlsx", "C3_Taxonomy_Catalogue_25AUG2025.xlsx"),
                StandardCharsets.UTF_8);
        var output = new java.io.ByteArrayOutputStream();
        var errors = new java.io.ByteArrayOutputStream();

        int exitCode = CatalogueOverlayProposalGenerator.run(
                new String[]{"--root", root.toString()},
                root,
                new java.io.PrintStream(output, true, StandardCharsets.UTF_8),
                new java.io.PrintStream(errors, true, StandardCharsets.UTF_8));

        assertThat(exitCode).isZero();
        assertThat(errors.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("Catalogue overlay proposal generated")
                .contains("Proposal SHA-256");
        assertThat(root.resolve(
                "target/catalogue-overlay/catalogue-overlay-proposal.json"))
                .isRegularFile();
        assertThat(root.resolve(
                "target/catalogue-overlay/catalogue-overlay-review.md"))
                .isRegularFile();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> proposalsByCode(
            Map<String, Object> document) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Object raw : (List<Object>) document.get("proposals")) {
            Map<String, Object> proposal = (Map<String, Object>) raw;
            result.put((String) proposal.get("code"), proposal);
        }
        return result;
    }

    private static List<List<String>> fixtureRows() {
        return List.of(
                row("IP-2000", "u2000", "Operational Information Products",
                        "Root family", "", "approved", "1"),
                row("IP-2100", "u2100", "Logistics Support Reports",
                        "Logistics movements shipment status reporting", "u2000",
                        "approved", "2"),
                row("IP-2200", "u2200", "Medical Information Products",
                        "Medical treatment health clinical information", "u2000",
                        "approved", "2"),
                row("IP-3000", "u3000", "Medical Treatment Report",
                        "Medical clinical treatment report", "invalid-source-parent",
                        "draft", "3"),
                row("IP-3001", "u3001", "Medical Treatment Report",
                        "Medical clinical treatment report", "invalid-source-parent",
                        "draft", "3"),
                row("IP-3002", "u3002", "Logistics Movement Report",
                        "Logistics shipment movement status report",
                        "source-parent-not-resolved", "draft", "3"));
    }

    private static List<String> row(
            String code,
            String uuid,
            String title,
            String description,
            String parent,
            String state,
            String level) {
        return List.of(
                code, uuid, title, description, parent,
                "", "", "fixture", "", "1", state, level);
    }

    private static String overlay(String reviewedProductTitle) {
        return """
                {
                  "schemaVersion": 2,
                  "mode": "OVERLAY",
                  "baseCatalogue": "fixture.xlsx",
                  "mappingVersion": "fixture-v1",
                  "validation": {
                    "requireExplicitPatchForRoot": "IP",
                    "requireExplicitPatchForState": "draft"
                  },
                  "nodePatches": [
                    {
                      "code": "IP-2100",
                      "expectedTitle": "Logistics Support Reports",
                      "expectedState": "approved",
                      "parentCode": "IP-2000",
                      "analysisRole": "PRODUCT_FAMILY",
                      "secondaryClassificationCodes": [],
                      "confidence": 0.95,
                      "reviewRequired": false,
                      "justification": "Reviewed family."
                    },
                    {
                      "code": "IP-2200",
                      "expectedTitle": "Medical Information Products",
                      "expectedState": "approved",
                      "parentCode": "IP-2000",
                      "analysisRole": "PRODUCT_FAMILY",
                      "secondaryClassificationCodes": [],
                      "confidence": 0.95,
                      "reviewRequired": false,
                      "justification": "Reviewed family."
                    },
                    {
                      "code": "IP-3000",
                      "expectedTitle": "%s",
                      "expectedState": "draft",
                      "parentCode": "IP-2100",
                      "analysisRole": "PRODUCT",
                      "secondaryClassificationCodes": [],
                      "confidence": 0.90,
                      "reviewRequired": false,
                      "justification": "Deliberately reviewed and locked."
                    },
                    {
                      "code": "IP-3001",
                      "expectedTitle": "Medical Treatment Report",
                      "expectedState": "draft",
                      "parentCode": "IP-2100",
                      "analysisRole": "PRODUCT",
                      "secondaryClassificationCodes": [],
                      "confidence": 0.50,
                      "reviewRequired": true,
                      "justification": "Needs review."
                    }
                  ]
                }
                """.formatted(reviewedProductTitle);
    }

    private static void writeWorkbook(Path destination, List<List<String>> rows)
            throws IOException {
        List<List<String>> allRows = new ArrayList<>();
        allRows.add(HEADERS);
        allRows.addAll(rows);

        LinkedHashMap<String, Integer> sharedIndexes = new LinkedHashMap<>();
        for (List<String> row : allRows) {
            for (String value : row) {
                sharedIndexes.computeIfAbsent(value, ignored -> sharedIndexes.size());
            }
        }

        StringBuilder sharedStrings = new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        sharedIndexes.keySet().forEach(value -> sharedStrings.append("<si><t>")
                .append(xml(value)).append("</t></si>"));
        sharedStrings.append("</sst>");

        StringBuilder sheet = new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        for (int rowIndex = 0; rowIndex < allRows.size(); rowIndex++) {
            sheet.append("<row r=\"").append(rowIndex + 1).append("\">");
            List<String> row = allRows.get(rowIndex);
            for (int column = 0; column < row.size(); column++) {
                sheet.append("<c r=\"").append(columnName(column + 1))
                        .append(rowIndex + 1).append("\" t=\"s\"><v>")
                        .append(sharedIndexes.get(row.get(column)))
                        .append("</v></c>");
            }
            sheet.append("</row>");
        }
        sheet.append("</sheetData></worksheet>");

        Files.createDirectories(destination.getParent());
        try (ZipOutputStream archive = new ZipOutputStream(
                Files.newOutputStream(destination), StandardCharsets.UTF_8)) {
            entry(archive, "xl/workbook.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                              xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                      <sheets>
                        <sheet name="Information Products" sheetId="1" r:id="rId1"/>
                      </sheets>
                    </workbook>
                    """);
            entry(archive, "xl/_rels/workbook.xml.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1"
                                    Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"
                                    Target="worksheets/sheet1.xml"/>
                    </Relationships>
                    """);
            entry(archive, "xl/sharedStrings.xml", sharedStrings.toString());
            entry(archive, "xl/worksheets/sheet1.xml", sheet.toString());
        }
    }

    private static void entry(ZipOutputStream archive, String path, String content)
            throws IOException {
        archive.putNextEntry(new ZipEntry(path));
        archive.write(content.getBytes(StandardCharsets.UTF_8));
        archive.closeEntry();
    }

    private static String columnName(int oneBased) {
        StringBuilder result = new StringBuilder();
        int value = oneBased;
        while (value > 0) {
            value--;
            result.append((char) ('A' + value % 26));
            value /= 26;
        }
        return result.reverse().toString();
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
