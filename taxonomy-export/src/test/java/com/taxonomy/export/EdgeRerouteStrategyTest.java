package com.taxonomy.export;

import com.taxonomy.diagram.DiagramNode;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class EdgeRerouteStrategyTest {

    @Test
    void emptyListReturnsEmpty() {
        EdgeRerouteStrategy strategy = new EdgeRerouteStrategy();
        DiagramNode suppressed = node("CP", "Cap", "Capabilities", 0.9, 0, null);
        assertThat(strategy.selectTarget(suppressed, List.of())).isEmpty();
    }

    @Test
    void nullListReturnsEmpty() {
        EdgeRerouteStrategy strategy = new EdgeRerouteStrategy();
        DiagramNode suppressed = node("CP", "Cap", "Capabilities", 0.9, 0, null);
        assertThat(strategy.selectTarget(suppressed, null)).isEmpty();
    }

    @Test
    void directDescendantPreferred() {
        EdgeRerouteStrategy strategy = new EdgeRerouteStrategy();
        DiagramNode parent = node("CP", "Cap", "Capabilities", 0.9, 0, null);
        DiagramNode child = node("CP-1023", "Msg", "Capabilities", 0.7, 3, "CP");
        DiagramNode other = node("CP-1050", "Other", "Capabilities", 0.95, 3, null);

        Optional<String> result = strategy.selectTarget(parent, List.of(other, child));
        assertThat(result).contains("CP-1023");
    }

    @Test
    void highestRelevanceSelectedWhenNoDescendant() {
        EdgeRerouteStrategy strategy = new EdgeRerouteStrategy();
        DiagramNode suppressed = node("CP-1000", "Container", "Capabilities", 0.9, 1, null);
        DiagramNode high = node("CP-1023", "Msg", "Capabilities", 0.8, 3, null);
        DiagramNode low = node("CP-1050", "Other", "Capabilities", 0.6, 3, null);

        Optional<String> result = strategy.selectTarget(suppressed, List.of(low, high));
        assertThat(result).contains("CP-1023");
    }

    @Test
    void loadBalancingDistributesAcrossSurvivors() {
        EdgeRerouteStrategy strategy = new EdgeRerouteStrategy();
        DiagramNode high = node("CP-1023", "Msg", "Capabilities", 0.8, 3, null);
        DiagramNode medium = node("CP-1050", "Other", "Capabilities", 0.6, 3, null);
        List<DiagramNode> survivors = List.of(high, medium);

        // Fill up the high-relevance survivor to the limit
        for (int i = 0; i < EdgeRerouteStrategy.MAX_REROUTES_PER_TARGET; i++) {
            DiagramNode suppressed = node("X-" + i, "X" + i, "Capabilities", 0.1, 1, null);
            strategy.selectTarget(suppressed, survivors);
        }

        // Next reroute should go to the medium-relevance survivor (load balancing)
        DiagramNode nextSuppressed = node("X-next", "Next", "Capabilities", 0.1, 1, null);
        Optional<String> result = strategy.selectTarget(nextSuppressed, survivors);
        assertThat(result).contains("CP-1050");
    }

    @Test
    void fallbackToHighestWhenAllOverloaded() {
        EdgeRerouteStrategy strategy = new EdgeRerouteStrategy();
        DiagramNode single = node("CP-1023", "Msg", "Capabilities", 0.8, 3, null);
        List<DiagramNode> survivors = List.of(single);

        // Overload the single survivor
        for (int i = 0; i < EdgeRerouteStrategy.MAX_REROUTES_PER_TARGET + 2; i++) {
            DiagramNode suppressed = node("X-" + i, "X" + i, "Capabilities", 0.1, 1, null);
            strategy.selectTarget(suppressed, survivors);
        }

        // Should still return the single survivor as fallback
        DiagramNode last = node("X-last", "Last", "Capabilities", 0.1, 1, null);
        Optional<String> result = strategy.selectTarget(last, survivors);
        assertThat(result).contains("CP-1023");
    }

    private static DiagramNode node(String id, String label, String type,
                                     double relevance, int depth, String parentId) {
        return new DiagramNode(id, label, type, relevance, false, 1, depth, false, parentId, false);
    }
}
