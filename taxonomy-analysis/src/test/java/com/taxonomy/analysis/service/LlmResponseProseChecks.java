package com.taxonomy.analysis.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/** Executable parser regressions, also run by the ordinary JUnit suite. */
public final class LlmResponseProseChecks {
    private static final String REASON = "Keep [brackets], {braces}, and ``` literally.";
    private static final String SCORE_OBJECT =
            "{\"IP\":{\"score\":100,\"reason\":\"" + REASON + "\"}}";
    private static final LlmResponseParser PARSER = new LlmResponseParser(new ObjectMapper());

    private LlmResponseProseChecks() { }

    public static void main(String[] args) throws Exception {
        for (String name : args) {
            switch (name) {
                case "prose" -> skipsBracketedProseBeforeScoreObjects();
                case "arrays" -> preservesArrayRootsAfterBracketedProse();
                case "truncated" -> truncatedContainersAreNeverSalvaged();
                case "inner" -> bracketedExamplesCannotSupplyInnerScores();
                case "limits" -> configuredReadLimitsAreNotTreatedAsProse();
                default -> throw new IllegalArgumentException(name);
            }
            System.out.println("PASS " + name);
        }
    }

    static void skipsBracketedProseBeforeScoreObjects() throws Exception {
        for (String prefix : List.of(
                "See [IP] and then ",
                "See [IP] and [CO] and then\n",
                "[the candidates] ",
                "[notes [IP]] ",
                "See [IP](#information-products) and then ",
                "[note with {braces}]\n```json\n",
                "[note with \"quoted ] bracket\"] ")) {
            String response = prefix + SCORE_OBJECT + "\n```\nDone.";
            require(SCORE_OBJECT.equals(PARSER.extractJson(response)),
                    "Bracketed prose must not hide the following score object: " + prefix);
            for (var parsed : List.of(
                    PARSER.parseScoreParseResult(response, nodes(), 100),
                    PARSER.parseIndependentScoreParseResult(response, nodes(), 50))) {
                require(Map.of("IP", 100).equals(parsed.scores()), "Score must survive prose extraction");
                require(Map.of("IP", REASON).equals(parsed.reasons()), "Reason must remain unchanged");
            }
        }
    }

    static void preservesArrayRootsAfterBracketedProse() {
        for (String prefix : List.of("", "Here is the result:\n```json\n", "See [IP] and then ")) {
            for (String array : List.of("[]", "[\"IP\"]", "[1]", "[true]", "[null]",
                    "[" + SCORE_OBJECT + "]", "[[" + SCORE_OBJECT + "]]",
                    "[" + SCORE_OBJECT + "," + SCORE_OBJECT + "]")) {
                // A later valid object must not replace an earlier real JSON array.
                String response = prefix + array + "\n" + SCORE_OBJECT;
                require(array.equals(PARSER.extractJson(response)), "Outer array must be preserved: " + prefix + array);
                rejectScores(response);
            }
        }
    }

    static void truncatedContainersAreNeverSalvaged() {
        for (String prefix : List.of("", "See [IP] and then ")) {
            for (String incomplete : List.of("[" + SCORE_OBJECT,
                    "{\"IP\":" + SCORE_OBJECT,
                    "[not a complete note " + SCORE_OBJECT)) {
                require(incomplete.equals(PARSER.extractJson(prefix + incomplete)),
                        "Incomplete outer container must remain intact");
                rejectScores(prefix + incomplete);
            }
        }
    }

    static void bracketedExamplesCannotSupplyInnerScores() {
        rejectScores("[Example: " + SCORE_OBJECT + "]");
        rejectScores("See [Example: " + SCORE_OBJECT + "] but no actual response.");
    }

    static void configuredReadLimitsAreNotTreatedAsProse() {
        var factory = tools.jackson.core.json.JsonFactory.builder()
                .streamReadConstraints(tools.jackson.core.StreamReadConstraints.builder()
                        .maxNestingDepth(2).build()).build();
        var constrained = new LlmResponseParser(new ObjectMapper(factory));
        try {
            constrained.extractJson("[[[0]]] " + SCORE_OBJECT);
        } catch (tools.jackson.core.exc.StreamConstraintsException expected) {
            return;
        }
        throw new AssertionError("Read limits must propagate, not skip a real outer array");
    }

    private static void rejectScores(String response) {
        expectFailure(() -> PARSER.parseScoreParseResult(response, nodes(), 100));
        expectFailure(() -> PARSER.parseIndependentScoreParseResult(response, nodes(), 50));
    }

    private static void expectFailure(CheckedCall call) {
        try { call.run(); }
        catch (IllegalArgumentException | tools.jackson.core.JacksonException expected) { return; }
        catch (Exception unexpected) { throw new AssertionError("Unexpected parser failure", unexpected); }
        throw new AssertionError("Non-object or incomplete outer response became successful scores");
    }

    private static List<TaxonomyNode> nodes() {
        var node = new TaxonomyNode();
        node.setCode("IP");
        return List.of(node);
    }

    @FunctionalInterface
    private interface CheckedCall { void run() throws Exception; }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
