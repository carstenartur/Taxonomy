package com.taxonomy.export;

import com.taxonomy.archimate.exchange.ArchiMateExchangeReader;
import com.taxonomy.archimate.exchange.ArchiMateXmlExporter;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.dto.TaxonomyResponsibilityLookup.Assignment;
import com.taxonomy.exchange.sparx.SparxXmiCodec;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ResponsibilityExchangeTest {
    private final Assignment owner = new Assignment("office-42", "Meldestelle & Bürgerbüro", "BP-parent");
    private final DiagramModel model = new DiagramModel("Bürgeranfragen", List.of(
            new DiagramNode("BP-1", "Anfragen prüfen", "Business Processes", .8, true, 1, 2, false, null, false, owner),
            new DiagramNode("IP-2", "Aktenauszug", "Information Products", .7, false, 2)), List.of(), null);

    @Test void archimateRestoresOnlyRecordedAssignmentsAndTheirSource() {
        var reader = new ArchiMateExchangeReader();
        var xml = new ArchiMateXmlExporter().export(new ArchiMateDiagramService().convert(model));
        assertThat(reader.toDiagram(reader.read(xml)).nodes()).isEqualTo(model.nodes());
    }

    @Test void archimateRejectsPartialAssignmentInsteadOfInventingMissingFields() {
        var xml = new ArchiMateXmlExporter().export(new ArchiMateDiagramService().convert(model));
        String partial = new String(xml, java.nio.charset.StandardCharsets.UTF_8)
                .replace(">taxonomy.responsibilityUnitId<", ">custom.unrelated<");
        assertThatIllegalArgumentException().isThrownBy(() ->
                new ArchiMateExchangeReader().read(partial.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .withMessageContaining("responsibilityUnitId");
    }

    @Test void sparxExposesTheSameInheritedOwnerOnTheAssignedElementOnly() throws Exception {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(SparxDiagramHandoff.build(model, UUID.randomUUID())))) {
            assertThat(zip.getNextEntry().getName()).isEqualTo("architecture.xmi");
            var elements = new SparxXmiCodec("3").read(zip.readAllBytes(), "fresh-copy", false).artifacts();
            assertThat(elements).hasSize(2);
            for (var element : elements) {
                var tags = element.attributes();
                if ("BP-1".equals(tags.get("tag:taxonomy.sourceId"))) {
                    assertThat(tags).containsEntry("tag:taxonomy.responsibilityName", owner.name())
                            .containsEntry("tag:taxonomy.responsibilityUnitId", owner.unitId())
                            .containsEntry("tag:taxonomy.responsibilitySourceId", owner.sourceId());
                } else {
                    assertThat(tags).containsEntry("tag:taxonomy.sourceId", "IP-2")
                            .doesNotContainKeys("tag:taxonomy.responsibilityName", "tag:taxonomy.responsibilitySourceId",
                                    "tag:taxonomy.responsibilityUnitId");
                }
            }
        }
    }
}
