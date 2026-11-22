package com.taxonomy.catalog.definition;

import com.taxonomy.catalog.definition.CatalogueParentIndex.Entry;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dsl.model.CatalogueDefinitionResolver.Node;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** The project edition has the same source rows and additional German title and description columns. */
public final class BundledCatalogue {
    public static final String BILINGUAL_PATH = "data/bilingual/C3_Taxonomy_Catalogue_25AUG2025.xlsx";
    public static final String BILINGUAL_RESOURCE = "classpath:" + BILINGUAL_PATH;
    public static final String ORIGINAL_SHA256 = "6b19743eff1487a76ea3e5b788d90831ba1705da31790cf58f2d69a979b14130";
    public static final String TITLES_ONLY_SHA256 = "4963672a78db08d2dfc2c8605fd3765d24859da8c713607a06b9cdf5dac23e3a";
    private BundledCatalogue() {}

    /** Both earlier editions share the original English catalogue cells. */
    public static boolean needsTranslationUpgrade(String sha256) {
        return ORIGINAL_SHA256.equals(sha256) || TITLES_ONLY_SHA256.equals(sha256);
    }

    private static class TranslationIndex {
        private static final Map<String, Entry> NODES = read();
        private static Map<String, Entry> read() {
            try (var input = new ClassPathResource(BILINGUAL_PATH).getInputStream(); var book = new XSSFWorkbook(input)) {
                var result = new HashMap<String, Entry>();
                TaxonomyService.readWorkbookNodes(book).nodes().forEach((id, node) -> {
                    if (node.getUuid() != null) result.put(id, new Entry(new Node(id,
                            node.getTaxonomyRoot(), node.getParentCode(), node.getNameEn(), node.getNameDe(),
                            node.getSortOrder(), node.getUuid()), node.getDescriptionEn(), node.getDescriptionDe()));
                });
                return Map.copyOf(result);
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot read the packaged German catalogue text", failure);
            }
        }
    }

    static Map<String, Entry> originalEditionTranslations() { return TranslationIndex.NODES; }
}
