package com.taxonomy.editor;

import com.taxonomy.diagram.DiagramSceneNode;
import com.taxonomy.dsl.command.ArchitectureCommand.UpdateArchitectureElement;
import com.taxonomy.dsl.command.ArchitectureCommand.UpsertOrganization;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.model.ArchitectureOrganization;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.dsl.model.CatalogueDefinition;
import com.taxonomy.dsl.model.CatalogueDefinitionResolver;
import com.taxonomy.dto.TaxonomyResponsibilityLookup.Assignment;
import com.taxonomy.export.LayeredDiagramLayoutService;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ArchitectureEditorResponsibilityProjectionTest {
    @Test
    void sparseCatalogueResponsibilityReachesTheWorkingGraph() {
        var commands = new ArchitectureDslCommands();
        String source = """
                element BR-1000 type BusinessRole {
                  title: "Operations";
                }
                element BR-1081 type BusinessRole {
                  title: "Response coordinator";
                }
                element BR-1082 type BusinessRole {
                  title: "Independent role";
                }
                """;
        String directory = commands.apply(source, new UpsertOrganization("unit-a", Map.of("title", "Operations team"))).dsl();
        String assigned = commands.apply(directory, new UpdateArchitectureElement("BR-1000", null,
                Map.of(ArchitectureOrganization.RESPONSIBLE_ID, "unit-a"))).dsl();
        var resolver = new CatalogueDefinitionResolver() {
            @Override public CatalogueDefinition forModel(CanonicalArchitectureModel model) {
                return CatalogueDefinition.standard();
            }

            @Override public Map<String, String> parentsForModel(CanonicalArchitectureModel model) {
                return Map.of("BR-1081", "BR-1002", "BR-1002", "BR-1000");
            }
        };
        var context = ArchitectureCommandPort.Context.of(
                RepositoryContext.workspace("repo-a", "copy-a", "draft", "alice"), null, 1);
        var document = new ArchitectureEditorService.Document(
                context, assigned, "READY", List.of(), List.of(), 0, null, "WORKSPACE_REVISION");
        var projector = new ArchitectureEditorProjection(new LayeredDiagramLayoutService(), resolver);
        var scene = projector.project(document, true).scene();
        var nodes = scene.nodes().stream().collect(Collectors.toMap(DiagramSceneNode::id, node -> node));
        var responsibility = new Assignment("unit-a", "Operations team", "BR-1000");

        assertEquals(responsibility, nodes.get("BR-1000").responsibility());
        assertEquals(responsibility, nodes.get("BR-1081").responsibility());
        assertEquals("Response coordinator", nodes.get("BR-1081").label());
        assertNull(nodes.get("BR-1082").responsibility());
    }
}
