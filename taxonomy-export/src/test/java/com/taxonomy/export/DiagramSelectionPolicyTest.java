package com.taxonomy.export;

import com.taxonomy.diagram.*;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link DiagramSelectionPolicy} implementations, independent of any renderer.
 *
 * <p>Each test validates the policy's curation decisions (visibility, suppression,
 * containment, limits) on a plain {@link DiagramModel} — no Mermaid, ArchiMate, or
 * other rendering infrastructure is involved.</p>
 */
class DiagramSelectionPolicyTest {

    // ── Default impact policy ───────────────────────────────────────────

    @Nested
    class DefaultImpactPolicyTest {

        private final DiagramSelectionPolicy policy =
                new ConfigurableDiagramSelectionPolicy(DiagramSelectionConfig.defaultImpact());

        @Test
        void nullModelPassesThrough() {
            assertThat(policy.apply(null)).isNull();
        }

        @Test
        void emptyModelPassesThrough() {
            var model = model(List.of(), List.of());
            var result = policy.apply(model);
            assertThat(result.nodes()).isEmpty();
        }

        @Test
        void highRelevanceNodesAreKept() {
            var node = node("CP-1023", "Cap", "Capabilities", 0.8, false, 1, 3);
            var result = policy.apply(model(List.of(node), List.of()));
            assertThat(ids(result)).containsExactly("CP-1023");
        }

        @Test
        void lowRelevanceNodesAreExcluded() {
            var node = node("CP-1023", "Cap", "Capabilities", 0.1, false, 1, 3);
            var result = policy.apply(model(List.of(node), List.of()));
            assertThat(result.nodes()).isEmpty();
        }

        @Test
        void lowRelevanceAnchorsAreKept() {
            var node = node("CP-1023", "Cap", "Capabilities", 0.1, true, 1, 3);
            var result = policy.apply(model(List.of(node), List.of()));
            assertThat(ids(result)).containsExactly("CP-1023");
        }

        @Test
        void lowRelevanceImpactSelectedAreKept() {
            var node = new DiagramNode("CP-1023", "Cap", "Capabilities", 0.1, false, 1,
                    3, true, null, false);
            var result = policy.apply(model(List.of(node), List.of()));
            assertThat(ids(result)).containsExactly("CP-1023");
        }

        @Test
        void rootNodesSuppressedWhenConcreteExists() {
            var root = node("CP", "Capabilities", "Capabilities", 0.9, false, 1, 0);
            var leaf = node("CP-1023", "Messaging", "Capabilities", 0.8, false, 1, 3);
            var result = policy.apply(model(List.of(root, leaf), List.of()));
            assertThat(ids(result)).containsExactly("CP-1023");
        }

        @Test
        void rootNodesKeptWhenNoConcrete() {
            var root = node("CP", "Capabilities", "Capabilities", 0.9, false, 1, 0);
            var result = policy.apply(model(List.of(root), List.of()));
            assertThat(ids(result)).containsExactly("CP");
        }

        @Test
        void scaffoldingSuppressedWhenConcreteExists() {
            var scaffolding = node("CP-1000", "Container", "Capabilities", 0.9, false, 1, 1);
            var concrete = node("CP-1023", "Messaging", "Capabilities", 0.8, false, 1, 3);
            var result = policy.apply(model(List.of(scaffolding, concrete), List.of()));
            assertThat(ids(result)).containsExactly("CP-1023");
        }

        @Test
        void scaffoldingKeptWhenNoConcrete() {
            var scaffolding = node("CP-1000", "Container", "Capabilities", 0.9, false, 1, 1);
            var result = policy.apply(model(List.of(scaffolding), List.of()));
            assertThat(ids(result)).containsExactly("CP-1000");
        }

        @Test
        void scaffoldingKeptWhenConcreteFilteredByRelevance() {
            var scaffolding = node("CP-1000", "Container", "Capabilities", 0.9, false, 1, 1);
            var lowConcrete = node("CP-1023", "Low", "Capabilities", 0.1, false, 1, 3);
            var result = policy.apply(model(List.of(scaffolding, lowConcrete), List.of()));
            assertThat(ids(result)).containsExactly("CP-1000");
        }

