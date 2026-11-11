package com.taxonomy.editor;

import com.taxonomy.dsl.model.CatalogueDefinition;
import com.taxonomy.export.LayeredDiagramLayoutService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Independent authored DSL documents test visual evidence of one frozen reconciliation. */
class ArchitectureReconciliationProjectionTest {
    private final ArchitectureEditorProjection projection = new ArchitectureEditorProjection(new LayeredDiagramLayoutService());

    @Test void comparisonUsesTheFrozenPreviewAndKeepsCoordinatesStableAcrossDifferentVersions() {
        String base = "element one type CoreService {\n title: \"Original\";\n}\n";
        String local = "element one type CoreService {\n title: \"Local title\";\n}\n";
        String incoming = base + "element two type BusinessProcess {\n title: \"Added upstream\";\n}\n";
        String merged = local + "element two type BusinessProcess {\n title: \"Added upstream\";\n}\n";
        var preview = preview(base, local, incoming, merged);
        var result = projection.compare(preview);
        assertEquals("frozen-preview", result.fingerprint());
        assertEquals(preview.context(), result.context());
        var comparison = result.comparison();
        var original = comparison.scenes().get("base").nodes().getFirst();
        var changed = comparison.scenes().get("local").nodes().getFirst();
        var proposed = comparison.scenes().get("proposed").nodes().stream().filter(n -> n.id().equals("one")).findFirst().orElseThrow();
        assertEquals("Original", original.label());
        assertEquals("Local title", changed.label());
        assertEquals(original.x(), changed.x()); assertEquals(original.y(), changed.y());
        assertEquals(original.x(), proposed.x()); assertEquals(original.y(), proposed.y());
        assertEquals(2, comparison.scenes().get("proposed").nodes().size());
        var title = comparison.properties().stream().filter(row -> row.blockId().equals("element:one") && row.property().equals("title")).findFirst().orElseThrow();
        assertEquals(List.of("Original"), title.base());
        assertEquals(List.of("Local title"), title.local());
        assertEquals(List.of("Original"), title.incoming());
        assertEquals(List.of("Local title"), title.proposed());
        assertEquals("SEMANTIC", title.category());
    }

    @Test void eachSceneUsesItsRecordedCatalogueAndTheLegacyMapContainsOnlyCommonMappings() {
        var oldGeneration = "12c55fba-893b-487d-9b04-eea4b08a33ae";
        var newGeneration = "d9fbd53e-f190-4d44-b3da-3e538056ad6e";
        var oldCatalogue = new CatalogueDefinition("museum-v1", true, List.of(
                new CatalogueDefinition.Root("Museum collections", "MC", "Collection")), List.of());
        var newCatalogue = new CatalogueDefinition("archive-v2", true, List.of(
                new CatalogueDefinition.Root("Archives", "AR", "Collection"),
                new CatalogueDefinition.Root("Accession", "AC", "Accession")), List.of());
        var catalogues = Map.of(oldGeneration, oldCatalogue, newGeneration, newCatalogue);
        var reads = new ArrayList<String>();
        var historical = new ArchitectureEditorProjection(new LayeredDiagramLayoutService(), model -> {
            reads.add(model.getCatalogueRevision());
            return catalogues.get(model.getCatalogueRevision());
        });
        String oldSource = "meta {\n catalogueRevision: \"" + oldGeneration + "\";\n}\n"
                + "element record type Collection {\n title: \"Record\";\n}\n";
        String newSource = oldSource.replace(oldGeneration, newGeneration);
        var comparison = historical.compare(preview(oldSource, oldSource, newSource, newSource)).comparison();
        assertEquals(List.of(oldGeneration, newGeneration), reads);
        assertEquals(oldCatalogue.typeRoots(), comparison.sceneTypeRoots().get("base"));
        assertEquals(oldCatalogue.typeRoots(), comparison.sceneTypeRoots().get("local"));
        assertEquals(newCatalogue.typeRoots(), comparison.sceneTypeRoots().get("incoming"));
        assertEquals(newCatalogue.typeRoots(), comparison.sceneTypeRoots().get("proposed"));
        assertEquals(CatalogueDefinition.standard().typeRoots(), comparison.typeRoots());
        var before = comparison.scenes().get("local").nodes().getFirst();
        var after = comparison.scenes().get("incoming").nodes().getFirst();
        assertNotEquals(before.layer(), after.layer());
        assertEquals(before.x(), after.x());
        assertEquals(before.y(), after.y());
    }

