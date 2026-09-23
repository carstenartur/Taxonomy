package com.taxonomy.analysis.service;

import com.taxonomy.analysis.relations.RequirementRelationSearchService;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.service.CatalogueOverlayService;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.RelationSearchModel;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real workbook and overlay; only the database/remote boundaries are replaced. */
class TaxonomyHierarchyContextTest {
    static final Map<String, TaxonomyNode> nodes = new TreeMap<>();
    static TaxonomyService catalogue;
    static CatalogueOverlayService overlay;
    static TaxonomyNodeRepository repository;
    static LlmService llm;

    @BeforeAll static void loadRealWorkbook() throws Exception {
        Map<String,String> uuids = new HashMap<>();
        DataFormatter f = new DataFormatter(Locale.ROOT);
        try (var in = new ClassPathResource("data/C3_Taxonomy_Catalogue_25AUG2025.xlsx").getInputStream();
             var book = new XSSFWorkbook(in)) {
            for (Sheet sheet : book) {
                if (!List.of("Business Processes", "Business Roles", "Capabilities", "COI Services", "Communications Services", "Core Services", "Information Products", "User Applications").contains(sheet.getSheetName())) continue;
                String rootCode = switch(sheet.getSheetName()) {
                    case "Business Processes" -> "BP"; case "Business Roles" -> "BR";
                    case "Capabilities" -> "CP"; case "COI Services" -> "CI";
                    case "Communications Services" -> "CO"; case "Core Services" -> "CR";
                    case "Information Products" -> "IP"; default -> "UA";
                };
                TaxonomyNode root = new TaxonomyNode(); root.setCode(rootCode); root.setTaxonomyRoot(rootCode);
                root.setNameEn(sheet.getSheetName()); root.setDescriptionEn("Catalogue root " + sheet.getSheetName());
                nodes.put(rootCode, root);
                for (Row row : sheet) {
                    if (row.getRowNum() == 0) continue;
                    String code = text(row,0,f), title = text(row,2,f);
                    if (code.isBlank() || title.isBlank()) continue;
                    TaxonomyNode n = new TaxonomyNode(); n.setCode(code); n.setNameEn(title); n.setUuid(text(row,1,f));
                    n.setDescriptionEn(text(row,3,f)); n.setParentCode(text(row,4,f)); n.setState(text(row,10,f)); n.setTaxonomyRoot(rootCode);
                    String level = text(row,11,f); int l = 1;
                    try { l = (int)Double.parseDouble(level); } catch (NumberFormatException ignored) { }
                    n.setLevel(Math.max(1,l)); nodes.put(code,n);
                    if (!n.getUuid().isBlank()) uuids.put(n.getUuid(),code);
                }
            }
        }
        overlay = new CatalogueOverlayService(JsonMapper.builder().build(), new DefaultResourceLoader(), true, "classpath:data/nato-taxonomy.json");
        overlay.applyAndValidate(nodes,uuids,"classpath:data/C3_Taxonomy_Catalogue_25AUG2025.xlsx");
        repository = mock(TaxonomyNodeRepository.class);
        when(repository.findByCode(anyString())).thenAnswer(a -> Optional.ofNullable(nodes.get(a.getArgument(0))));
        catalogue = new TaxonomyService(repository,null,null);
        ReflectionTestUtils.setField(catalogue,"catalogueOverlayService",overlay);
        llm = new LlmService(null,null,JsonMapper.builder().build(),catalogue,null,null,null);
    }
    static String text(Row row, int col, DataFormatter f) { return f.formatCellValue(row.getCell(col)).strip(); }
    static TaxonomyNode descendant(String root) {
        return nodes.values().stream().filter(n -> root.equals(n.getTaxonomyRoot()) && n.getLevel() >= 3)
            .filter(n -> nodes.containsKey(n.getParentCode()) && !nodes.get(n.getParentCode()).getDescriptionEn().isBlank())
            .findFirst().orElseThrow();
    }
    static String prompt(List<TaxonomyNode> offered) { return ReflectionTestUtils.invokeMethod(llm,"buildNodeListWithContext",offered); }
    @SuppressWarnings("unchecked") static List<TaxonomyNode> semanticPath(TaxonomyService service, String id) {
        assertThat(Arrays.stream(TaxonomyService.class.getMethods()).map(java.lang.reflect.Method::getName))
            .as("The catalogue must expose a semantic path separate from its navigation path")
            .contains("getSemanticPathToRoot");
        return ReflectionTestUtils.invokeMethod(service,"getSemanticPathToRoot",id);
    }
    @Test void businessProcessKeepsItsInheritedSourceDescription() {
        TaxonomyNode n = descendant("BP");
        assertThat(prompt(List.of(n))).contains(nodes.get(n.getParentCode()).getDescriptionEn()).contains(n.getDescriptionEn());
    }
    @Test void overlayClassificationDoesNotBecomeInheritedRestriction() {
        TaxonomyNode n = nodes.get("IP-1051");
        assertThat(overlay.getNodeMetadata(n.getCode()).reviewRequired()).isTrue();
        String rendered = prompt(List.of(n));
        assertThat(rendered).contains("Classification/navigation only").contains(n.getDescriptionEn());
        assertThat(rendered).doesNotContain(nodes.get(n.getParentCode()).getDescriptionEn());
    }
    @Test void mixedParentCandidatesRetainTheirOwnContexts() {
        TaxonomyNode bp = descendant("BP"), cr = descendant("CR");
        assertThat(prompt(List.of(bp,cr)))
            .contains(nodes.get(bp.getParentCode()).getDescriptionEn())
            .contains(nodes.get(cr.getParentCode()).getDescriptionEn());
    }
    @Test void relationAdapterUsesTheSameSemanticAncestorContext() {
        TaxonomyNode n = descendant("BP");
        var adapter = new RequirementRelationSearchService(catalogue,null,null,null);
        RelationSearchModel.Node candidate = ReflectionTestUtils.invokeMethod(adapter,"scalar",n);
        assertThat(candidate.description()).contains(nodes.get(n.getParentCode()).getDescriptionEn()).contains(n.getDescriptionEn());
    }
    @Test void sourceBusinessProcessPathRemainsIntact() {
        String id = descendant("BP").getCode();
        assertThat(semanticPath(catalogue,id)).containsExactlyElementsOf(catalogue.getPathToRoot(id));
    }
    @Test void semanticPathStopsAtOwnOverlayParent() {
        assertThat(semanticPath(catalogue,"IP-1051")).extracting(TaxonomyNode::getCode).containsExactly("IP-1051");
        assertThat(catalogue.getPathToRoot("IP-1051").size()).isGreaterThan(1);
    }
    @Test void deterministicAcceptanceIsNotExternalSemanticApproval() {
        String id = nodes.keySet().stream().filter(overlay::isProduct).filter(c -> !overlay.getNodeMetadata(c).reviewRequired()).findFirst().orElseThrow();
        assertThat(semanticPath(catalogue,id)).extracting(TaxonomyNode::getCode).containsExactly(id);
    }
    @Test void malformedSemanticPathFailsInsteadOfLooping() {
        TaxonomyNode broken = new TaxonomyNode(); broken.setCode("BP"); broken.setNameEn(nodes.get("BP").getNameEn());
        broken.setTaxonomyRoot("BP"); broken.setParentCode("BP");
        TaxonomyNodeRepository repo = mock(TaxonomyNodeRepository.class);
        when(repo.findByCode("BP")).thenReturn(Optional.of(broken));
        TaxonomyService service = new TaxonomyService(repo,null,null);
        ReflectionTestUtils.setField(service,"catalogueOverlayService",overlay);
        assertThatThrownBy(() -> semanticPath(service,"BP")).isInstanceOf(IllegalStateException.class).hasMessageContaining("cycle");
    }
}
