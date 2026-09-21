package com.taxonomy.export;

import org.junit.jupiter.api.Test;

class DiagramProjectionHierarchyTest {
    @Test
    void preservesNearestPresentAncestorAndSourceHierarchy() {
        DiagramHierarchyRegression.preservesNearestPresentAncestor();
    }

    @Test
    void longWhitespacePrefixDoesNotCauseQuadraticProjection() throws Exception {
        DiagramHierarchyRegression.whitespaceWithoutDelimiterHasBoundedRuntime();
    }
}
