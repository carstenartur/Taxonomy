package com.taxonomy.editor;

import com.taxonomy.dsl.model.ArchitectureElement;
import com.taxonomy.dsl.model.ArchitectureRelation;
import com.taxonomy.dsl.model.ArchitectureOrganization;
import com.taxonomy.dsl.model.CatalogueResponsibility;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ArchitectureResponsibilityTest {
    @Test void catalogueResponsibilitiesApplyBeforeAnyArchitectureExists() {
        var model = new CanonicalArchitectureModel();
        model.getOrganizations().add(new ArchitectureOrganization("operations", "Operations", null));
        model.getOrganizations().add(new ArchitectureOrganization("service", "Service desk", "operations"));
        model.getCatalogueResponsibilities().add(new CatalogueResponsibility("BP", "operations"));
        model.getCatalogueResponsibilities().add(new CatalogueResponsibility("BP-2", "service"));
        var parents = Map.of("BP-1", "BP", "BP-2", "BP", "BP-3", "BP-2", "IP-1", "IP");

        var resolved = ArchitectureResponsibility.forAnalysis(model, parents);
        assertEquals("operations", resolved.get("BP-1").unitId());
        assertEquals("BP", resolved.get("BP-1").sourceId());
        assertEquals("service", resolved.get("BP-3").unitId());
        assertEquals("BP-2", resolved.get("BP-3").sourceId());
        assertFalse(resolved.containsKey("IP-1"));
    }

    @Test void workingArchitectureOverridesDoNotChangeTheCatalogueAnalysis() {
        var model = new CanonicalArchitectureModel();
        model.getOrganizations().add(new ArchitectureOrganization("base", "Catalogue unit", null));
        model.getOrganizations().add(new ArchitectureOrganization("team", "Architecture team", null));
        model.getCatalogueResponsibilities().add(new CatalogueResponsibility("BR", "base"));
        element(model, "BR-1", null).getExtensions().put(ArchitectureOrganization.RESPONSIBLE_ID, "team");
        element(model, "BP-unassigned", null).getExtensions().put(ArchitectureOrganization.RESPONSIBLE_ID, "team");
        element(model, "BR-2", null);
        element(model, "BR-3", null);
        var parents = Map.of("BR-1", "BR", "BR-2", "BR-1", "BR-3", "BR");
        var analysis = ArchitectureResponsibility.forAnalysis(model, parents);
        assertEquals("base", analysis.get("BR-2").unitId());
        assertEquals("base", analysis.get("BR-3").unitId());
        assertFalse(analysis.containsKey("BP-unassigned"));
        var editor = ArchitectureResponsibility.forEditor(model, parents, parents);
        assertEquals("team", editor.get("BR-2").organizationId());
        assertEquals("base", editor.get("BR-3").organizationId());
    }
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

    @Test void anAdoptedUnitRetainsItsCapturedNameAndSourceAfterTheDirectoryChanges() {
        var model = new CanonicalArchitectureModel();
        model.getOrganizations().add(new ArchitectureOrganization("operations", "Renamed team", null));
        var adopted = element(model, "BR-1081", null);
        adopted.getExtensions().putAll(Map.of(ArchitectureOrganization.CAPTURED_UNIT, "operations",
                ArchitectureOrganization.CAPTURED_NAME, "Original team", ArchitectureOrganization.CAPTURED_SOURCE, "BR-1002"));
        var named = ArchitectureResponsibility.namedAssignments(model, ArchitectureResponsibility.forEditor(model, Map.of(), Map.of()));
        assertEquals("Original team", named.get(adopted.getId()).name());
        assertEquals("BR-1002", named.get(adopted.getId()).sourceId());
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
