package com.taxonomy.catalog.definition;

import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dsl.model.CatalogueDefinitionResolver.Node;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** The project edition has the same source rows and an additional German title column. */
public final class BundledCatalogue {
    public static final String BILINGUAL_PATH = "data/bilingual/C3_Taxonomy_Catalogue_25AUG2025.xlsx";
    public static final String BILINGUAL_RESOURCE = "classpath:" + BILINGUAL_PATH;
    public static final String ORIGINAL_SHA256 = "6b19743eff1487a76ea3e5b788d90831ba1705da31790cf58f2d69a979b14130";
    private BundledCatalogue() {}

    private static class TranslationIndex {
        private static final Map<String, Node> NODES = read();
        private static Map<String, Node> read() {
            try (var input = new ClassPathResource(BILINGUAL_PATH).getInputStream(); var book = new XSSFWorkbook(input)) {
                var result = new HashMap<String, Node>();
                TaxonomyService.readWorkbookNodes(book).nodes().forEach((id, node) -> {
                    if (node.getUuid() != null && node.getNameDe() != null) result.put(id, new Node(id,
                            node.getTaxonomyRoot(), node.getParentCode(), node.getNameEn(), node.getNameDe(),
                            node.getSortOrder(), node.getUuid()));
                });
                return Map.copyOf(result);
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot read the packaged German catalogue titles", failure);
            }
        }
    }

    static Map<String, Node> originalEditionTranslations() { return TranslationIndex.NODES; }
}
