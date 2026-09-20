package com.taxonomy.architecture.report;

import static org.assertj.core.api.Assertions.*;

import com.taxonomy.diagram.*;
import com.taxonomy.export.LayeredDiagramLayoutService;

import org.junit.jupiter.api.Test;

import java.util.List;

class ArchitectureFigureRendererTest {
    @Test
    void readingScaleDetailsTrimUnusedCanvasAndExposeVisibleRelationKeys() throws Exception {
        var graph =
                new DiagramModel(
                        "Saved graph",
                        List.of(
                                new DiagramNode(
                                        "A", "Saved capability", "Capabilities", 1, true, 0),
                                new DiagramNode(
                                        "B", "Saved service", "Core Services", .8, false, 1)),
                        List.of(new DiagramEdge("e1", "A", "B", "REALIZES", .8, "impact")),
                        null);
        var scene = new LayeredDiagramLayoutService().layout(graph);
        var panel = new ArchitectureFigurePlanner().plan(graph, scene).details().getFirst();
        var rendered = new ArchitectureFigureRenderer().render(panel, "en");
        assertThat(rendered.height())
                .as("unused minimum-layout canvas must not consume Word pages")
                .isLessThan(400);
        assertThat(rendered.altDescription()).contains("e1", "REALIZES", "impact", "A", "B");
        var png = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(rendered.png()));
        assertThat(png.getWidth()).isEqualTo(rendered.width());
        assertThat(png.getHeight()).isEqualTo(rendered.height());
    }

    @Test
    void selfRelationPaintsVisibleDirectedLoopBeyondItsNodeInOverviewAndDetail() throws Exception {
        var graph =
                new DiagramModel(
                        "Self relation",
                        List.of(new DiagramNode("A", "Saved A", "Capabilities", 1, true, 0)),
                        List.of(new DiagramEdge("loop", "A", "A", "SUPPORTS", 1, "impact")),
                        null);
        var scene = new LayeredDiagramLayoutService().layout(graph);
        var plan = new ArchitectureFigurePlanner().plan(graph, scene);
        for (var panel : List.of(plan.overview(), plan.details().getFirst())) {
            var rendered = new ArchitectureFigureRenderer().render(panel, "en");
            var noEdges = new DiagramModel(graph.title(), graph.nodes(), List.of(), null);
            var plainPanel =
                    new ArchitectureFigurePlanner.Panel(
                            panel.id(),
                            noEdges,
                            new DiagramScene(
                                    scene.title(),
                                    scene.width(),
                                    scene.height(),
                                    scene.direction(),
                                    scene.nodes(),
                                    List.of()),
                            panel.nodeIds(),
                            List.of(),
                            panel.overview());
            var plain = new ArchitectureFigureRenderer().render(plainPanel, "en");
            var artifacts =
                    java.nio.file.Files.createDirectories(
                            java.nio.file.Path.of("target/frozen-word-review"));
            java.nio.file.Files.write(
                    artifacts.resolve(panel.id() + "-self-loop.png"), rendered.png());
            var actual =
                    javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(rendered.png()));
            var without = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(plain.png()));
            // A loop needs visible ink outside the filled node, not just alt text or a relation
            // key.
            long outsideInk = 0;
            for (int y = 0; y < Math.min(actual.getHeight(), without.getHeight()); y++)
                for (int x = 0; x < Math.min(actual.getWidth(), without.getWidth()); x++)
                    if ((actual.getRGB(x, y) & 0xffffff) != 0xffffff
                            && (without.getRGB(x, y) & 0xffffff) == 0xffffff) outsideInk++;
            assertThat(outsideInk).as("visible loop and arrow in %s", panel.id()).isGreaterThan(50);
        }
    }
}