        @Test
        void scaffoldingSuppressionWorksAcrossCategories() {
            var cpScaff = node("CP-1000", "CP container", "Capabilities", 0.9, false, 1, 1);
            var cpLeaf = node("CP-1023", "Messaging", "Capabilities", 0.8, false, 1, 3);
            var coScaff = node("CO-1000", "CO container", "Communications Services", 0.7, false, 6, 1);
            var result = policy.apply(model(List.of(cpScaff, cpLeaf, coScaff), List.of()));
            assertThat(ids(result)).containsExactlyInAnyOrder("CP-1023", "CO-1000");
        }

        @Test
        void nodesLimitedToMaxNodes() {
            List<DiagramNode> nodes = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                nodes.add(node("CP-" + String.format("%04d", 1000 + i), "N" + i,
                        "Capabilities", 0.5 + i * 0.01, false, 1, 3));
            }
            var result = policy.apply(model(nodes, List.of()));
            assertThat(result.nodes().size()).isLessThanOrEqualTo(25);
        }

        @Test
        void edgesFilteredByIncludedNodes() {
            var n1 = node("CP-1023", "Cap", "Capabilities", 0.8, false, 1, 3);
            var n2 = node("CR-1047", "Svc", "Core Services", 0.7, false, 3, 3);
            var e1 = edge("e1", "CP-1023", "CR-1047", "REALIZES", 0.7);
            var e2 = edge("e2", "CP-1023", "XX-MISSING", "USES", 0.5);
            var result = policy.apply(model(List.of(n1, n2), List.of(e1, e2)));
            assertThat(result.edges()).hasSize(1);
            assertThat(result.edges().get(0).sourceId()).isEqualTo("CP-1023");
        }

