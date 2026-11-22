package com.taxonomy.dto;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CatalogueSourceUuidsTest {
    private static final String FIRST = "78505b43-d4b8-4d10-8546-b9ecc42f9803";
    private static final String SECOND = "ddbcd8b7-259e-4f4d-98fa-22e220dc5f34";

    @Test void keepsOnlyUniqueRecordedCatalogueIdentities() {
        var root = node("CI", SECOND, 0);
        root.setChildren(List.of(node("CI-1151", "{" + FIRST.toUpperCase(java.util.Locale.ROOT) + "}", 4),
                node("CI-1021", "legacy-external-id", 3), node("CI-1023", null, 2)));
        assertEquals(Map.of("CI-1151", FIRST), CatalogueSourceUuids.fromTree(List.of(root)));
        assertEquals("57fee725-b3fb-4247-9054-f2eff238b4b8",
                CatalogueSourceUuids.normalize("57fee725b3fb42479054f2eff238b4b8"));
        assertNull(CatalogueSourceUuids.normalize("1-1-1-1-1"));
        assertNull(CatalogueSourceUuids.normalize("00000000-0000-0000-0000-000000000000"));
        assertEquals(Map.of(), CatalogueSourceUuids.fromTree(List.of(
                node("CI-1151", FIRST, 4), node("CI-1151", SECOND, 4), node("CR-1038", SECOND, 3))));
    }

    private static TaxonomyNodeDto node(String code, String uuid, int level) {
        var node = new TaxonomyNodeDto();
        node.setCode(code);
        node.setUuid(uuid);
        node.setLevel(level);
        return node;
    }
}
