package com.taxonomy;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import com.taxonomy.dsl.export.TaxDslExportService;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.model.RelationType;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The initial catalogue DSL must be referentially closed without inventing virtual components. */
class TaxDslExportClosureTest {
    @Test void virtualRootTemplatesAreNotAcceptedArchitectureRelationships() {
        var root = node("IP", "IP", 0);
        var process = node("BP-1017", "BP", 1);
        var relation = relation(process, root);
        var exporter = exporter(List.of(root, process), List.of(relation));
        var model = exporter.buildCanonicalModel();
        assertEquals(List.of("BP-1017"), model.getElements().stream().map(e -> e.getId()).toList());
        assertTrue(model.getRelations().isEmpty(), "Navigation templates are not concrete architecture facts");
        assertSame(root, relation.getTargetNode(), "Export never mutates the catalogue or saved source relation");
    }

    @Test void concreteRelationshipsAndTheirProvenanceSurviveAlongsideTemplates() {
        var root = node("BP", "BP", 0);
        var process = node("BP-1017", "BP", 1);
        var record = node("IP-1116", "IP", 2);
        var concrete = relation(process, record);
        concrete.setProvenance("Reviewed concrete catalogue relationship");
        var exporter = exporter(List.of(root, process, record), List.of(relation(root, record), concrete));
        var model = exporter.buildCanonicalModel();
        assertEquals(1, model.getRelations().size());
        var kept = model.getRelations().getFirst();
        assertEquals("BP-1017", kept.getSourceId());
        assertEquals("IP-1116", kept.getTargetId());
        assertEquals("CONSUMES", kept.getRelationType());
        assertEquals(concrete.getProvenance(), kept.getProvenance());
        assertEquals("accepted", kept.getStatus());
        var blocks = ArchitectureSemanticPatch.index(exporter.exportAll("closure"));
        assertTrue(blocks.containsKey("element:BP-1017"));
        assertTrue(blocks.containsKey("element:IP-1116"));
        assertFalse(blocks.containsKey("element:BP"));
        assertEquals(1, blocks.values().stream().filter(b -> "relation".equals(b.getKind())).count());
    }

    @Test void emptySeedAndExplicitFullExportKeepDistinctObjectSetsAndTheSameGeneration() {
        var standalone = node("IP-standalone", "IP", 2);
        standalone.setParentCode("IP-parent");
        standalone.setSortOrder(13);
        var exporter = exporter(List.of(standalone), List.of());
        var parser = new com.taxonomy.dsl.parser.TaxDslParser();
        var seed = parser.parse(exporter.newProject("project"));
        var catalogue = parser.parse(exporter.exportAll("catalogue"));
        assertTrue(seed.blocksOfKind("element").isEmpty());
        assertEquals(seed.getMeta().catalogueRevision(), catalogue.getMeta().catalogueRevision());
        var single = catalogue.blocksOfKind("element").getFirst();
        assertEquals("IP-parent", single.property("x-catalogue-parent"));
        assertEquals("13", single.property("x-catalogue-order"));
    }

    @Test void missingConcreteEndpointFailsInsteadOfBeingSilentlyDropped() {
        var process = node("BP-1017", "BP", 1);
        var missing = node("IP-1116", "IP", 2);
        var exporter = exporter(List.of(process), List.of(relation(process, missing)));
        var failure = assertThrows(IllegalStateException.class, () -> exporter.exportAll("closure"));
        assertTrue(failure.getMessage().contains("IP-1116"));
    }

    @Test void rootToRootTemplatesDoNotCreatePhantomElementsOrRelations() {
        var process = node("BP", "BP", 0);
        var information = node("IP", "IP", 0);
        var exporter = exporter(List.of(process, information), List.of(relation(process, information)));
        var model = exporter.buildCanonicalModel();
        assertTrue(model.getElements().isEmpty());
        assertTrue(model.getRelations().isEmpty());
    }

    private static TaxonomyNode node(String id, String root, int level) {
        var node = new TaxonomyNode(); node.setCode(id); node.setTaxonomyRoot(root);
        node.setNameEn(id); node.setLevel(level); return node;
    }
    private static TaxonomyRelation relation(TaxonomyNode source, TaxonomyNode target) {
        var relation = new TaxonomyRelation(); relation.setSourceNode(source); relation.setTargetNode(target);
        relation.setRelationType(RelationType.CONSUMES); return relation;
    }
    private static TaxDslExportService exporter(List<TaxonomyNode> nodes, List<TaxonomyRelation> relations) {
        var nodeRepository = mock(TaxonomyNodeRepository.class);
        var relationRepository = mock(TaxonomyRelationRepository.class);
        when(nodeRepository.findAll()).thenReturn(nodes); when(relationRepository.findAll()).thenReturn(relations);
        var journal = mock(com.taxonomy.catalog.provenance.CatalogueSourceJournal.class);
        when(journal.lockForCapture()).thenReturn(new com.taxonomy.catalog.provenance.CatalogueSourceJournal.Snapshot(
                "016cd9ec-0cd7-49b2-a750-6accccc35fad", java.time.Instant.EPOCH, null, null, null));
        return new TaxDslExportService(nodeRepository, relationRepository, journal);
    }
    public static void main(String[] args) throws Exception {
        int passed = 0, failed = 0;
        for (var method : TaxDslExportClosureTest.class.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Test.class)) continue;
            try { method.invoke(new TaxDslExportClosureTest()); passed++; }
            catch (java.lang.reflect.InvocationTargetException error) { failed++; System.err.println(method.getName() + ": " + error.getCause()); }
        }
        System.out.println("Catalogue DSL closure: " + passed + " passed, " + failed + " failed");
        if (failed > 0) throw new AssertionError(failed + " failures");
    }
}