        @Test
        void edgesLimitedToMaxEdges() {
            List<DiagramNode> nodes = new ArrayList<>();
            List<DiagramEdge> edges = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                nodes.add(node("CP-" + String.format("%04d", i), "N" + i,
                        "Capabilities", 0.9, false, 1, 3));
            }
            for (int i = 0; i < 10; i++) {
                for (int j = i + 1; j < 10; j++) {
                    edges.add(edge("e" + i + "_" + j,
                            "CP-" + String.format("%04d", i),
                            "CP-" + String.format("%04d", j),
                            "RELATED_TO", 0.5));
                }
            }
            var result = policy.apply(model(nodes, edges));
            assertThat(result.edges().size()).isLessThanOrEqualTo(40);
        }

        @Test
        void anchorsAreSortedFirst() {
            var normal = node("CP-1023", "Normal", "Capabilities", 0.9, false, 1, 3);
            var anchor = node("CP-1024", "Anchor", "Capabilities", 0.5, true, 1, 3);
            var result = policy.apply(model(List.of(normal, anchor), List.of()));
            assertThat(result.nodes().get(0).id()).isEqualTo("CP-1024");
        }

        @Test
        void impactEdgesSortedFirst() {
            var n1 = node("CP-1023", "Cap", "Capabilities", 0.8, false, 1, 3);
            var n2 = node("CR-1047", "Svc", "Core Services", 0.7, false, 3, 3);
            var trace = new DiagramEdge("e1", "CP-1023", "CR-1047", "REALIZES", 0.8, "trace");
            var impact = new DiagramEdge("e2", "CP-1023", "CR-1047", "SUPPORTS", 0.5, "impact");
            var result = policy.apply(model(List.of(n1, n2), List.of(trace, impact)));
            assertThat(result.edges().get(0).relationCategory()).isEqualTo("impact");
        }
    }

    // ── Leaf-only policy ────────────────────────────────────────────────

    @Nested
    class LeafOnlyPolicyTest {

        private final DiagramSelectionPolicy policy = new LeafOnlyDiagramSelectionPolicy();

        @Test
        void rootNodesSuppressedWhenLeavesExist() {
            var root = node("CP", "Capabilities", "Capabilities", 0.9, true, 1, 0);
            var leaf = node("CP-1023", "Messaging", "Capabilities", 0.8, true, 1, 3);
            var result = policy.apply(model(List.of(root, leaf), List.of()));
            assertThat(ids(result)).containsExactly("CP-1023");
        }

        @Test
        void scaffoldingSuppressedWhenLeavesExist() {
            var scaffolding = node("CP-1000", "Container", "Capabilities", 0.9, true, 1, 1);
            var leaf = node("CP-1023", "Messaging", "Capabilities", 0.8, true, 1, 3);
            var result = policy.apply(model(List.of(scaffolding, leaf), List.of()));
            assertThat(ids(result)).containsExactly("CP-1023");
        }

        @Test
        void rootKeptWhenNoLeavesExist() {
            var root = node("CI", "COI Services", "COI Services", 0.7, false, 3, 0);
            var result = policy.apply(model(List.of(root), List.of()));
            assertThat(ids(result)).containsExactly("CI");
        }

        @Test
        void edgesReroutedFromSuppressedToLeaf() {
            var rootCp = node("CP", "Cap", "Capabilities", 0.9, true, 1, 0);
            var leafCp = node("CP-1023", "Msg", "Capabilities", 0.85, true, 1, 3);
            var rootCr = node("CR", "Svc", "Core Services", 0.8, true, 3, 0);
            var leafCr = node("CR-1047", "Infra", "Core Services", 0.75, true, 3, 3);
            var edg = edge("e1", "CP", "CR", "REALIZES", 0.7);
            var result = policy.apply(model(
                    List.of(rootCp, leafCp, rootCr, leafCr), List.of(edg)));
            assertThat(result.edges()).hasSize(1);
            assertThat(result.edges().get(0).sourceId()).isEqualTo("CP-1023");
            assertThat(result.edges().get(0).targetId()).isEqualTo("CR-1047");
        }

        @Test
        void duplicateReroutedEdgesAreDeduped() {
            var rootCp = node("CP", "Cap", "Capabilities", 0.9, true, 1, 0);
            var leafCp = node("CP-1023", "Cap", "Capabilities", 0.85, true, 1, 3);
            var rootCr = node("CR", "Svc", "Core Services", 0.8, true, 3, 0);
            var leafCr = node("CR-1047", "Svc", "Core Services", 0.75, true, 3, 3);
            var e1 = edge("e1", "CP", "CR", "REALIZES", 0.7);
            var e2 = edge("e2", "CP", "CR", "REALIZES", 0.6);
            var result = policy.apply(model(
                    List.of(rootCp, leafCp, rootCr, leafCr), List.of(e1, e2)));
            long count = result.edges().stream()
                    .filter(e -> "CP-1023".equals(e.sourceId()) && "CR-1047".equals(e.targetId()))
                    .count();
            assertThat(count).isEqualTo(1);
        }

        @Test
        void selfLoopEdgesRemovedAfterRerouting() {
            var root = node("CP", "Cap", "Capabilities", 0.9, true, 1, 0);
            var leaf = node("CP-1023", "Cap", "Capabilities", 0.85, true, 1, 3);
            var selfEdge = edge("e1", "CP", "CP", "REALIZES", 0.5);
            var result = policy.apply(model(List.of(root, leaf), List.of(selfEdge)));
            assertThat(result.edges()).isEmpty();
        }

        @Test
        void edgesLimitedToShowcaseMax() {
            var cap = node("CP-1023", "Cap", "Capabilities", 0.9, true, 1, 3);
            List<DiagramNode> nodes = new ArrayList<>(List.of(cap));
            List<DiagramEdge> edges = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                String nodeId = "CR-" + (1000 + i);
                nodes.add(node(nodeId, "Svc" + i, "Core Services", 0.5, false, 3, 3));
                edges.add(edge("e" + i, "CP-1023", nodeId, "REALIZES", 0.5));
            }
            var result = policy.apply(model(nodes, edges));
            assertThat(result.edges().size()).isLessThanOrEqualTo(12);
        }
    }

    // ── Clustering policy ───────────────────────────────────────────────

    @Nested
    class ClusteringPolicyTest {

        private final DiagramSelectionPolicy policy = new ClusteringDiagramSelectionPolicy();

        @Test
        void intermediateWithMultipleChildrenBecomesContainer() {
            var parent = nodeWithParent("BP-1327", "Enable", "Business Processes",
                    0.8, false, 2, 2, null);
            var child1 = nodeWithParent("BP-1490", "Health Services", "Business Processes",
                    0.7, false, 2, 3, "BP-1327");
            var child2 = nodeWithParent("BP-1697", "Medical Command", "Business Processes",
                    0.6, false, 2, 3, "BP-1327");
            var result = policy.apply(model(List.of(parent, child1, child2), List.of()));

            // Parent must be a container
            DiagramNode container = result.nodes().stream()
                    .filter(n -> "BP-1327".equals(n.id())).findFirst().orElseThrow();
            assertThat(container.container()).isTrue();

            // Children must still be present and reference the container
            assertThat(ids(result)).containsExactlyInAnyOrder("BP-1327", "BP-1490", "BP-1697");
            assertThat(result.nodes().stream()
                    .filter(n -> "BP-1490".equals(n.id())).findFirst().orElseThrow()
                    .parentId()).isEqualTo("BP-1327");
        }

        @Test
        void intermediateWithSingleChildIsCollapsed() {
            var parent = nodeWithParent("BP-1327", "Enable", "Business Processes",
                    0.8, false, 2, 2, null);
            var child = nodeWithParent("BP-1490", "Health Services", "Business Processes",
                    0.7, false, 2, 3, "BP-1327");
            var result = policy.apply(model(List.of(parent, child), List.of()));

            // Parent suppressed, child lifted
            assertThat(ids(result)).containsExactly("BP-1490");
            // Child's parent reference cleared (lifted to top level)
            assertThat(result.nodes().get(0).parentId()).isNull();
        }

        @Test
        void containerFlagIsNotSetOnLeafNodes() {
            var leaf = node("BP-1490", "Health", "Business Processes", 0.7, false, 2, 3);
            var result = policy.apply(model(List.of(leaf), List.of()));
            assertThat(result.nodes().get(0).container()).isFalse();
        }

        @Test
        void containmentWorksGenericAcrossAllCategories() {
            // Business Processes
            var bpParent = nodeWithParent("BP-1327", "Enable", "Business Processes",
                    0.8, false, 2, 2, null);
            var bp1 = nodeWithParent("BP-1490", "Health", "Business Processes",
                    0.7, false, 2, 3, "BP-1327");
            var bp2 = nodeWithParent("BP-1697", "Medical", "Business Processes",
                    0.6, false, 2, 3, "BP-1327");
            // Capabilities
            var cpParent = nodeWithParent("CP-1050", "Comm Caps", "Capabilities",
                    0.8, false, 1, 2, null);
            var cp1 = nodeWithParent("CP-1051", "Voice", "Capabilities",
                    0.7, false, 1, 3, "CP-1050");
            var cp2 = nodeWithParent("CP-1052", "Data", "Capabilities",
                    0.6, false, 1, 3, "CP-1050");

            var result = policy.apply(model(
                    List.of(bpParent, bp1, bp2, cpParent, cp1, cp2), List.of()));

            // Both parents become containers
            assertThat(result.nodes().stream()
                    .filter(DiagramNode::container).map(DiagramNode::id).collect(Collectors.toSet()))
                    .containsExactlyInAnyOrder("BP-1327", "CP-1050");
        }

        @Test
        void containerNodesNotCountedAsArchitectureElements() {
            // Container-only nodes must have container=true so ArchiMate export can skip them
            var parent = nodeWithParent("BP-1327", "Enable", "Business Processes",
                    0.8, false, 2, 2, null);
            var child1 = nodeWithParent("BP-1490", "Health", "Business Processes",
                    0.7, false, 2, 3, "BP-1327");
            var child2 = nodeWithParent("BP-1697", "Medical", "Business Processes",
                    0.6, false, 2, 3, "BP-1327");
            var result = policy.apply(model(List.of(parent, child1, child2), List.of()));

            // Only non-container nodes are semantically valid architecture elements
            long architectureElements = result.nodes().stream()
                    .filter(n -> !n.container()).count();
            assertThat(architectureElements).isEqualTo(2); // child1 + child2

            long containers = result.nodes().stream()
                    .filter(DiagramNode::container).count();
            assertThat(containers).isEqualTo(1); // parent
        }
    }

    // ── Trace policy ────────────────────────────────────────────────────

    @Nested
    class TracePolicyTest {

        private final DiagramSelectionPolicy policy = new TraceDiagramSelectionPolicy();

        @Test
        void nothingIsSuppressed() {
            var root = node("CP", "Capabilities", "Capabilities", 0.9, false, 1, 0);
            var scaffolding = node("CP-1000", "Container", "Capabilities", 0.7, false, 1, 1);
            var leaf = node("CP-1023", "Messaging", "Capabilities", 0.5, false, 1, 3);
            var result = policy.apply(model(List.of(root, scaffolding, leaf), List.of()));
            assertThat(ids(result)).containsExactlyInAnyOrder("CP", "CP-1000", "CP-1023");
        }

        @Test
        void lowRelevanceNodesAreKept() {
            var lowRel = node("CP-1023", "Low", "Capabilities", 0.01, false, 1, 3);
            var result = policy.apply(model(List.of(lowRel), List.of()));
            assertThat(ids(result)).containsExactly("CP-1023");
        }

        @Test
        void allEdgesAreKeptUpToLimit() {
            var n1 = node("CP-1023", "Cap", "Capabilities", 0.8, false, 1, 3);
            var n2 = node("CR-1047", "Svc", "Core Services", 0.7, false, 3, 3);
            var e1 = edge("e1", "CP-1023", "CR-1047", "REALIZES", 0.7);
            var e2 = edge("e2", "CR-1047", "CP-1023", "SUPPORTS", 0.3);
            var result = policy.apply(model(List.of(n1, n2), List.of(e1, e2)));
            assertThat(result.edges()).hasSize(2);
        }

        @Test
        void higherNodeLimitThanDefault() {
            // Trace config has maxNodes=50
            List<DiagramNode> nodes = new ArrayList<>();
            for (int i = 0; i < 45; i++) {
                nodes.add(node("CP-" + String.format("%04d", 1000 + i), "N" + i,
                        "Capabilities", 0.5, false, 1, 3));
            }
            var result = policy.apply(model(nodes, List.of()));
            assertThat(result.nodes()).hasSize(45);
        }
    }

    // ── Custom config tests ─────────────────────────────────────────────

    @Nested
    class CustomConfigTest {

        @Test
        void customMinRelevance() {
            var config = new DiagramSelectionConfig(
                    false, false, false, false, false, false, false,
                    0.7, 25, 40);
            var policy = new ConfigurableDiagramSelectionPolicy(config);

            var hi = node("CP-1023", "Hi", "Capabilities", 0.8, false, 1, 3);
            var lo = node("CP-1024", "Lo", "Capabilities", 0.5, false, 1, 3);
            var result = policy.apply(model(List.of(hi, lo), List.of()));
            assertThat(ids(result)).containsExactly("CP-1023");
        }

        @Test
        void customMaxNodes() {
            var config = new DiagramSelectionConfig(
                    false, false, false, false, false, false, false,
                    0.0, 3, 40);
            var policy = new ConfigurableDiagramSelectionPolicy(config);

            List<DiagramNode> nodes = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                nodes.add(node("CP-" + (1000 + i), "N" + i, "Capabilities", 0.5, false, 1, 3));
            }
            var result = policy.apply(model(nodes, List.of()));
            assertThat(result.nodes().size()).isLessThanOrEqualTo(3);
        }

        @Test
        void configPresetsHaveExpectedValues() {
            assertThat(DiagramSelectionConfig.defaultImpact().suppressRootNodes()).isTrue();
            assertThat(DiagramSelectionConfig.defaultImpact().minRelevance()).isEqualTo(0.35);

            assertThat(DiagramSelectionConfig.leafOnly().leafOnlyMode()).isTrue();
            assertThat(DiagramSelectionConfig.leafOnly().maxEdges()).isEqualTo(12);

            assertThat(DiagramSelectionConfig.clustering().allowIntermediateAsClusters()).isTrue();
            assertThat(DiagramSelectionConfig.clustering().collapseRedundantParentChild()).isTrue();

            assertThat(DiagramSelectionConfig.trace().suppressRootNodes()).isFalse();
            assertThat(DiagramSelectionConfig.trace().minRelevance()).isEqualTo(0.0);
        }

        @Test
        void sameGeneralTypeCanBehaveDifferently() {
            // Two default-like policies with different relevance thresholds
            var strict = new ConfigurableDiagramSelectionPolicy(
                    new DiagramSelectionConfig(true, true, false, false, false, false, true,
                            0.7, 25, 40));
            var lenient = new ConfigurableDiagramSelectionPolicy(
                    new DiagramSelectionConfig(true, true, false, false, false, false, true,
                            0.1, 25, 40));

            var node = node("CP-1023", "Cap", "Capabilities", 0.5, false, 1, 3);
            var model = model(List.of(node), List.of());

            assertThat(strict.apply(model).nodes()).isEmpty();
            assertThat(lenient.apply(model).nodes()).hasSize(1);
        }
    }

    // ── ArchiMate export separation tests ───────────────────────────────

    @Nested
    class ArchiMateExportSeparationTest {

        @Test
        void containerNodesAreSkippedInArchiMateConversion() {
            // Container node
            var container = new DiagramNode("BP-1327", "Enable", "Business Processes",
                    0.8, false, 2, 2, false, null, true);
            // Normal child nodes
            var child1 = new DiagramNode("BP-1490", "Health", "Business Processes",
                    0.7, false, 2, 3, false, "BP-1327", false);
            var child2 = new DiagramNode("BP-1697", "Medical", "Business Processes",
                    0.6, false, 2, 3, false, "BP-1327", false);

            var model = new DiagramModel("Test", List.of(container, child1, child2),
                    List.of(), new DiagramLayout("LR", true));

            var archiMate = new ArchiMateDiagramService().convert(model);

            // Container should NOT appear as an ArchiMate element
            assertThat(archiMate.elements().stream()
                    .map(e -> e.id()).collect(Collectors.toSet()))
                    .containsExactlyInAnyOrder("BP-1490", "BP-1697");

            // Container should NOT appear in organizations
            assertThat(archiMate.organizations().values().stream()
                    .flatMap(List::stream).collect(Collectors.toSet()))
                    .doesNotContain("BP-1327");
        }

        @Test
        void normalNodesStillAppearInArchiMate() {
            var normal = node("CP-1023", "Messaging", "Capabilities", 0.8, false, 1, 3);
            var model = model(List.of(normal), List.of());

            var archiMate = new ArchiMateDiagramService().convert(model);

            assertThat(archiMate.elements()).hasSize(1);
            assertThat(archiMate.elements().get(0).label()).isEqualTo("Messaging");
        }
    }

    // ── Containment rendering test ──────────────────────────────────────

    @Nested
    class ContainmentRenderingTest {

        private final MermaidExportService mermaid = new MermaidExportService();

        @Test
        void containerRenderedAsNestedSubgraph() {
            var container = new DiagramNode("BP-1327", "Enable", "Business Processes",
                    0.8, false, 2, 2, false, null, true);
            var child1 = new DiagramNode("BP-1490", "Health Services", "Business Processes",
                    0.7, false, 2, 3, false, "BP-1327", false);
            var child2 = new DiagramNode("BP-1697", "Medical Command", "Business Processes",
                    0.6, false, 2, 3, false, "BP-1327", false);
            var model = new DiagramModel("Test", List.of(container, child1, child2),
                    List.of(), new DiagramLayout("LR", true));

            String result = mermaid.export(model);

            // Container should be a nested subgraph
            assertThat(result).contains("subgraph BP_1327[\"Enable\"]");
            // Children should appear inside the container
            assertThat(result).contains("BP_1490");
            assertThat(result).contains("BP_1697");
            // Children should NOT get class assignments for container
            assertThat(result).doesNotContain("class BP_1327 ");
        }

        @Test
        void flatNodesRenderNormally() {
            var n1 = node("CP-1023", "Cap A", "Capabilities", 0.8, false, 1, 3);
            var n2 = node("CP-1024", "Cap B", "Capabilities", 0.7, false, 1, 3);
            var model = model(List.of(n1, n2), List.of());

            String result = mermaid.export(model);

            // Should render as flat nodes inside the layer subgraph
            assertThat(result).contains("CP_1023[\"CP-1023 Cap A");
            assertThat(result).contains("CP_1024[\"CP-1024 Cap B");
        }
    }

    // ── Utility methods ─────────────────────────────────────────────────

    private static DiagramNode node(String id, String label, String type,
                                     double relevance, boolean anchor, int layer, int depth) {
        return new DiagramNode(id, label, type, relevance, anchor, layer, depth, false, null, false);
    }

    private static DiagramNode nodeWithParent(String id, String label, String type,
                                               double relevance, boolean anchor, int layer,
                                               int depth, String parentId) {
        return new DiagramNode(id, label, type, relevance, anchor, layer, depth, false, parentId, false);
    }

    private static DiagramEdge edge(String id, String src, String tgt, String type, double rel) {
        return new DiagramEdge(id, src, tgt, type, rel);
    }

    private static DiagramModel model(List<DiagramNode> nodes, List<DiagramEdge> edges) {
        return new DiagramModel("Test", nodes, edges, new DiagramLayout("LR", true));
    }

    private static Set<String> ids(DiagramModel model) {
        return model.nodes().stream().map(DiagramNode::id).collect(Collectors.toSet());
    }
}
