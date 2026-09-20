package com.taxonomy;

import com.taxonomy.diagram.*;
import com.taxonomy.export.StructurizrExportService;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class StructurizrInteroperabilityTest {
    @Test void officialParserAcceptsFlatTaxonomyWithoutInventingC4ParentsAndPreservesIdentity() {
        var model = new DiagramModel("Reference", List.of(
                new DiagramNode("a-b", "Same label", "Core Services", 1, true, 1),
                new DiagramNode("ab", "Same label", "Communications Services", 1, true, 2)),
                List.of(new DiagramEdge("r", "a-b", "ab", "uses", 1)), new DiagramLayout("LR", false));
        String dsl = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> new StructurizrExportService().export(model));
        var parser = new com.structurizr.dsl.StructurizrDslParser();
        parser.setRestricted(true);
        try { parser.parse(dsl); } catch (Exception failure) { throw new AssertionError(failure); }
        assertThat(parser.getWorkspace().getModel().getElements()).hasSize(2)
                .extracting(e -> e.getProperties().get("taxonomy.id")).containsExactlyInAnyOrder("a-b", "ab");
        assertThat(parser.getWorkspace().getModel().getRelationships()).hasSize(1);
        assertThat(parser.getWorkspace().getViews().getCustomViews()).hasSize(1);
    }
}
