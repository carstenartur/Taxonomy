package com.taxonomy.catalog.snapshot;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import com.taxonomy.catalog.service.AppInitializationStateService;
import com.taxonomy.catalog.service.CatalogueOverlayService;
import com.taxonomy.catalog.service.TaxonomyService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Reproducible real-catalogue scalar footprint; this is explicitly not a JVM heap or Lucene benchmark. */
class CatalogueSnapshotFootprintTest {
    @Test
    @SuppressWarnings("unchecked")
    void measuresOneRootVersusAllRootsUsingTheProductionWorkbookParserAndOverlay() throws Exception {
        var repository = mock(TaxonomyNodeRepository.class);
        var taxonomy = new TaxonomyService(repository, mock(TaxonomyRelationRepository.class),
                new AppInitializationStateService());
        var mapper = new ObjectMapper();
        var overlay = new CatalogueOverlayService(mapper, new DefaultResourceLoader(), true,
                "classpath:data/nato-taxonomy.json");
        ReflectionTestUtils.setField(taxonomy, "catalogueOverlayService", overlay);
        Map<String, String> sheets = (Map<String, String>) ReflectionTestUtils.getField(
                TaxonomyService.class, "SHEET_PREFIXES");
        Map<String, TaxonomyNode> nodes = new LinkedHashMap<>();
        Map<String, String> uuids = new HashMap<>();
        byte[] workbookBytes;
        try (var input = new ClassPathResource("data/C3_Taxonomy_Catalogue_25AUG2025.xlsx").getInputStream()) {
            workbookBytes = input.readAllBytes();
        }
        try (var workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(workbookBytes))) {
            for (var sheet : sheets.entrySet()) {
                var root = CatalogueSnapshotServiceTest.node(sheet.getValue(), sheet.getValue(), null, sheet.getKey());
                root.setDescriptionEn("C3 Taxonomy – " + sheet.getKey());
                nodes.put(root.getCode(), root);
                ReflectionTestUtils.invokeMethod(taxonomy, "readSheet", workbook.getSheet(sheet.getKey()),
                        sheet.getValue(), nodes, uuids);
            }
        }
        overlay.applyAndValidate(nodes, uuids, "classpath:data/C3_Taxonomy_Catalogue_25AUG2025.xlsx");
        var captures = new CatalogueSnapshotService(repository, overlay, CatalogueRuntimePolicy.fullCatalogue(),
                CatalogueSnapshotServiceTest.sources(overlay.getOverlayMetadata().sha256()));
        var source = new CatalogueSourceIdentity("fixture-repository", null, "fixture-branch", "fixture-commit");
        List<RootCatalogueSnapshot> full = new ArrayList<>();
        Map<String, Object> rootMeasurements = new LinkedHashMap<>();
        for (String root : sheets.values()) {
            when(repository.findByTaxonomyRootOrderByLevelAscNameEnAsc(root)).thenReturn(nodes.values().stream()
                    .filter(node -> root.equals(node.getTaxonomyRoot()))
                    .sorted(Comparator.comparingInt(TaxonomyNode::getLevel).thenComparing(TaxonomyNode::getNameEn))
                    .toList());
            var snapshot = captures.captureRoot(source, root);
            full.add(snapshot);
            rootMeasurements.put(root, Map.of("nodes", snapshot.nodes().size(),
                    "jsonBytes", mapper.writeValueAsBytes(snapshot).length));
        }
        var cp = full.stream().filter(root -> root.rootCode().equals("CP")).findFirst().orElseThrow();
        int allBytes = mapper.writeValueAsBytes(full).length;
        assertThat(cp.nodes().size()).isLessThan(nodes.size());
        assertThat(mapper.writeValueAsBytes(cp).length).isLessThan(allBytes);
        try (var ignored = captures.bind(source, Set.of("CP"), List.of(cp))) {
            assertThat(taxonomy.getRootCodes()).containsExactly("CP");
            assertThat(taxonomy.getChildrenMap().values().stream().flatMap(List::stream))
                    .allMatch(node -> "CP".equals(node.getTaxonomyRoot()));
            assertThat(taxonomy.getFullTree().getFirst().getCode()).isEqualTo("CP");
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("measurement", "UTF-8 JSON of scalar snapshots before persistence; database IDs are null; no heap or index claim");
        evidence.put("workbookSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(workbookBytes)));
        evidence.put("overlaySha256", overlay.getOverlayMetadata().sha256());
        evidence.put("allRootsNodes", nodes.size());
        evidence.put("allRootsJsonBytes", allBytes);
        evidence.put("roots", rootMeasurements);
        Path output = Path.of("target", "catalogue-snapshot-footprint.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        System.out.println("CATALOGUE_SNAPSHOT_FOOTPRINT " + mapper.writeValueAsString(evidence));
    }
}
