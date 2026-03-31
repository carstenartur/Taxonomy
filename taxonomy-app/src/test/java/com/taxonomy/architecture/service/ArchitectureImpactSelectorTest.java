package com.taxonomy.architecture.service;

import com.taxonomy.dto.NodeOrigin;
import com.taxonomy.dto.RequirementElementView;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureImpactSelectorTest {

    private final ArchitectureImpactSelector selector = new ArchitectureImpactSelector();

    @Test
    void selectForImpactReturnsEmptyForEmptyInput() {
        List<RequirementElementView> result = selector.selectForImpact(
                List.of(), Map.of(), Set.of());
        assertThat(result).isEmpty();
    }

    @Test
    void selectForImpactReturnsNullSafely() {
        assertThat(selector.selectForImpact(null, Map.of(), Set.of())).isNull();
    }

    @Test
    void selectsHighScoringLeafNodesAsImpact() {
        RequirementElementView leaf = createElement("CP-1023", "Secure Communications", "CP", 3, 85);
        List<RequirementElementView> elements = new ArrayList<>(List.of(leaf));

        selector.selectForImpact(elements, Map.of("CP-1023", 85), Set.of());

        assertThat(leaf.isSelectedForImpact()).isTrue();
    }

    @Test
    void limitsImpactNodesPerCategory() {
        List<RequirementElementView> elements = new ArrayList<>();
        Map<String, Integer> scores = new LinkedHashMap<>();

        // Create more than MAX_IMPACT_PER_CATEGORY elements in same category
        for (int i = 0; i < 8; i++) {
            String code = "CP-10" + String.format("%02d", i);
            elements.add(createElement(code, "Node " + i, "CP", 3, 70 + i));
            scores.put(code, 70 + i);
        }

        selector.selectForImpact(elements, scores, Set.of());

        long selectedCount = elements.stream()
                .filter(RequirementElementView::isSelectedForImpact)
                .count();
        assertThat(selectedCount).isLessThanOrEqualTo(ArchitectureImpactSelector.MAX_IMPACT_PER_CATEGORY);
    }

    @Test
    void crossCategoryBonusIncreasesScore() {
        RequirementElementView crossCat = createElement("CP-1023", "Service A", "CP", 3, 60);
        RequirementElementView nonCrossCat = createElement("CP-1024", "Service B", "CP", 3, 60);

        double crossScore = selector.computeImpactScore(
                crossCat, Map.of("CP-1023", 60), Set.of("CP-1023"), List.of());
        double nonCrossScore = selector.computeImpactScore(
                nonCrossCat, Map.of("CP-1024", 60), Set.of(), List.of());

        assertThat(crossScore).isGreaterThan(nonCrossScore);
    }

    @Test
    void deeperNodesScorerHigherSpecificity() {
        RequirementElementView shallow = createElement("CP-1000", "Top Level", "CP", 1, 70);
        RequirementElementView deep = createElement("CP-1023", "Deep Level", "CP", 4, 70);

        double shallowScore = selector.computeImpactScore(
                shallow, Map.of("CP-1000", 70), Set.of(), List.of());
        double deepScore = selector.computeImpactScore(
                deep, Map.of("CP-1023", 70), Set.of(), List.of());

        assertThat(deepScore).isGreaterThan(shallowScore);
    }

    @Test
    void genericWeakNodesAreSkipped() {
        RequirementElementView generic = createElement("CP-1000", "Enable", "CP", 1, 80);
        RequirementElementView concrete = createElement("CP-1023", "Secure Messaging Service", "CP", 3, 50);
        List<RequirementElementView> elements = new ArrayList<>(List.of(generic, concrete));
        Map<String, Integer> scores = Map.of("CP-1000", 80, "CP-1023", 50);

        boolean isWeak = selector.isGenericWeakNode(generic, scores, elements);

        assertThat(isWeak).isTrue();
    }

    @Test
    void nonGenericNamedNodeNotConsideredWeak() {
        RequirementElementView node = createElement("CP-1000", "Clinical Workflow Management", "CP", 1, 80);
        RequirementElementView child = createElement("CP-1023", "Sub Service", "CP", 3, 50);
        List<RequirementElementView> elements = List.of(node, child);
        Map<String, Integer> scores = Map.of("CP-1000", 80, "CP-1023", 50);

        boolean isWeak = selector.isGenericWeakNode(node, scores, elements);

        assertThat(isWeak).isFalse();
    }

    @Test
    void setsImpactSelectedOriginOnSelectedNodes() {
        RequirementElementView leaf = createElement("CP-1023", "Service X", "CP", 3, 85);
        leaf.setOrigin(NodeOrigin.PROPAGATED);
        List<RequirementElementView> elements = new ArrayList<>(List.of(leaf));

        selector.selectForImpact(elements, Map.of("CP-1023", 85), Set.of());

        assertThat(leaf.getOrigin()).isEqualTo(NodeOrigin.IMPACT_PROMOTED);
    }

    @Test
    void preservesDirectScoredOriginOnSelectedNodes() {
        RequirementElementView leaf = createElement("CP-1023", "Service X", "CP", 3, 85);
        leaf.setOrigin(NodeOrigin.DIRECT_SCORED);
        List<RequirementElementView> elements = new ArrayList<>(List.of(leaf));

        selector.selectForImpact(elements, Map.of("CP-1023", 85), Set.of());

        // DIRECT_SCORED should not be downgraded to IMPACT_PROMOTED
        assertThat(leaf.getOrigin()).isEqualTo(NodeOrigin.DIRECT_SCORED);
    }

    @Test
    void scaffoldingNodesAreSkippedWhenConcreteDescendantsExist() {
        // CP-1000 at depth 1 is taxonomy scaffolding; CP-1023 at depth 3 is concrete
        RequirementElementView scaffolding = createElement("CP-1000", "Capabilities", "CP", 1, 90);
        RequirementElementView concrete = createElement("CP-1023", "Secure Messaging Service", "CP", 3, 70);
        List<RequirementElementView> elements = new ArrayList<>(List.of(scaffolding, concrete));
        Map<String, Integer> scores = Map.of("CP-1000", 90, "CP-1023", 70);

        selector.selectForImpact(elements, scores, Set.of());

        assertThat(scaffolding.isSelectedForImpact()).isFalse();
        assertThat(concrete.isSelectedForImpact()).isTrue();
    }

    @Test
    void scaffoldingNodeSelectedWhenNoConcreteDescendantsExist() {
        // Only depth-1 node in the category — should still be selected
        RequirementElementView scaffolding = createElement("CP-1000", "Capabilities", "CP", 1, 85);
        List<RequirementElementView> elements = new ArrayList<>(List.of(scaffolding));

        selector.selectForImpact(elements, Map.of("CP-1000", 85), Set.of());

        assertThat(scaffolding.isSelectedForImpact()).isTrue();
    }

    @Test
    void rootNodeAtDepthZeroIsScaffolding() {
        RequirementElementView root = createElement("CP", "Capabilities", "CP", 0, 92);
        RequirementElementView leaf = createElement("CP-1023", "Service", "CP", 3, 70);
        List<RequirementElementView> elements = new ArrayList<>(List.of(root, leaf));

        boolean isScaffolding = selector.isTaxonomyScaffolding(root, elements);

        assertThat(isScaffolding).isTrue();
    }

    @Test
    void scaffoldingDetectionAcrossAllCategories() {
        // Verify the pattern applies to all 8 root categories
        for (String cat : List.of("BP", "BR", "CP", "CI", "CO", "CR", "IP", "UA")) {
            RequirementElementView container = createElement(cat + "-1000", cat + " container", cat, 1, 80);
            RequirementElementView concrete = createElement(cat + "-1023", cat + " concrete", cat, 3, 60);
            List<RequirementElementView> elements = new ArrayList<>(List.of(container, concrete));

            boolean isScaffolding = selector.isTaxonomyScaffolding(container, elements);
            assertThat(isScaffolding)
                    .as("Category %s: depth-1 container should be scaffolding", cat)
                    .isTrue();
        }
    }

    @Test
    void seedContextNodesPreserveOriginWhenSelected() {
        RequirementElementView seedNode = createElement("CP-1023", "Communication Capabilities", "CP", 3, 85);
        seedNode.setOrigin(NodeOrigin.SEED_CONTEXT);
        List<RequirementElementView> elements = new ArrayList<>(List.of(seedNode));

        selector.selectForImpact(elements, Map.of("CP-1023", 85), Set.of());

        // SEED_CONTEXT should not be overridden to IMPACT_PROMOTED
        assertThat(seedNode.getOrigin()).isEqualTo(NodeOrigin.SEED_CONTEXT);
        assertThat(seedNode.isSelectedForImpact()).isTrue();
    }

    @Test
    void redundantIntermediateWithSingleStrongChildIsSkipped() {
        RequirementElementView intermediate = createElement("BP-1327", "Enable", "BP", 2, 80);
        intermediate.setHierarchyPath("BP > BP-1000 > BP-1327");
        RequirementElementView child = createElement("BP-1490", "Health Services", "BP", 3, 60);
        child.setHierarchyPath("BP > BP-1000 > BP-1327 > BP-1490");
        List<RequirementElementView> elements = new ArrayList<>(List.of(intermediate, child));
        Map<String, Integer> scores = Map.of("BP-1327", 80, "BP-1490", 60);

        boolean redundant = selector.isRedundantIntermediate(intermediate, scores, elements);

        assertThat(redundant).isTrue();
    }

    @Test
    void intermediateNotRedundantWhenChildScoreIsTooLow() {
        RequirementElementView intermediate = createElement("BP-1327", "Enable", "BP", 2, 80);
        intermediate.setHierarchyPath("BP > BP-1000 > BP-1327");
        RequirementElementView child = createElement("BP-1490", "Health Services", "BP", 3, 10);
        child.setHierarchyPath("BP > BP-1000 > BP-1327 > BP-1490");
        List<RequirementElementView> elements = new ArrayList<>(List.of(intermediate, child));
        Map<String, Integer> scores = Map.of("BP-1327", 80, "BP-1490", 10);

        boolean redundant = selector.isRedundantIntermediate(intermediate, scores, elements);

        assertThat(redundant).isFalse();
    }

    @Test
    void intermediateNotRedundantWithMultipleStrongChildren() {
        RequirementElementView intermediate = createElement("BP-1327", "Enable", "BP", 2, 80);
        intermediate.setHierarchyPath("BP > BP-1000 > BP-1327");
        RequirementElementView child1 = createElement("BP-1490", "Health", "BP", 3, 60);
        child1.setHierarchyPath("BP > BP-1000 > BP-1327 > BP-1490");
        RequirementElementView child2 = createElement("BP-1697", "Medical", "BP", 3, 55);
        child2.setHierarchyPath("BP > BP-1000 > BP-1327 > BP-1697");
        List<RequirementElementView> elements = new ArrayList<>(List.of(intermediate, child1, child2));
        Map<String, Integer> scores = Map.of("BP-1327", 80, "BP-1490", 60, "BP-1697", 55);

        boolean redundant = selector.isRedundantIntermediate(intermediate, scores, elements);

        assertThat(redundant).isFalse();
    }

    @Test
    void scaffoldingAtDepthOneNotTreatedAsRedundantIntermediate() {
        RequirementElementView scaffolding = createElement("CP-1000", "Capabilities", "CP", 1, 90);
        RequirementElementView leaf = createElement("CP-1023", "Service", "CP", 3, 60);
        leaf.setHierarchyPath("CP > CP-1000 > CP-1023");
        List<RequirementElementView> elements = new ArrayList<>(List.of(scaffolding, leaf));
        Map<String, Integer> scores = Map.of("CP-1000", 90, "CP-1023", 60);

        // Depth 1 should not be flagged as redundant intermediate (it's scaffolding)
        boolean redundant = selector.isRedundantIntermediate(scaffolding, scores, elements);

        assertThat(redundant).isFalse();
    }

    @Test
    void selectedImpactNodesHavePresenceReason() {
        RequirementElementView leaf = createElement("CP-1023", "Secure Messaging", "CP", 3, 85);
        List<RequirementElementView> elements = new ArrayList<>(List.of(leaf));

        selector.selectForImpact(elements, Map.of("CP-1023", 85), Set.of());

        assertThat(leaf.getPresenceReason()).isNotNull();
        assertThat(leaf.getPresenceReason()).contains("CP-1023");
        assertThat(leaf.getPresenceReason()).contains("Secure Messaging");
    }

    private static RequirementElementView createElement(String code, String title,
                                                         String sheet, int depth, int score) {
        RequirementElementView el = new RequirementElementView();
        el.setNodeCode(code);
        el.setTitle(title);
        el.setTaxonomySheet(sheet);
        el.setTaxonomyDepth(depth);
        el.setRelevance(score / 100.0);
        el.setDirectLlmScore(score);
        return el;
    }
}
