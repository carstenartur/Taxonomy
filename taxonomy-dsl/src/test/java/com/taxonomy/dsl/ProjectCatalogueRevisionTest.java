package com.taxonomy.dsl;

import com.taxonomy.dsl.merge.TaxDslSemanticMerger;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.serializer.TaxDslSerializer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProjectCatalogueRevisionTest {
    private static final String A = "016cd9ec-0cd7-49b2-a750-6accccc35fad";
    private static final String B = "545ad93f-2e03-408c-a6bc-84cd88f962a6";
    private final TaxDslParser parser = new TaxDslParser();

    @Test void explicitComparisonPreservesIndependentRawBlocksAndAnUnresolvedHistoricBasis() {
        String legacy = "# source comment\r\nelement LOCAL type System {\r\n  x-local: value;\r\n}\r\n";
        var b = java.util.UUID.fromString(B);
        var draft = com.taxonomy.dsl.model.ProjectCatalogue.compareWith(legacy, b);
        assertTrue(draft.endsWith(legacy));
        assertNull(parser.parse(draft).getMeta().catalogueRevision());
        assertEquals(B, parser.parse(draft).getMeta().comparisonCatalogueRevision());
        assertEquals(draft, com.taxonomy.dsl.model.ProjectCatalogue.compareWith(draft, b));
        String fresh = com.taxonomy.dsl.model.ProjectCatalogue.initial("default", java.util.UUID.fromString(A));
        assertTrue(com.taxonomy.dsl.model.ProjectCatalogue.isInitial(fresh, "default"));
        assertFalse(com.taxonomy.dsl.model.ProjectCatalogue.isInitial(fresh + legacy, "default"));
        assertEquals(A, parser.parse(com.taxonomy.dsl.model.ProjectCatalogue.compareWith(fresh, b)).getMeta().catalogueRevision());
    }

    @Test void transfersStayWithinTheirExplicitSelectionAndKeepTheTargetCatalogueAndUnknownBlocks() {
        String a = com.taxonomy.dsl.model.ProjectCatalogue.initial("architecture", java.util.UUID.fromString(A));
        String unrelated = "# persisted\ncustom annotation {\n  flag: true;\n}\n";
        String b = com.taxonomy.dsl.model.ProjectCatalogue.initial("architecture", java.util.UUID.fromString(B));
        String element = "element SINGLE type System {\n  title: Standalone;\n}\n";
        String merged = com.taxonomy.dsl.command.ArchitectureSemanticPatch.copyBlocks(a + unrelated, b + element, java.util.Set.of("element:SINGLE"));
        assertTrue(merged.startsWith(a + unrelated));
        assertEquals(A, parser.parse(merged).getMeta().catalogueRevision());
        assertEquals(1, parser.parse(merged).blocksOfKind("element").size());
    }

    @Test void legacyHasNoClaimedCatalogueAndFormattingPreservesAnExplicitComparison() {
        String old = "meta {\n  language: \"taxdsl\";\n}\n";
        String pinned = old.replace("}", "  catalogueRevision: \"" + A + "\";\n  comparisonCatalogueRevision: \"" + B + "\";\n}");
        assertNull(parser.parse(old).getMeta().catalogueRevision());
        assertNull(parser.parse(old).getMeta().comparisonCatalogueRevision());
        var formatted = parser.parse(new TaxDslSerializer().serialize(parser.parse(pinned)));
        assertEquals(A, formatted.getMeta().catalogueRevision());
        assertEquals(B, formatted.getMeta().comparisonCatalogueRevision());
        var projected = new com.taxonomy.dsl.mapper.AstToModelMapper().map(formatted);
        assertEquals(A, projected.getCatalogueRevision());
        var emitted = new com.taxonomy.dsl.mapper.ModelToAstMapper().toDocument(projected, "project");
        assertEquals(A, emitted.getMeta().catalogueRevision());
        assertEquals(B, emitted.getMeta().comparisonCatalogueRevision());
    }

    @Test void anIndependentModelEditKeepsBothTheBasisAndExplicitComparison() {
        String base = "meta {\n  catalogueRevision: \"" + A + "\";\n}\n";
        String compare = base.replace("}", "  comparisonCatalogueRevision: \"" + B + "\";\n}");
        String edited = base + "element LOCAL type System {\n  title: \"Standalone\";\n}\n";
        var merged = new TaxDslSemanticMerger().merge(base, compare, edited);
        assertTrue(merged.conflicts().isEmpty());
        var model = parser.parse(merged.mergedText());
        assertEquals(A, model.getMeta().catalogueRevision());
        assertEquals(B, model.getMeta().comparisonCatalogueRevision());
        assertEquals(1, model.blocksOfKind("element").size());
    }
}
