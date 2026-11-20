package com.taxonomy;

import com.taxonomy.diagram.*;
import com.taxonomy.export.StructurizrExportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class StructurizrInteroperabilityTest {
    @Test void officialParserPreservesWorkingTitleAndPartialStatus() throws Exception {
        var node = new DiagramNode("fixture-node", "Übergabe", "Business Processes", .6, false, 2);
        var model = new DiagramModel("Arbeitsstand Übergabe — PARTIAL / TEILERGEBNIS", List.of(node), List.of(),
                new DiagramLayout("LR", true));
        var parser = new com.structurizr.dsl.StructurizrDslParser();
        parser.setRestricted(true);

        parser.parse(new StructurizrExportService().export(model));

        assertThat(parser.getWorkspace().getName()).isEqualTo("Arbeitsstand Übergabe — PARTIAL / TEILERGEBNIS");
    }

    @ParameterizedTest
    @ValueSource(strings = {"extends", "Übergabe \"A\" & Süd", "Weg endet mit \\"})
    void officialParserKeepsLiteralWorkspaceTitle(String title) throws Exception {
        var parser = new com.structurizr.dsl.StructurizrDslParser();
        parser.setRestricted(true);

        parser.parse(new StructurizrExportService().export(new DiagramModel(title, List.of(), List.of(), null)));

        assertThat(parser.getWorkspace().getName()).isEqualTo(title);
    }

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
