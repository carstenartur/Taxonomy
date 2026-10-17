package com.taxonomy.analysis.relations;

import com.taxonomy.analysis.recovery.AnalysisCheckpointSession;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.dto.RelationSearchModel.*;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Replays the catalogue-quote failure seen with a local model through the real search. */
class SmallModelRelationRecoveryTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ORIGINAL = "arbeitszeiterfassungen";
    private static final Node FIRST = new Node("UA-1604", "UA", "Time recording", "Record working time", false);
    private static final Node SECOND = new Node("UA-1605", "UA", "Time sheet", "Enter contracted hours worked", false);

    @Test
    void correctsOnlyTheInvalidSiblingWithoutChargingItsSourceTwice() {
        List<String> sent = new ArrayList<>();
        var report = search(List.of(FIRST, SECOND), 2, 2, prompt -> {
            sent.add(prompt);
            var input = input(prompt);
            if (sent.size() == 1) return reply(List.of(selection(FIRST.id(), ORIGINAL),
                    selection(SECOND.id(), SECOND.description())));
            assertEquals(List.of(SECOND.id()), ids(input));
            assertTrue(input.has("validationErrors"), "a correction must explain the precise validation failure");
            return reply(List.of(selection(SECOND.id(), ORIGINAL)));
        });
        assertEquals(List.of(FIRST.id(), SECOND.id()), report.sources().stream().map(s -> s.node().id()).toList());
        assertEquals(2, sent.size());
        assertEquals(2, report.totalCalls(), "the corrective model call consumes the existing call budget");
        assertTrue(report.sources().stream().allMatch(s -> s.contributions().getFirst().quote().equals(ORIGINAL)));
    }

    @Test
    void interruptionDuringCorrectionRetainsAcceptedSourcesInSearchAndPreparation() {
        for (var reason : List.of(com.taxonomy.analysis.service.AnalysisStoppedException.Reason.CANCELLED,
                com.taxonomy.analysis.service.AnalysisStoppedException.Reason.TIME_LIMIT)) {
            for (boolean preparation : List.of(false, true)) {
                try (var journal = new com.taxonomy.analysis.recovery.RecoveryCheckpointPersistenceProbe.Journal();
                     var session = new AnalysisCheckpointSession(journal)) {
                    var calls = new java.util.concurrent.atomic.AtomicInteger();
                    Function<String, String> completion = prompt -> {
                        if (calls.incrementAndGet() == 1) return reply(List.of(selection(FIRST.id(), ORIGINAL),
                                selection(SECOND.id(), SECOND.description())));
                        throw new com.taxonomy.analysis.service.AnalysisStoppedException(reason);
                    };
                    if (preparation) {
                        var catalogue = new RequirementRelationSearch.InputCatalogue() {
                            public Node find(String id) { return FIRST.id().equals(id) ? FIRST : SECOND; }
                            public List<Node> roots() { return List.of(); }
                            public List<Node> children(Node node) { return List.of(); }
                        };
                        var plan = new RequirementRelationSearch(catalogue, new RelationCompatibilityMatrix(), completion, () -> {})
                                .prepare("interrupted", ORIGINAL, Map.of(FIRST.id(), 100d, SECOND.id(), 90d),
                                        List.of("persisted-source-result"),
                                        new RequirementRelationSearch.Options(new Limits(5, 8, 10, 128), 2), true);
                        assertEquals(List.of(FIRST.id()), plan.sources().stream().map(source -> source.node().id()).toList());
                        assertTrue(plan.stopReason().startsWith(reason.name() + ":"));
                    } else {
                        var report = search(List.of(FIRST, SECOND), 5, 2, completion);
                        assertEquals(List.of(FIRST.id()), report.sources().stream().map(source -> source.node().id()).toList());
                        assertTrue(report.stopReason().startsWith(reason.name() + ":"));
                    }
                    assertEquals(2, calls.get(), "a cooperative stop must still prevent further model calls");
                    assertTrue(journal.checkpoints().values().stream().anyMatch(q -> q.state().equals("DECOMPOSED")
                            && q.detail().getRawResponse().contains(FIRST.id())), "validated parent evidence remains durable");
                }
            }
        }
    }

    @Test
    void keepsAValidSiblingWhenTheCorrectionAlsoFails() {
        List<String> sent = new ArrayList<>();
        var report = search(List.of(FIRST, SECOND), 5, 2, prompt -> {
            sent.add(prompt);
            return sent.size() == 1 ? reply(List.of(selection(FIRST.id(), ORIGINAL),
                    selection(SECOND.id(), SECOND.description())))
                    : reply(List.of(selection(SECOND.id(), SECOND.description())));
        });
        assertEquals(List.of(FIRST.id()), report.sources().stream().map(s -> s.node().id()).toList());
        assertEquals(2, sent.size(), "one bounded correction, not an endless retry loop");
        assertTrue(report.warnings().stream().anyMatch(w -> w.contains(SECOND.id()) && w.contains("quote")));
    }

    @Test
    void budgetExhaustionStillRetainsTheValidSibling() {
        var report = search(List.of(FIRST, SECOND), 1, 2, prompt -> reply(List.of(
                selection(FIRST.id(), ORIGINAL), selection(SECOND.id(), SECOND.description()))));
        assertEquals(List.of(FIRST.id()), report.sources().stream().map(s -> s.node().id()).toList());
        assertEquals(1, report.totalCalls());
        assertTrue(report.warnings().stream().anyMatch(w -> w.contains("CALL_BUDGET")));
    }

    @Test
    void preservesOriginalRawEvidenceAndReplaysCorrectionsWithoutNewRequests() {
        Map<String, AnalysisCheckpointSession.Checkpoint> saved = new LinkedHashMap<>();
        var journal = new AnalysisCheckpointSession.Store() {
            public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question q) {
                return saved.getOrDefault(q.key(), new AnalysisCheckpointSession.Checkpoint(q, "ATTEMPT", null));
            }
            public void finish(AnalysisCheckpointSession.Question q, String state, LlmCallDetail detail) {
                saved.put(q.key(), new AnalysisCheckpointSession.Checkpoint(q, state, detail));
            }
            public void checkActive() { }
        };
        String raw = reply(List.of(selection(FIRST.id(), ORIGINAL), selection(SECOND.id(), SECOND.description())));
        List<String> sent = new ArrayList<>();
        try (var session = new AnalysisCheckpointSession(journal)) {
            var first = search(List.of(FIRST, SECOND), 5, 2, prompt -> {
                sent.add(prompt);
                return sent.size() == 1 ? raw : reply(List.of(selection(SECOND.id(), ORIGINAL)));
            });
            assertEquals(2, first.sources().size());
        }
        try (var session = new AnalysisCheckpointSession(journal)) {
            var resumed = search(List.of(FIRST, SECOND), 5, 2, prompt -> {
                fail("accepted decisions and their corrections must replay locally"); return null;
            });
            assertEquals(2, resumed.sources().size());
            assertEquals(0, resumed.totalCalls());
        }
        assertTrue(saved.values().stream().anyMatch(q -> "DECOMPOSED".equals(q.state())
                && raw.equals(q.detail().getRawResponse()) && q.detail().getError().contains("quote")));
        assertEquals(1, saved.values().stream().filter(q -> "SUCCESS".equals(q.state())).count());
    }

    @Test
    void neverAcceptsCompleteLookingJsonFromAnIncompleteCompletionDuringReplay() {
        String raw = reply(List.of(selection(FIRST.id(), ORIGINAL), selection(SECOND.id(), ORIGINAL)))
                .replace("Capture working time", "Unfinished candidate scope");
        assertIncompleteReplay(raw);
    }

    @Test
    void mixedSourceAndNavigationBatchesKeepSemanticClarificationsOpen() {
        for (boolean sourcePhase : List.of(true, false)) {
            Map<String, AnalysisCheckpointSession.Checkpoint> saved = new LinkedHashMap<>();
            var journal = new AnalysisCheckpointSession.Store() {
                public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question q) {
                    return saved.getOrDefault(q.key(), new AnalysisCheckpointSession.Checkpoint(q, "ATTEMPT", null));
                }
                public void finish(AnalysisCheckpointSession.Question q, String state, LlmCallDetail detail) {
                    saved.put(q.key(), new AnalysisCheckpointSession.Checkpoint(q, state, detail));
                }
                public void checkActive() { }
            };
            String question = "Which working time records are required?";
            var unresolved = sourcePhase ? new LinkedHashMap<String, Object>(Map.of("nodeId", FIRST.id(),
                    "outcome", "UNRESOLVED", "contributions", List.of(), "rationale", "The scope needs clarification", "question", question))
                    : new LinkedHashMap<>(navigation(FIRST.id(), ""));
            if (!sourcePhase) {
                unresolved.put("outcome", "UNRESOLVED"); unresolved.put("contribution", "");
                unresolved.put("necessity", null); unresolved.put("question", question);
            }
            String raw = sourcePhase ? reply(List.of(unresolved, selection(SECOND.id(), SECOND.description())))
                    : decisions(List.of(unresolved, navigation(SECOND.id(), SECOND.description())));
            var contribution = new Contribution(new Node("BP-source", "BP", "Process", "Time recording", false),
                    "Capture working time", ORIGINAL, "");
            var query = new Query(ORIGINAL, contribution, "PRODUCES", Direction.OUTGOING, Phase.NAVIGATE, List.of(FIRST, SECOND), null);
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            Function<RelationSearchProtocol, Object> assess = protocol -> sourcePhase
                    ? protocol.contributionsRecovering(ORIGINAL, List.of(FIRST, SECOND)) : protocol.evaluateRecovering(query);
            try (var session = new AnalysisCheckpointSession(journal)) {
                assess.apply(new RelationSearchProtocol(prompt -> {
                    if (calls.incrementAndGet() == 1) return raw;
                    assertEquals(List.of(SECOND.id()), idsForPhase(input(prompt), sourcePhase));
                    return sourcePhase ? reply(List.of(selection(SECOND.id(), ORIGINAL)))
                            : decisions(List.of(navigation(SECOND.id(), ORIGINAL)));
                }));
            }
            assertEquals(2, calls.get());
            var parent = saved.values().stream().filter(q -> raw.equals(q.detail().getRawResponse())).findFirst().orElseThrow();
            assertEquals("CLARIFICATION", parent.state(), "a semantic question must remain selectable despite a malformed sibling");
            assertTrue(parent.detail().getError().contains(question));
            try (var session = new AnalysisCheckpointSession(journal)) {
                assess.apply(new RelationSearchProtocol(prompt -> { fail("saved clarification and correction must replay"); return null; }));
            }
            saved.put(parent.question().key(), new AnalysisCheckpointSession.Checkpoint(parent.question(), "ATTEMPT", null));
            try (var session = new AnalysisCheckpointSession(journal)) {
                assess.apply(new RelationSearchProtocol(prompt -> {
                    calls.incrementAndGet();
                    assertEquals(2, idsForPhase(input(prompt), sourcePhase).size());
                    return sourcePhase ? reply(List.of(selection(FIRST.id(), ORIGINAL), selection(SECOND.id(), SECOND.description())))
                            : decisions(List.of(navigation(FIRST.id(), ORIGINAL), navigation(SECOND.id(), SECOND.description())));
                }));
            }
            assertEquals(3, calls.get(), "explicit clarification retry must reuse the already successful corrective child");
            assertTrue(saved.values().stream().noneMatch(q -> "CLARIFICATION".equals(q.state())));
        }
    }

    private static List<String> idsForPhase(JsonNode input, boolean sourcePhase) {
        List<String> result = new ArrayList<>();
        input.get(sourcePhase ? "nodes" : "candidates").forEach(node -> result.add(node.get("id").asString()));
        return result;
    }

    @Test
    void incompleteMalformedJsonReusesTheSameCorrectiveQuestionsDuringReplay() {
        assertIncompleteReplay("{\"selections\":[");
    }

    private static void assertIncompleteReplay(String raw) {
        Map<String, AnalysisCheckpointSession.Checkpoint> saved = new LinkedHashMap<>();
        var journal = new AnalysisCheckpointSession.Store() {
            public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question q) {
                return saved.getOrDefault(q.key(), new AnalysisCheckpointSession.Checkpoint(q, "ATTEMPT", null));
            }
            public void finish(AnalysisCheckpointSession.Question q, String state, LlmCallDetail detail) {
                saved.put(q.key(), new AnalysisCheckpointSession.Checkpoint(q, state, detail));
            }
            public void checkActive() { }
        };
        List<String> sent = new ArrayList<>();
        try (var session = new AnalysisCheckpointSession(journal)) {
            var first = search(List.of(FIRST, SECOND), 5, 2, prompt -> {
                sent.add(prompt);
                if (sent.size() == 1) throw new com.taxonomy.analysis.service.LlmIncompleteResponseException(raw, "length");
                return reply(ids(input(prompt)).stream().map(id -> selection(id, ORIGINAL)).toList());
            });
            assertEquals(2, first.sources().size());
            assertEquals(3, sent.size());
        }
        try (var session = new AnalysisCheckpointSession(journal)) {
            var resumed = search(List.of(FIRST, SECOND), 5, 2, prompt -> { fail("corrective question identity must survive replay"); return null; });
            assertEquals(2, resumed.sources().size());
            assertTrue(resumed.sources().stream().allMatch(s -> s.contributions().getFirst().text().equals("Capture working time")),
                    "raw text of an incomplete response can never become accepted evidence on replay");
            assertEquals(0, resumed.totalCalls());
        }
        assertTrue(saved.values().stream().anyMatch(q -> "DECOMPOSED".equals(q.state())
                && raw.equals(q.detail().getRawResponse()) && q.detail().getError().contains("length")));
    }

    @Test
    void batchesByTheFullPromptSizeAndKeepsTaxonomiesSeparate() {
        var large = new Node("UA-long", "UA", "Long description", "x".repeat(7_000), false);
        var anotherLarge = new Node("UA-long-2", "UA", "Another long description", "y".repeat(7_000), false);
        var other = new Node("BP-other", "BP", "Other taxonomy", "A process", false);
        List<String> sent = new ArrayList<>();
        var report = search(List.of(large, anotherLarge, FIRST, other), 10, 4, prompt -> {
            sent.add(prompt);
            var offered = input(prompt).get("nodes");
            Set<String> roots = new HashSet<>(); offered.forEach(n -> roots.add(n.get("root").asString()));
            assertEquals(1, roots.size(), "one coherent taxonomy per assessment");
            assertTrue(prompt.length() <= 16_000);
            return reply(ids(input(prompt)).stream().map(id -> selection(id, ORIGINAL)).toList());
        });
        assertEquals(4, report.sources().size());
        assertTrue(sent.size() >= 3);
    }

    @Test
    void interleavedTaxonomiesShareCoherentSourceRequestsAndKeepTheirRanking() {
        var process = new Node("BP-first", "BP", "Process", "Time recording process", false);
        var otherProcess = new Node("BP-second", "BP", "Other process", "Time sheet process", false);
        var ranked = List.of(FIRST, process, SECOND, otherProcess);
        List<String> sent = new ArrayList<>();
        var report = search(ranked, 2, 4, prompt -> {
            sent.add(prompt);
            var input = input(prompt);
            Set<String> roots = new HashSet<>(); input.get("nodes").forEach(node -> roots.add(node.get("root").asString()));
            assertEquals(1, roots.size());
            return reply(ids(input).stream().map(id -> selection(id, ORIGINAL)).toList());
        });
        assertEquals(ranked.stream().map(Node::id).toList(), report.sources().stream().map(source -> source.node().id()).toList(),
                "taxonomy packing must not change source priority or strand valid sources at the call limit");
        assertEquals(2, sent.size());
        assertFalse(report.warnings().stream().anyMatch(warning -> warning.contains("CALL_BUDGET")));
    }

    @Test
    void sourcePriorityRemainsStableAfterContinuingAnInterleavedCohort() {
        var process = new Node("BP-first", "BP", "Process", "Time recording process", false);
        var otherProcess = new Node("BP-second", "BP", "Other process", "Time sheet process", false);
        var ranked = List.of(FIRST, process, SECOND, otherProcess);
        Map<String, AnalysisCheckpointSession.Checkpoint> saved = new LinkedHashMap<>();
        var previous = new java.util.concurrent.atomic.AtomicReference<com.taxonomy.dto.RelationSearchReport>();
        var journal = new AnalysisCheckpointSession.Store() {
            public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question question) {
                var checkpoint = saved.get(question.key());
                return checkpoint == null || "READY".equals(checkpoint.state())
                        ? new AnalysisCheckpointSession.Checkpoint(question, "ATTEMPT", null) : checkpoint;
            }
            public void finish(AnalysisCheckpointSession.Question question, String state, LlmCallDetail detail) {
                saved.put(question.key(), new AnalysisCheckpointSession.Checkpoint(question, state, detail));
            }
            public void checkActive() { }
            public com.taxonomy.dto.RelationSearchReport previousRelations() { return previous.get(); }
        };
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        for (int pass = 0; pass < 2; pass++) {
            try (var session = new AnalysisCheckpointSession(journal)) {
                previous.set(search(ranked, 1, 4, prompt -> {
                    calls.incrementAndGet(); return reply(ids(input(prompt)).stream().map(id -> selection(id, ORIGINAL)).toList());
                }));
            }
        }
        assertEquals(2, calls.get(), "completed source groups must replay without another provider request");
        assertEquals(ranked.stream().map(Node::id).toList(), previous.get().sources().stream().map(source -> source.node().id()).toList());
    }

    @Test
    void cancelledPreparationKeepsTheRankingOfItsPartialSources() {
        var process = new Node("BP-first", "BP", "Process", "Time recording process", false);
        var otherProcess = new Node("BP-second", "BP", "Other process", "Time sheet process", false);
        var product = new Node("IP-last", "IP", "Records", "Working time records", false);
        var ranked = List.of(FIRST, process, SECOND, otherProcess, product);
        Map<String, Node> byId = new LinkedHashMap<>();
        Map<String, Double> scores = new LinkedHashMap<>();
        for (int i = 0; i < ranked.size(); i++) {
            byId.put(ranked.get(i).id(), ranked.get(i)); scores.put(ranked.get(i).id(), 100d - i);
        }
        var catalogue = new RequirementRelationSearch.InputCatalogue() {
            public Node find(String id) { return byId.get(id); }
            public List<Node> roots() { return List.of(); }
            public List<Node> children(Node node) { return List.of(); }
        };
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var search = new RequirementRelationSearch(catalogue, new RelationCompatibilityMatrix(), prompt -> {
            calls.incrementAndGet();
            return reply(ids(input(prompt)).stream().map(id -> selection(id, ORIGINAL)).toList());
        }, () -> {
            if (calls.get() == 2) throw new java.util.concurrent.CancellationException("Analysis cancelled");
        });
        var plan = search.prepare("cancelled", ORIGINAL, scores, List.of("persisted-source-result"),
                new RequirementRelationSearch.Options(new Limits(5, 8, 10, 128), 5), true);
        assertEquals(2, calls.get());
        assertEquals(ranked.subList(0, 4).stream().map(Node::id).toList(),
                plan.sources().stream().map(source -> source.node().id()).toList());
        assertFalse(plan.stopReason().isBlank());
        assertTrue(plan.items().isEmpty());
    }

    @Test
    void anOversizedSingleDescriptionStaysUnassessedWithoutTruncationOrRemoteCall() {
        var large = new Node("UA-too-long", "UA", "Long description", "x".repeat(18_000), false);
        var report = search(List.of(large), 5, 1, prompt -> { fail("oversized evidence must not be sent or silently shortened"); return null; });
        assertTrue(report.sources().isEmpty());
        assertEquals(0, report.totalCalls());
        assertTrue(report.warnings().stream().anyMatch(w -> w.contains("PROMPT_BUDGET") && w.contains(large.id())));
    }

    @Test
    void multilingualDescriptionsArePackedWithinTheUtf8Budget() {
        var first = new Node("UA-multilingual-1", "UA", "First", "界".repeat(3_000), false);
        var second = new Node("UA-multilingual-2", "UA", "Second", "界".repeat(3_000), false);
        List<String> sent = new ArrayList<>();
        var report = search(List.of(first, second), 5, 2, prompt -> {
            sent.add(prompt);
            assertTrue(RelationSearchProtocol.completionOptions(prompt, 1).budgetText(prompt)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 16_000);
            return reply(ids(input(prompt)).stream().map(id -> selection(id, ORIGINAL)).toList());
        });
        assertEquals(2, report.sources().size());
        assertEquals(2, sent.size());
    }

    @Test
    void anOversizedCorrectionRetainsAnOpenDiagnosticWithoutAnotherModelCall() {
        Map<String, AnalysisCheckpointSession.Checkpoint> saved = new LinkedHashMap<>();
        var journal = new AnalysisCheckpointSession.Store() {
            public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question q) {
                return saved.getOrDefault(q.key(), new AnalysisCheckpointSession.Checkpoint(q, "ATTEMPT", null));
            }
            public void finish(AnalysisCheckpointSession.Question q, String state, LlmCallDetail detail) {
                saved.put(q.key(), new AnalysisCheckpointSession.Checkpoint(q, state, detail));
            }
            public void checkActive() { }
        };
        int limit = RelationSearchProtocol.requestCharacters(RelationSearchProtocol.sourcePrompt(ORIGINAL, List.of(FIRST), Map.of()));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        try (var session = new AnalysisCheckpointSession(journal)) {
            var protocol = new RelationSearchProtocol((step, nodes, prompt) -> {
                calls.incrementAndGet(); return reply(List.of(selection(FIRST.id(), FIRST.description())));
            }, "fixture", (step, nodes) -> {}, limit);
            var result = protocol.contributionsRecovering(ORIGINAL, List.of(FIRST));
            assertTrue(result.accepted().isEmpty());
            assertTrue(result.failures().get(FIRST.id()).contains("PROMPT_BUDGET"));
        }
        assertEquals(1, calls.get());
        assertTrue(saved.values().stream().anyMatch(q -> "UNASSESSED".equals(q.state())
                && q.detail().getError().contains("PROMPT_BUDGET")), "a preflight failure still needs an actionable open assessment");
    }

    @Test
    void verificationAcceptsACompactVerdictAndCopiesOnlyTheImmutableClaim() {
        var contribution = new Contribution(FIRST, "Capture working time", ORIGINAL, "");
        var claim = new Decision(SECOND.id(), Outcome.MATCH, "Working time data", ORIGINAL, Necessity.REQUIRED, "", "", "Required record", "");
        var query = new Query(ORIGINAL, contribution, "PRODUCES", Direction.OUTGOING, Phase.VERIFY, List.of(SECOND), claim);
        List<String> sent = new ArrayList<>();
        var actual = new RelationSearchProtocol(prompt -> {
            sent.add(prompt);
            return JSON.writeValueAsString(Map.of("decisions", List.of(Map.of("targetId", SECOND.id(),
                    "outcome", "VERIFIED", "rationale", "Both endpoints and the requirement support the claim.", "question", ""))));
        }).evaluate(query);
        assertEquals(1, actual.size());
        assertEquals(Outcome.VERIFIED, actual.getFirst().outcome());
        assertEquals(claim.quote(), actual.getFirst().quote());
        assertEquals(claim.contribution(), actual.getFirst().contribution());
        assertFalse(sent.getFirst().contains("DESCEND inspects"), "verification has its own focused instructions");
    }

    @Test
    void correctsOnlyTheInvalidNavigationDecision() {
        var contribution = new Contribution(FIRST, "Capture working time", ORIGINAL, "");
        var third = new Node("IP-third", "IP", "Time data", "Working hours", false);
        var fourth = new Node("IP-fourth", "IP", "More data", "Other hours", false);
        var query = new Query(ORIGINAL, contribution, "PRODUCES", Direction.OUTGOING, Phase.NAVIGATE, List.of(third, fourth), null);
        List<String> sent = new ArrayList<>();
        var protocol = new RelationSearchProtocol(prompt -> {
            sent.add(prompt);
            if (sent.size() == 1) return decisions(List.of(navigation(third.id(), ORIGINAL), navigation(fourth.id(), fourth.description())));
            assertEquals(fourth.id(), input(prompt).get("candidates").get(0).get("id").asString());
            assertEquals(1, input(prompt).get("candidates").size());
            return decisions(List.of(navigation(fourth.id(), ORIGINAL)));
        });
        var actual = protocol.evaluateRecovering(query);
        assertEquals(2, actual.size());
        assertTrue(actual.stream().allMatch(d -> d.outcome() == Outcome.MATCH));
        assertEquals(2, sent.size());
    }

    private static Map<String, Object> navigation(String id, String quote) {
        return Map.of("targetId", id, "outcome", "MATCH", "contribution", "Working time data", "requirementQuote", quote,
                "necessity", "REQUIRED", "condition", "", "alternativeGroup", "", "rationale", "Data needed by the requested function", "question", "");
    }
    private static String decisions(List<?> decisions) { return JSON.writeValueAsString(Map.of("decisions", decisions)); }

    private static com.taxonomy.dto.RelationSearchReport search(List<Node> nodes, int calls, int sources,
                                                               Function<String, String> completion) {
        Map<String, Node> byId = new LinkedHashMap<>();
        Map<String, Double> scores = new LinkedHashMap<>();
        for (int i = 0; i < nodes.size(); i++) { byId.put(nodes.get(i).id(), nodes.get(i)); scores.put(nodes.get(i).id(), 100d - i); }
        var catalogue = new RequirementRelationSearch.InputCatalogue() {
            public Node find(String id) { return byId.get(id); }
            public List<Node> roots() { return List.of(); }
            public List<Node> children(Node node) { return List.of(); }
        };
        return new RequirementRelationSearch(catalogue, new RelationCompatibilityMatrix(), completion, () -> {})
                .search(ORIGINAL, scores, new RequirementRelationSearch.Options(new Limits(calls, 8, 10, 128), sources));
    }
    private static Map<String, Object> selection(String id, String quote) {
        return Map.of("nodeId", id, "outcome", "EXPLICIT", "contributions", List.of(Map.of(
                "text", "Capture working time", "quote", quote, "condition", "")),
                "rationale", "The requested function is time recording.", "question", "");
    }
    private static String reply(List<?> selections) { return JSON.writeValueAsString(Map.of("selections", selections)); }
    private static JsonNode input(String prompt) { return JSON.readTree(prompt.substring(prompt.indexOf("INPUT\n") + 6)); }
    private static List<String> ids(JsonNode input) {
        List<String> ids = new ArrayList<>(); input.get("nodes").forEach(n -> ids.add(n.get("id").asString())); return ids;
    }
}
