package com.taxonomy.export;

import org.junit.jupiter.api.Test;

class PdfDiagramPaginationTest {
    @Test void wideEditorCanvasRetainsEveryNodeAndRelationship() throws Exception {
        PdfDiagramPaginationChecks.wideCanvasRetainsEveryNodeAndEdge();
    }
    @Test void tallCanvasDoesNotLoseNodesOrAllocateEmptyStrips() throws Exception {
        PdfDiagramPaginationChecks.tallCanvasRetainsLastNode();
    }
    @Test void smallScenePreservesExistingCoordinates() throws Exception {
        PdfDiagramPaginationChecks.smallSceneKeepsItsGeometry();
    }
    @Test void invalidGeometryAndExcessivePanelCountsFailBeforeWriting() {
        PdfDiagramPaginationChecks.rejectsInvalidGeometryAndBoundsPanelWork();
    }
    @Test void singleUnreadableBoxFailsExplicitly() {
        PdfDiagramPaginationChecks.giantSingleBoxIsRejected();
    }
}
