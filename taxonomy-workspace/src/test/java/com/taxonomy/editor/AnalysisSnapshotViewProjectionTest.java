package com.taxonomy.editor;

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
}