    @Test void presentationAndExchangeEvidenceChangesRemainSeparateAndRemovedObjectsRemainVisibleInTheirSource() {
        String base = "element one type CoreService {\n title: \"Original\";\n x-exchange-artifact: \"native-proof\";\n}\n"
                + "view overview {\n include: one;\n layout: \"LR\";\n}\n";
        String local = base.replace("native-proof", "changed-native-proof").replace("\"LR\"", "\"TB\"");
        var comparison = projection.compare(preview(base, local, "", local)).comparison();
        assertTrue(comparison.scenes().get("incoming").nodes().isEmpty());
        assertEquals(1, comparison.scenes().get("base").nodes().size());
        assertEquals("EXCHANGE_EVIDENCE", comparison.properties().stream().filter(row -> row.property().equals("x-exchange-artifact")).findFirst().orElseThrow().category());
        assertEquals("PRESENTATION", comparison.properties().stream().filter(row -> row.property().equals("layout")).findFirst().orElseThrow().category());
    }

    @Test void unresolvedChoicesHaveNoInventedProposedModel() {
        String base = "element one type CoreService {\n title: \"Original\";\n}\n";
        String local = base.replace("Original", "Local");
        var unresolved = new ArchitectureEditorService.ReconciliationPreview(preview(base, local, base, local).context(),
                "b".repeat(40), "unresolved", base, local, base, null, List.of(), List.of(), false);
        var comparison = projection.compare(unresolved).comparison();
        assertFalse(comparison.scenes().containsKey("proposed"));
        assertFalse(comparison.sceneTypeRoots().containsKey("proposed"));
        assertNull(comparison.properties().stream().filter(row -> row.property().equals("title")).findFirst().orElseThrow().proposed());
    }

    @Test void changedTypesAndRepeatedOpaquePropertiesKeepTheirOneSharedIdentity() {
        String base = "element one type CoreService {\n title: \"Original\";\n x-opaque: \"first\";\n x-opaque: \"second\";\n}\n";
        String local = base.replace("type CoreService", "type Capability").replace("\"first\"", "\"changed\"");
        var comparison = projection.compare(preview(base, local, base, local)).comparison();
        assertEquals(1, comparison.scenes().get("local").nodes().size());
        assertEquals("Capability", comparison.scenes().get("local").nodes().getFirst().type());
        assertEquals(comparison.scenes().get("base").nodes().getFirst().x(), comparison.scenes().get("local").nodes().getFirst().x());
        var opaque = comparison.properties().stream().filter(row -> row.property().equals("x-opaque")).findFirst().orElseThrow();
        assertEquals("element:one", opaque.blockId());
        assertEquals(List.of("changed", "second"), opaque.local());
        assertTrue(comparison.properties().stream().anyMatch(row -> row.property().equals("@header") && row.blockId().equals("element:one")));
    }

    @Test void relationsUseTheirFrozenEndpointsAndCommonGeometryAndMembershipOrderCreatesNoDifference() {
        String elements = "element one type CoreService {\n title: \"One\";\n}\n"
                + "element two type BusinessProcess {\n title: \"Two\";\n}\n";
        String relation = "relation one SUPPORTS two {\n status: accepted;\n}\n";
        String view = "view overview {\n include: one;\n include: two;\n}\n";
        String incoming = elements + relation + view;
        String local = incoming.replace(" include: one;\n include: two;", " include: two;\n include: one;");
        var comparison = projection.compare(preview(incoming, local, incoming, elements + view)).comparison();
        assertEquals(1, comparison.scenes().get("local").edges().size());
        assertTrue(comparison.scenes().get("proposed").edges().isEmpty());
        assertEquals(comparison.scenes().get("base").edges().getFirst(), comparison.scenes().get("local").edges().getFirst());
        assertTrue(comparison.properties().stream().noneMatch(row -> row.property().equals("include")));
        assertTrue(comparison.properties().stream().anyMatch(row -> row.blockId().equals("relation:one SUPPORTS two")));
    }

    private static ArchitectureEditorService.ReconciliationPreview preview(String base, String local, String incoming, String merged) {
        var context = new ArchitectureCommandPort.Context("repository", "workspace", "design", "a".repeat(40), 4, "author", "PRIVATE_WORKSPACE");
        return new ArchitectureEditorService.ReconciliationPreview(context,"b".repeat(40),"frozen-preview",base,local,incoming,merged,List.of(),List.of(),true);
    }
}
