package com.taxonomy.export;

import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.dto.RequirementElementView;

import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Shared behavioral checks for the JUnit suite and an isolated exact-runtime driver. */
final class DiagramHierarchyRegression {
    private DiagramHierarchyRegression() { }

    static void preservesNearestPresentAncestor() {
        var root = element("CP", "CP");
        var parent = element("CP-1", " CP\t >\n CP-1 ");
        var leaf = element("CP-3", " CP > CP-1\t > absent > CP-3 ");
        var orphan = element("CR-1", "missing > CR-1");
        var view = new RequirementArchitectureView();
        view.setIncludedElements(List.of(root, parent, leaf, orphan));
        var diagram = new DiagramProjectionService().projectRaw(view, "Hierarchy");
        if (!"CP".equals(diagram.nodes().get(1).parentId())
                || !"CP-1".equals(diagram.nodes().get(2).parentId())
                || diagram.nodes().get(0).parentId() != null
                || diagram.nodes().get(3).parentId() != null) {
            throw new AssertionError("Projection changed the nearest available ancestor");
        }
        if (!" CP > CP-1\t > absent > CP-3 ".equals(leaf.getHierarchyPath())) {
            throw new AssertionError("Projection mutated the source hierarchy");
        }
    }

    static void whitespaceWithoutDelimiterHasBoundedRuntime() throws Exception {
        var view = new RequirementArchitectureView();
        view.setIncludedElements(List.of(element("CP-1", " ".repeat(100_000) + "CP-1")));
        // A daemon worker keeps a broken regex from hanging the complete JVM.
        var projection = new FutureTask<>(() -> new DiagramProjectionService().projectRaw(view, "Whitespace"));
        var worker = new Thread(projection, "hierarchy-projection-regression");
        worker.setDaemon(true);
        worker.start();
        try {
            var diagram = projection.get(2, TimeUnit.SECONDS);
            if (diagram.nodes().size() != 1 || diagram.nodes().getFirst().parentId() != null) {
                throw new AssertionError("A delimiter-free hierarchy must remain parentless");
            }
        } catch (TimeoutException error) {
            throw new AssertionError("Whitespace-only hierarchy prefix exceeded two seconds", error);
        } finally {
            projection.cancel(true);
        }
    }

    private static RequirementElementView element(String code, String hierarchy) {
        var element = new RequirementElementView();
        element.setNodeCode(code);
        element.setHierarchyPath(hierarchy);
        element.setTaxonomySheet("CP");
        element.setRelevance(.9);
        return element;
    }

    public static void main(String[] args) throws Exception {
        preservesNearestPresentAncestor();
        whitespaceWithoutDelimiterHasBoundedRuntime();
        System.out.println("Diagram hierarchy: two behavioral checks passed");
    }
}
