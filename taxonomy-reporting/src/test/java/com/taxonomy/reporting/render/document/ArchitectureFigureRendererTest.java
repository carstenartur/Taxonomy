package com.taxonomy.reporting.render.document;

import static org.assertj.core.api.Assertions.*;

import com.taxonomy.diagram.*;
import com.taxonomy.export.LayeredDiagramLayoutService;

import org.junit.jupiter.api.Test;

import java.util.List;

class ArchitectureFigureRendererTest {
    @Test
    void savedA1OverviewKeepsVisibleInkAcrossTheIntermediateCardsColumn() throws Exception {
        var cards = List.of(
                a1Card("CI-1151", 1168, 400), a1Card("BP-2125", 576, 400), a1Card("CI-1047", 906, 400),
                a1Card("first-card", 52, 52), a1Card("last-card", 52, 516));
        var modelEdge = new DiagramEdge("e1", "CI-1151", "BP-2125", "SUPPORTS", .7, "impact");
        var edge = new DiagramSceneEdge("e1", "CI-1151", "BP-2125", "SUPPORTS", .7, "impact", 1168, 441, 814, 441);
        var graph = new DiagramModel("Retained A1", cards.stream()
                .map(card -> new DiagramNode(card.id(), card.label(), card.type(), .7, false, 2)).toList(),
                List.of(modelEdge), null);
        var scene = new DiagramScene(graph.title(), 1458, 650, "LR", cards, List.of(edge));
        var fixture = new ArchitectureFigurePlanner.Panel("architecture-overview", graph, scene,
                cards.stream().map(DiagramSceneNode::id).toList(), List.of(edge.id()), true);
        var figure = new ArchitectureFigureRenderer().render(fixture, "de");
        var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(figure.png()));
        int x = (int) Math.round((1030 - 28) * figure.rasterScale());
        long connectorPixels = java.util.stream.IntStream.range(0, image.getHeight())
                .filter(y -> (image.getRGB(x, y) & 0xffffff) == 0x64748b).count();
        assertThat(connectorPixels).as("connector remains visible across the center of CI-1047's column").isPositive();
        int cardTopY = (int) Math.round((416 - 28) * figure.rasterScale());
        assertThat(image.getRGB(x, cardTopY) & 0xffffff).isEqualTo(0xFEF3C7);
        for (var card : cards) {
            double scale = figure.rasterScale();
            int left = (int) Math.round((card.x() + 25 - 28) * scale);
            int right = (int) Math.round((card.x() + card.width() - 25 - 28) * scale);
            int top = (int) Math.round((card.y() + 20 - 28) * scale);
            int bottom = (int) Math.round((card.y() + card.height() - 20 - 28) * scale);
            assertThat(java.util.stream.IntStream.range(top, bottom).anyMatch(y ->
                    java.util.stream.IntStream.range(left, right).anyMatch(px ->
                            (image.getRGB(px, y) & 0xffffff) == 0x1e293b)))
                    .as("overview paints an identifier inside %s", card.id()).isTrue();
        }
        assertThat(figure.altDescription()).contains("CI-1151 → BP-2125");
    }

    private static DiagramSceneNode a1Card(String id, double x, double y) {
        return new DiagramSceneNode(id, id, "COI Services", .7, false, 2, 0, false, null,
                false, x, y, 238, 82);
    }

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
