package com.taxonomy.catalog.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogueOverlayServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DataFormatter formatter = new DataFormatter(Locale.ROOT);

    @Test
    void checkedInOverlayRepairsAndClassifiesEveryDraftInformationProduct() throws Exception {
        Map<String, TaxonomyNode> nodes = new LinkedHashMap<>();
        Map<String, String> uuidToCode = new HashMap<>();
        TaxonomyNode root = new TaxonomyNode();
        root.setCode("IP");
        root.setNameEn("Information Products");
        root.setTaxonomyRoot("IP");
        root.setLevel(0);
        nodes.put(root.getCode(), root);

        ClassPathResource workbookResource =
                new ClassPathResource("data/C3_Taxonomy_Catalogue_25AUG2025.xlsx");
        try (InputStream input = workbookResource.getInputStream();
             Workbook workbook = new XSSFWorkbook(input)) {
            boolean first = true;
            for (Row row : workbook.getSheet("Information Products")) {
                if (first) {
                    first = false;
                    continue;
                }
                String code = cell(row, 0);
                String title = cell(row, 2);
                if (code == null || title == null) {
                    continue;
                }
                TaxonomyNode node = new TaxonomyNode();
                node.setCode(code);
                node.setUuid(cell(row, 1));
                node.setNameEn(title);
                node.setDescriptionEn(cell(row, 3));
                node.setParentCode(cell(row, 4));
                node.setTaxonomyRoot("IP");
                node.setState(cell(row, 10));
                node.setLevel(integerCell(row, 11, 1));
                nodes.put(code, node);
                if (node.getUuid() != null) {
                    uuidToCode.put(node.getUuid(), code);
                }
            }
        }

        CatalogueOverlayService service = new CatalogueOverlayService(
                objectMapper,
                new DefaultResourceLoader(),
                true,
                "classpath:data/nato-taxonomy.json");
        CatalogueOverlayService.OverlayApplicationResult result = service.applyAndValidate(
                nodes,
                uuidToCode,
                "classpath:data/C3_Taxonomy_Catalogue_25AUG2025.xlsx");

        assertThat(result.patchCount()).isEqualTo(866);
        assertThat(result.productCount()).isEqualTo(853);
        assertThat(result.productFamilyCount()).isEqualTo(13);
        assertThat(result.maxDepth()).isEqualTo(5);
        assertThat(result.maxDirectChildren()).isLessThan(100);
        assertThat(nodes.values()).filteredOn(node -> "IP".equals(node.getParentCode()))
                .extracting(TaxonomyNode::getCode)
                .containsExactly("IP-1000");
        assertThat(nodes.get("IP-2065").getParentCode()).isEqualTo("IP-1069");
        assertThat(nodes.get("IP-2065").getLevel()).isEqualTo(4);

        // Regression guards for source-title homonyms and obvious cross-domain false positives.
        assertThat(nodes.get("IP-1333").getParentCode()).isEqualTo("IP-2198");
        assertThat(nodes.get("IP-1506").getParentCode()).isEqualTo("IP-1063");
        assertThat(nodes.get("IP-1281").getParentCode()).isEqualTo("IP-2163");
        assertThat(nodes.get("IP-1393").getParentCode()).isEqualTo("IP-2139");
        assertThat(nodes.get("IP-1997").getParentCode()).isEqualTo("IP-2102");
        assertThat(nodes.get("IP-1855").getParentCode()).isEqualTo("IP-2100");
        assertThat(service.getNodeMetadata("IP-1703").secondaryClassificationCodes())
                .contains("IP-1022");
        assertThat(service.isProduct("IP-1659")).isTrue();
        assertThat(service.isProductFamily("IP-2065")).isTrue();
        assertThat(nodes.get("IP-1659").getLevel()).isGreaterThanOrEqualTo(4);

        Set<String> parentCodes = nodes.values().stream()
                .map(TaxonomyNode::getParentCode)
                .collect(Collectors.toSet());
        assertThat(nodes.values().stream()
                .filter(node -> service.isProduct(node.getCode()))
                .map(TaxonomyNode::getCode)
                .filter(parentCodes::contains)
                .toList()).isEmpty();
    }

    @Test
    void unknownOverlayParentFailsClosed(@TempDir Path tempDir) throws Exception {
        Path overlay = tempDir.resolve("overlay.json");
        Files.writeString(overlay, """
                {
                  "schemaVersion": 2,
                  "mode": "OVERLAY",
                  "baseCatalogue": "base.xlsx",
                  "mappingVersion": "test",
                  "nodePatches": [{
                    "code": "IP-1",
                    "expectedTitle": "Product",
                    "expectedState": "draft",
                    "parentCode": "IP-MISSING",
                    "analysisRole": "PRODUCT",
                    "confidence": 1.0,
                    "reviewRequired": false,
                    "justification": "test"
                  }]
                }
                """);

        TaxonomyNode root = new TaxonomyNode();
        root.setCode("IP");
        root.setNameEn("Information Products");
        root.setTaxonomyRoot("IP");
        root.setLevel(0);
        TaxonomyNode product = new TaxonomyNode();
        product.setCode("IP-1");
        product.setNameEn("Product");
        product.setState("draft");
        product.setTaxonomyRoot("IP");
        product.setLevel(1);
        Map<String, TaxonomyNode> nodes = new LinkedHashMap<>();
        nodes.put(root.getCode(), root);
        nodes.put(product.getCode(), product);

        CatalogueOverlayService service = new CatalogueOverlayService(
                objectMapper,
                new DefaultResourceLoader(),
                true,
                overlay.toUri().toString());

        assertThatThrownBy(() -> service.applyAndValidate(
                nodes, Map.of(), tempDir.resolve("base.xlsx").toUri().toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown parent");
    }

    private String cell(Row row, int column) {
        Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) {
            return null;
        }
        String value = formatter.formatCellValue(cell);
        return value == null || value.isBlank() ? null : value.strip();
    }

    private int integerCell(Row row, int column, int fallback) {
        String value = cell(row, column);
        if (value == null) {
            return fallback;
        }
        try {
            return (int) Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
