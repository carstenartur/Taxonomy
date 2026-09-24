package com.taxonomy.analysis.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;

/** Pure parser contract; IP is an existing catalogue root identity, not a made-up product tree. */
final class IndependentProductScoreChecks {
    static final List<String> INVALID = List.of("0.999999999999999999", "1.000000000000000001",
            "100.000000000000000001", "-0.000000000000000001", "49.9", "99.5", "-1", "101",
            "4294967376", "1e30", "1e309", "null", "true", "\"80\"");
    static final List<String> VALID = List.of("0", "1", "1.0", "1e0", "49", "50", "80", "100.000");

    private IndependentProductScoreChecks() { }

    static void rejects(String number) throws Exception {
        try {
            parse(number, 50);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Invalid product score accepted: " + number);
    }

    static void accepts(String number) throws Exception {
        int expected = new java.math.BigDecimal(number).intValueExact();
        var raw = parse(number, 0);
        if (raw.scores().get("IP") != expected || !"explicit evidence".equals(raw.reasons().get("IP"))) {
            throw new AssertionError("Exact suitability or reason changed: " + number);
        }
        var thresholded = parse(number, 50);
        if (thresholded.scores().get("IP") != (expected >= 50 ? expected : 0)) {
            throw new AssertionError("Independent threshold contract changed: " + number);
        }
    }

    private static LlmService.ScoreParseResult parse(String number, int threshold) throws Exception {
        var node = new TaxonomyNode();
        node.setCode("IP");
        return new LlmResponseParser(JsonMapper.builder().build()).parseIndependentScoreParseResult(
                "{\"IP\":{\"score\":" + number + ",\"reason\":\"explicit evidence\"}}", List.of(node), threshold);
    }

    public static void main(String[] args) throws Exception {
        int failures = 0;
        for (String number : INVALID) {
            try { rejects(number); } catch (AssertionError failure) { failures++; System.err.println(failure.getMessage()); }
        }
        for (String number : VALID) {
            try { accepts(number); } catch (AssertionError failure) { failures++; System.err.println(failure.getMessage()); }
        }
        System.out.println("Product numeric cases: " + (INVALID.size() + VALID.size()) + ", failures: " + failures);
        if (failures != 0) throw new AssertionError("Product numeric boundary failures: " + failures);
    }
}
