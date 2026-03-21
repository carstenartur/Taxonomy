package com.taxonomy.export;

import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StructurizrExportServiceTest {

    private final StructurizrExportService service = new StructurizrExportService();

    @Test
    void exportProducesValidDsl() {
        var nodes = List.of(
                new DiagramNode("n1", "User Portal", "User Applications", 0.9, true, 1),
                new DiagramNode("n2", "Auth Service", "Core Services", 0.8, false, 2)
        );
        var edges = List.of(
                new DiagramEdge("e1", "n1", "n2", "authenticates via", 0.7)
        );
        var model = new DiagramModel("Test", nodes, edges, new DiagramLayout("LR", false));

        String dsl = service.export(model);

        assertThat(dsl).startsWith("workspace {");
        assertThat(dsl).contains("softwareSystem \"User Portal\"");
        assertThat(dsl).contains("container \"Auth Service\"");
        assertThat(dsl).contains("-> ");
        assertThat(dsl).contains("authenticates via");
        assertThat(dsl).contains("views {");
        assertThat(dsl).endsWith("}\n");
    }

    @Test
    void emptyModelProducesValidDsl() {
        var model = new DiagramModel("Empty", List.of(), List.of(), new DiagramLayout("LR", false));

        String dsl = service.export(model);

        assertThat(dsl).startsWith("workspace {");
        assertThat(dsl).contains("model {");
        assertThat(dsl).contains("views {");
        assertThat(dsl).endsWith("}\n");
    }

    @Test
    void personTypeIsMappedCorrectly() {
        var nodes = List.of(
                new DiagramNode("n1", "System Admin", "Business Roles", 0.5, false, 1)
        );
        var model = new DiagramModel("Roles", nodes, List.of(), new DiagramLayout("LR", false));

        String dsl = service.export(model);

        assertThat(dsl).contains("person \"System Admin\"");
    }

    @Test
    void specialCharactersInLabelsAreEscaped() {
        var nodes = List.of(
                new DiagramNode("n1", "Auth \"OAuth\" Service", "Services", 0.5, false, 1)
        );
        var model = new DiagramModel("Escape", nodes, List.of(), new DiagramLayout("LR", false));

        String dsl = service.export(model);

        assertThat(dsl).contains("Auth \\\"OAuth\\\" Service");
    }

    @Test
    void sanitizeIdHandlesEdgeCases() {
        assertThat(service.sanitizeId(null, 1)).isEqualTo("element1");
        assertThat(service.sanitizeId("", 2)).isEqualTo("element2");
        assertThat(service.sanitizeId("   ", 3)).isEqualTo("element3");
        assertThat(service.sanitizeId("My Service", 4)).isEqualTo("myService");
        assertThat(service.sanitizeId("123-System", 5)).isEqualTo("123System");
        assertThat(service.sanitizeId("Auth/OAuth2.0", 6)).isEqualTo("authOAuth20");
        assertThat(service.sanitizeId("---", 7)).isEqualTo("element7");
    }
}
