package com.taxonomy.analysis.demo;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.CatalogueOverlayService;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

/** The scenario is checked against the application's actual catalogue workbook and overlay. */
class DemoScenarioCatalogueTest {
    private static final Map<String, TaxonomyNode> NODES = new TreeMap<>();
    private static final Map<String, List<String>> CHILDREN = new TreeMap<>();
    private static final Map<String, String> SHEETS = Map.of(
            "Business Processes", "BP", "Business Roles", "BR", "Capabilities", "CP", "COI Services", "CI",
            "Communications Services", "CO", "Core Services", "CR", "Information Products", "IP", "User Applications", "UA");

    @BeforeAll static void loadRealCatalogue() throws Exception {
        var uuids = new HashMap<String, String>();
        var formatter = new DataFormatter(Locale.ROOT);
        try (var in = new ClassPathResource("data/C3_Taxonomy_Catalogue_25AUG2025.xlsx").getInputStream();
             var book = new XSSFWorkbook(in)) {
            for (Sheet sheet : book) {
                String rootCode = SHEETS.get(sheet.getSheetName());
                if (rootCode == null) continue;
                var root = new TaxonomyNode();
                root.setCode(rootCode); root.setTaxonomyRoot(rootCode); root.setNameEn(sheet.getSheetName());
                root.setDescriptionEn("C3 Taxonomy – " + sheet.getSheetName());
                NODES.put(rootCode, root);
                for (Row row : sheet) {
                    if (row.getRowNum() == 0) continue;
                    String code = text(row, 0, formatter), title = text(row, 2, formatter);
                    if (code.isBlank() || title.isBlank()) continue;
                    var node = new TaxonomyNode();
                    node.setCode(code); node.setNameEn(title); node.setUuid(text(row, 1, formatter));
                    node.setDescriptionEn(text(row, 3, formatter)); node.setParentCode(text(row, 4, formatter));
                    node.setState(text(row, 10, formatter)); node.setTaxonomyRoot(rootCode);
                    int level = 1;
                    try { level = (int) Double.parseDouble(text(row, 11, formatter)); } catch (NumberFormatException ignored) { }
                    node.setLevel(Math.max(1, level));
                    NODES.put(code, node);
                    if (!node.getUuid().isBlank()) uuids.put(node.getUuid(), code);
                }
            }
        }
        new CatalogueOverlayService(JsonMapper.builder().build(), new DefaultResourceLoader(), true, "classpath:data/nato-taxonomy.json")
                .applyAndValidate(NODES, uuids, "classpath:data/C3_Taxonomy_Catalogue_25AUG2025.xlsx");
        for (var node : NODES.values()) {
            if (SHEETS.containsValue(node.getCode())) continue;
            String parent = node.getParentCode();
            if (parent != null && !NODES.containsKey(parent)) parent = uuids.get(parent);
            if (parent == null || parent.isBlank()) parent = node.getTaxonomyRoot();
            CHILDREN.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(node.getCode());
        }
    }

    private static String text(Row row, int col, DataFormatter formatter) {
        return formatter.formatCellValue(row.getCell(col)).strip();
    }

    private static DemoCatalogueValidator.CatalogueView view(Map<String, TaxonomyNode> nodes, Map<String, List<String>> children) {
        return new DemoCatalogueValidator.CatalogueView() {
            @Override public Optional<String> title(String code) {
                return Optional.ofNullable(nodes.get(code)).map(TaxonomyNode::getNameEn);
            }
            @Override public List<String> children(String code) { return children.getOrDefault(code, List.of()); }
        };
    }

    private final DemoScenario scenario = new DemoScenarioCatalog().defaultScenario();

    @Test void scenarioMatchesTheActualCatalogue() {
        assertThat(DemoCatalogueValidator.problems(scenario, view(NODES, CHILDREN))).isEmpty();
        // The surveillance mutation target is a real code that the replay never scores positively.
        assertThat(NODES).containsKey("CI-1121");
        for (var iteration : scenario.iterations().values())
            assertThat(iteration.declaredScores().getOrDefault("CI-1121", 0)).isZero();
    }

    @Test void scenarioRejectsUnknownCodesAndMismatchedCatalogue() {
        var renamed = new TreeMap<>(NODES);
        var changed = new TaxonomyNode();
        changed.setCode("UA-1605"); changed.setNameEn("Renamed Applications");
        renamed.put("UA-1605", changed);
        assertThat(DemoCatalogueValidator.problems(scenario, view(renamed, CHILDREN)))
                .anySatisfy(p -> assertThat(p).contains("Catalogue mismatch for UA-1605"));

        var missing = new TreeMap<>(NODES);
        missing.remove("CR-1036");
        assertThat(DemoCatalogueValidator.problems(scenario, view(missing, CHILDREN)))
                .anySatisfy(p -> assertThat(p).contains("Unknown catalogue code CR-1036"));

        var moved = new TreeMap<String, List<String>>(CHILDREN);
        moved.put("UA-1253", List.of("UA-1604"));
        assertThatThrownBy(() -> DemoCatalogueValidator.validate(scenario, view(NODES, moved)))
                .hasMessageContaining("DEMO_SCENARIO_CATALOGUE_MISMATCH").hasMessageContaining("UA-1605 is not a catalogue child of UA-1253");
    }
}
