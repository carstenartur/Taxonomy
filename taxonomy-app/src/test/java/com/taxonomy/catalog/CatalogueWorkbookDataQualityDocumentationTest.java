package com.taxonomy.catalog;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the checked-in catalogue audit and its repository documentation. */
class CatalogueWorkbookDataQualityDocumentationTest {

    private static final String WORKBOOK =
            "/data/C3_Taxonomy_Catalogue_25AUG2025.xlsx";
    private static final String AUDIT =
            "/docs/data/C3_Taxonomy_Catalogue_25AUG2025_audit.csv";
    private static final String EXPECTED_WORKBOOK_SHA256 =
            "6b19743eff1487a76ea3e5b788d90831ba1705da31790cf58f2d69a979b14130";
    private static final String EXPECTED_AUDIT_SHA256 =
            "3691af78ac1511a17836cec3af234aaf59a8b8d693a9e7453fdd3476046c4c5c";

    @Test
    void auditExplainsEveryDeterministicFindingForThePinnedWorkbook() throws Exception {
        byte[] workbookBytes = resourceBytes(WORKBOOK);
        byte[] auditBytes = resourceBytes(AUDIT);
        String audit = new String(auditBytes, StandardCharsets.UTF_8);
        String english = resourceText(
                "/docs/en/C3_TAXONOMY_CATALOGUE_DATA_QUALITY.md");

        assertThat(sha256(workbookBytes)).isEqualTo(EXPECTED_WORKBOOK_SHA256);
        assertThat(sha256(auditBytes)).isEqualTo(EXPECTED_AUDIT_SHA256);
        assertThat(Pattern.compile("(?m)^CAT-2025-\\d+,")
                .matcher(audit).results().count()).isEqualTo(1_204);
        assertThat(countRecordField(audit, 1))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "ERROR", 943L,
                        "WARNING", 226L,
                        "REVIEW", 35L));
        assertThat(countRecordField(audit, 2))
                .containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                        Map.entry("UNASSIGNED_ROOT_NODE", 853L),
                        Map.entry("REPEATED_WHITESPACE", 130L),
                        Map.entry("DUPLICATE_SIBLING_SORT_ORDER", 93L),
                        Map.entry("CONFIRMED_TYPO", 50L),
                        Map.entry("TRACEABILITY_GAP", 32L),
                        Map.entry("BROKEN_CHARACTER_ENCODING", 16L),
                        Map.entry("DANGLING_PARENT", 8L),
                        Map.entry("DECLARED_LEVEL_MISMATCH", 8L),
                        Map.entry("IMPLAUSIBLE_LEVEL", 5L),
                        Map.entry("DUPLICATE_TITLE_DIFFERENT_BRANCHES", 3L),
                        Map.entry("DRAFT_CONTENT", 3L),
                        Map.entry("DUPLICATE_SIBLING_TITLE", 1L),
                        Map.entry("SELF_PARENT_CYCLE", 1L),
                        Map.entry("UNEXPECTED_ROOT_NODE", 1L)));
        assertThat(audit)
                .contains("IP-2065")
                .contains("BR-1220");
        assertThat(english)
                .contains("1,204 findings")
                .contains("853 unassigned Information Products")
                .contains(EXPECTED_WORKBOOK_SHA256)
                .contains(EXPECTED_AUDIT_SHA256);

        try (var workbook = WorkbookFactory.create(
                new ByteArrayInputStream(workbookBytes))) {
            int dataRows = 0;
            for (var sheet : workbook) {
                dataRows += Math.max(0, sheet.getPhysicalNumberOfRows() - 1);
            }
            assertThat(workbook.getNumberOfSheets()).isEqualTo(8);
            assertThat(dataRows).isEqualTo(2_564);

            var informationProducts = workbook.getSheet("Information Products");
            assertThat(informationProducts.getRow(369).getCell(0)
                    .getStringCellValue()).isEqualTo("IP-2065");
            assertThat(informationProducts.getRow(369).getCell(4)
                    .getStringCellValue()).isEqualTo("IP-2065");
            assertThat(new DataFormatter().formatCellValue(
                    informationProducts.getRow(369).getCell(11)))
                    .isEqualTo("14");
        }
    }

    private static byte[] resourceBytes(String path) throws IOException {
        try (var stream = CatalogueWorkbookDataQualityDocumentationTest.class
                .getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing resource: " + path);
            return stream.readAllBytes();
        }
    }

    private static String resourceText(String path) throws IOException {
        return new String(resourceBytes(path), StandardCharsets.UTF_8);
    }

    private static Map<String, Long> countRecordField(String csv, int groupIndex) {
        Pattern recordStart = Pattern.compile(
                "(?m)^CAT-2025-\\d+,(ERROR|WARNING|REVIEW),([^,]+),");
        Map<String, Long> counts = new LinkedHashMap<>();
        recordStart.matcher(csv).results().forEach(result ->
                counts.merge(result.group(groupIndex), 1L, Long::sum));
        return counts;
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
