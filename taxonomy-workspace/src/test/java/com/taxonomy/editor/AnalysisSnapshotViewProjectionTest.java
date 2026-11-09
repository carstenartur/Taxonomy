package com.taxonomy.editor;

import com.taxonomy.diagram.DiagramSceneEdge;
import com.taxonomy.dsl.admbw.AdmbwFffmlvProfile;
import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.diagram.DiagramLayout.Strategy;
import com.taxonomy.export.LayeredDiagramLayoutService;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class AnalysisSnapshotViewProjectionTest {
    @Test void selectedSnapshotViewUsesOnlyRecordedRelationsAndFullModelRemainsAvailable() {
        var commands = new ArchitectureDslCommands();
        String dsl = commands.apply("", new AdoptAnalysisSnapshot("analysis-test","Snapshot","Old text","a".repeat(64),
                List.of(new SnapshotElement("system","System","System",""),new SnapshotElement("component","Component","Component","")),
                List.of(),Map.of("x-analysis-snapshot-id","snapshot"))).dsl();
        dsl = commands.apply(dsl,new CreateArchitectureRelation(new RelationKey("system","CONTAINS","component"),"accepted")).dsl();
        var context = ArchitectureCommandPort.Context.of(RepositoryContext.workspace("repo-a","copy-a","draft","alice"),null,1);
        var document = new ArchitectureEditorService.Document(context,dsl,"READY",List.of(),List.of(),0,null,"WORKSPACE_REVISION");
        var projection = new ArchitectureEditorProjection(new LayeredDiagramLayoutService());
        var selected = projection.project(document,true,Strategy.STABLE,null,"analysis-test");
        assertThat(selected.scene().nodes()).hasSize(2);
        assertThat(selected.scene().edges()).isEmpty();
        assertThat(selected.model().getRelations()).hasSize(1);
        assertThat(projection.project(document,true).scene().edges()).hasSize(1);
        assertThatThrownBy(() -> projection.project(document,true,Strategy.STABLE,null,"other")).isInstanceOf(ArchitectureDslCommands.CommandProblem.class);
    }

    @Test
    void boundAdmbwSourceViewAddsAcceptedExhibitsToTheRecordedSnapshotEdges() {
        var commands = new ArchitectureDslCommands();
        var members = List.of(new SnapshotElement("capability", "Capability", "Warning capability", ""),
                new SnapshotElement("service", "CoreService", "Warning service", ""));
        var recorded = List.of(new RelationKey("capability", "RELATED_TO", "service"));
        var provenance = Map.of("x-analysis-catalogue-fingerprint", "b".repeat(64));
        String dsl = commands.applyAll("", List.of(
                new AdoptAnalysisSnapshot("analysis-delivery", "Delivery", "Snapshot", "a".repeat(64), members, recorded, provenance),
                new AdoptAnalysisSnapshot("analysis-generic", "Generic", "Snapshot", "a".repeat(64), members, recorded, provenance),
                new UpdateArchitectureElement("capability", "Capability", Map.of(
                        AdmbwFffmlvProfile.CATALOGUE_GUID, "{67AEAD63-403A-48FD-A022-174DC3037CE2}",
                        AdmbwFffmlvProfile.CATALOGUE_REFERENCE, "Catalogue edition 1")),
                new UpdateArchitectureElement("service", "CoreService", Map.of(AdmbwFffmlvProfile.TYPE, "ServiceSpecification")),
                new CreateArchitectureRelation(new RelationKey("service", "RELATED_TO", "capability"), "accepted"),
                new ConfigureAdmbwDelivery("WARN", "analysis-delivery"),
                new CreateArchitectureRelation(new RelationKey("service", "FULFILLS", "capability"), "accepted"),
                new CreateArchitectureRelation(new RelationKey("capability", "REALIZES", "service"), "proposed"))).dsl();
        var context = ArchitectureCommandPort.Context.of(RepositoryContext.workspace("repo-a", "copy-a", "draft", "alice"), null, 1);
        var document = new ArchitectureEditorService.Document(context, dsl, "READY", List.of(), List.of(), 0, null, "WORKSPACE_REVISION");
        var projection = new ArchitectureEditorProjection(new LayeredDiagramLayoutService());

        var delivery = projection.project(document, true, Strategy.STABLE, null, "analysis-delivery");
        assertThat(delivery.scene().edges()).extracting(DiagramSceneEdge::id)
                .containsExactlyInAnyOrder("capability RELATED_TO service", "service FULFILLS capability");
        var generic = projection.project(document, true, Strategy.STABLE, null, "analysis-generic");
        assertThat(generic.scene().edges()).extracting(DiagramSceneEdge::id)
                .containsExactly("capability RELATED_TO service");
    }
}
