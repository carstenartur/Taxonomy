package com.taxonomy.analysis.relations;

import com.taxonomy.analysis.service.LlmResponseParser;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.taxonomy.dto.RelationSearchModel.*;
import static org.junit.jupiter.api.Assertions.*;

class ReviewBoundaryContinuationTest {
    @ParameterizedTest
    @ValueSource(strings={"0.999999999999999999", "1.000000000000000001", "100.000000000000000001"})
    void rejectsExactFractionsRoundedToIntegersByDouble(String number) {
        var parser = new LlmResponseParser(JsonMapper.builder().build());
        var node = new TaxonomyNode(); node.setCode("A");
        assertThrows(IllegalArgumentException.class, () -> parser.parseScoreParseResult(
                "{\"A\":{\"score\":" + number + ",\"reason\":\"test\"}}", List.of(node), 100));
    }

    @ParameterizedTest
    @ValueSource(strings={"1.0", "1e0", "1"})
    void acceptsExactWholeNumberWithoutChangingNormalization(String number) throws Exception {
        var parser = new LlmResponseParser(JsonMapper.builder().build());
        var node = new TaxonomyNode(); node.setCode("A");
        assertEquals(Map.of("A",1), parser.parseScoreParseResult("{\"A\":"+number+"}", List.of(node), 1).scores());
    }

    @Test
    void duplicateRootSetFailsBeforeContributionExtraction() {
        var root = new Node("IP", "IP", "Information", "", true);
        assertInvalidRootsSpendNoCalls(List.of(root, root));
    }

    @Test
    void nullRootFailsBeforeContributionExtraction() {
        assertInvalidRootsSpendNoCalls(Arrays.asList((Node)null));
    }

    private static void assertInvalidRootsSpendNoCalls(List<Node> roots) {
        AtomicInteger calls = new AtomicInteger();
        var source = new Node("process", "BP", "Process", "", false);
        var catalogue = new RequirementRelationSearch.InputCatalogue() {
            public Node find(String id) { return source; }
            public List<Node> roots() { return roots; }
            public List<Node> children(Node node) { return List.of(); }
        };
        var search = new RequirementRelationSearch(catalogue, new RelationCompatibilityMatrix(),
                prompt -> { calls.incrementAndGet(); return "invalid model reply"; }, () -> {});
        var report = search.search("Read evidence.", Map.of("process",1),
                new RequirementRelationSearch.Options(new Limits(8,4,10,32),4));
        assertEquals(0, calls.get(), "invalid catalogue roots must not consume extraction calls");
        assertEquals(0, report.totalCalls());
        assertFalse(report.isSearchExhausted());
        assertFalse(report.stopReason().isEmpty());
    }
}
