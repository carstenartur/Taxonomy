package com.taxonomy.editor;

import com.taxonomy.dsl.model.ArchitectureElement;
import com.taxonomy.dsl.model.ArchitectureRelation;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ArchitectureResponsibilityTest {
    @Test void closestAssignmentSurvivesRenameOverrideRemovalAndNewChildren() {
        var model = new CanonicalArchitectureModel();
        var root = element(model, "BR-1000", null);
        var branch = element(model, "BR-1002", "BR-1000");
        var leaf = element(model, "BR-1081", "BR-1002");
        root.getExtensions().put("x-responsible-organization-id", "unit-a");
        branch.getExtensions().put("x-responsible-organization-id", "unit-b");
        var row = ArchitectureResponsibility.resolve(model, Map.of()).get(leaf.getId());
        assertEquals("unit-b", row.organizationId());
        assertEquals(branch.getId(), row.sourceElementId());
        branch.getExtensions().clear();
        var parents = Map.of("BR-1002", "BR-1000");
        row = ArchitectureResponsibility.resolve(model, parents).get(leaf.getId());
        assertEquals("unit-a", row.organizationId());
        assertEquals(root.getId(), row.sourceElementId());
        element(model, "new-child", "BR-1081");
        assertEquals("unit-a", ArchitectureResponsibility.resolve(model, parents).get("new-child").organizationId());
    }

    @Test void sparseCatalogueAndLegacyNamesRetainTheApplicableAncestor() {
        var model = new CanonicalArchitectureModel();
        element(model, "BR-1000", null).getExtensions().put("x-responsible-organization", "Old office");
        element(model, "BR-1081", null);
        var resolved = ArchitectureResponsibility.resolve(model, Map.of(
                "BR-1081", "BR-1002", "BR-1002", "BR-1057", "BR-1057", "BR-1000", "BR-1000", "BR"));
        assertEquals("BR-1000", resolved.get("BR-1081").parentId());
        assertEquals("Old office", resolved.get("BR-1081").legacyOrganization());
        assertNull(resolved.get("BR-1081").organizationId());
        assertEquals("BR", resolved.get("BR-1000").parentId());
    }

    @Test void containmentOverridesCatalogueAndMixedCycleCannotLoop() {
        var model = new CanonicalArchitectureModel();
        var a = element(model, "a", "b");
        var b = element(model, "b", null);
        a.getExtensions().put("x-responsible-organization-id", "engineering");
        var relation = new ArchitectureRelation();
        relation.setSourceId("a");
        relation.setTargetId("b");
        relation.setRelationType("CONTAINS");
        model.getRelations().add(relation);
        var rows = ArchitectureResponsibility.resolve(model, Map.of());
        assertNotEquals("b", rows.get(a.getId()).parentId());
        assertEquals("a", rows.get(b.getId()).parentId());
        assertEquals("engineering", rows.get("b").organizationId());
    }

    private ArchitectureElement element(CanonicalArchitectureModel model, String id, String parent) {
        var e = new ArchitectureElement(id, "BusinessRole", id, null, "BR");
        if (parent != null) e.getExtensions().put("x-catalogue-parent", parent);
        model.getElements().add(e);
        return e;
    }
}
