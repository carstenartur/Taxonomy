package com.taxonomy.interop;

import com.taxonomy.archimate.ArchiMateLoss;
import com.taxonomy.archimate.ArchiMateRelationship;
import com.taxonomy.archimate.exchange.ArchiMateExchangeProfile;
import com.taxonomy.archimate.exchange.ArchiMateExchangeReader;
import com.taxonomy.archimate.exchange.ArchiMateXmlExporter;
import com.taxonomy.export.ArchiMateDiagramService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class CivilianArchiMateExchangeTest {
    @Test
    void reprojectsThePublishedFloodGraphAndRetainsItsDirectedEvidenceAndDiagramOccurrences() throws Exception {
        var reader = new ArchiMateExchangeReader();
        byte[] original;
        try (var compressed = new GZIPInputStream(Objects.requireNonNull(getClass().getResourceAsStream(
                "/scenarios/pr1251-complete-architecture.archimate.xml.gz")))) {
            original = compressed.readAllBytes();
        }
        var historical = reader.read(original);
        assertThat(historical.properties().get("taxonomy.mappingProfile").value()).isEqualTo("taxonomy-archimate-3.1-v2");
        var graph = reader.toDiagram(historical);
        assertThat(graph.nodes()).hasSize(50);
        assertThat(graph.edges()).hasSize(36);

        // The external 3.2 matrix rejects the historical Access projection of these
        // two capability -> information-product requirements in this exact snapshot.
        var affected = Set.of("e28", "e29");
        assertThat(historical.relationships()).filteredOn(r -> affected.contains(r.id()))
                .extracting(ArchiMateRelationship::archiMateType).containsExactly("Access", "Access");
        var projected = new ArchiMateDiagramService().convert(graph);
        var returned = reader.read(new ArchiMateXmlExporter().export(projected));
        assertThat(returned.properties().get("taxonomy.mappingProfile").value()).isEqualTo(ArchiMateExchangeProfile.VERSION);
        assertThat(returned.relationships()).filteredOn(r -> affected.contains(r.id()))
                .extracting(ArchiMateRelationship::archiMateType).containsExactly("Association", "Association");
        assertThat(returned.losses()).filteredOn(l -> affected.contains(l.id()) && "APPROXIMATED".equals(l.kind()))
                .extracting(ArchiMateLoss::id).containsExactlyInAnyOrder("e28", "e29");
        var recovered = reader.toDiagram(returned);
        assertThat(recovered.nodes()).containsExactlyInAnyOrderElementsOf(graph.nodes());
        assertThat(recovered.edges()).containsExactlyInAnyOrderElementsOf(graph.edges());
        assertThat(returned.views()).containsExactlyElementsOf(projected.views());
        assertThat(returned.views()).extracting(view -> List.of(view.nodes().size(), view.connections().size()))
                .containsExactly(List.of(50, 36), List.of(46, 36));
    }
}
