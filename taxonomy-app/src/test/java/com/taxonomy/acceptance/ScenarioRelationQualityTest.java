package com.taxonomy.acceptance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;

import static com.taxonomy.acceptance.DirectedRelationMetrics.*;
import static org.junit.jupiter.api.Assertions.*;

class ScenarioRelationQualityTest {
    // An independently written projection, deliberately not read from the oracle or playback corpus.
    private static final List<String> CORE = List.of(
            "UA-1580:CONSUMES:IP-1116", "UA-1580:CONSUMES:IP-2130", "UA-1580:SUPPORTS:BP-1535",
            "BP-1017:CONSUMES:IP-2130", "BP-1535:CONSUMES:IP-2130", "BP-1535:USES:CR-1098",
            "BP-1535:USES:CR-1097", "CR-1060:SUPPORTS:BP-1535", "CO-1048:SUPPORTS:BP-1535",
            "CO-1066:SUPPORTS:BP-1535", "CR-1037:SUPPORTS:BP-1535", "BP-1521:USES:CR-1022",
            "BP-1530:USES:CR-1022", "CR-1007:SUPPORTS:BP-1140", "CR-1006:SUPPORTS:BP-1140",
            "CR-1007:SUPPORTS:BP-1536", "BP-1535:USES:CR-1198", "UA-1522:USES:CI-1060",
            "CI-1060:SUPPORTS:BP-2009");
    private static final String ORIGINAL_SHA256 = "a5546798b6117e9d66cc3c63ce316ae9985b980aa949df7669d3c28fb5d9d989";
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();

    @Test void writesExplicitlyAuthoredReportsFromTheSavedSnapshot() throws Exception {
        inputs();
        var result = ScenarioRelationQuality.write(directory);
        assertEquals(Verdict.PASS, result.verdict());
        var report = json.readTree(Files.readString(directory.resolve("relation-quality.json")));
        assertEquals("AUTHORED_PLAYBACK_NOT_LIVE_MODEL", report.path("evidenceKind").stringValue());
        assertEquals(19, report.at("/evaluation/matchedRequired").asInt());
        assertEquals("flood-functional-architecture-v4", report.path("referenceVersion").stringValue());
        assertTrue(report.path("referenceSha256").stringValue().matches("[0-9a-f]{64}"));
        assertEquals(20, report.path("coverageRequiredCount").asInt());
        assertEquals("SEPARATE_SNAPSHOT_AND_EXPORT_GATE", report.path("coverageAssessment").stringValue());
        assertEquals("a".repeat(64), report.path("taxonomyFingerprint").stringValue());
        assertEquals("b".repeat(64), report.path("promptFingerprint").stringValue());
        assertEquals(136, report.path("relationCalls").asInt());
        assertEquals(256, report.path("relationCallBudget").asInt());
        assertTrue(Files.readString(directory.resolve("relation-quality.html")).contains("Not a live-model benchmark"));
        assertTrue(Files.readString(directory.resolve("relation-quality.csv")).contains("PASS"));
        byte[] before = Files.readAllBytes(directory.resolve("relation-quality.json"));
        ScenarioRelationQuality.write(directory);
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("relation-quality.json")));
    }

    @Test void unfinishedSearchCannotPassDespiteSuccessfulTopLevelStatus() throws Exception {
        var snapshot = inputs();
        ((ObjectNode) snapshot.at("/analysis/relationSearchReport/result")).putArray("unfinished").addObject();
        saveSnapshot(snapshot);
        var result = ScenarioRelationQuality.write(directory);
        assertEquals(RunState.PARTIAL, result.state());
        assertEquals(Verdict.INCONCLUSIVE, result.verdict());
        assertNull(result.recall());
        assertThrows(AssertionError.class, () -> ScenarioRelationQuality.verify(directory));
    }

    @Test void deletingThePhoneBranchCannotPassOnTheOtherTwoChannels() throws Exception {
        var snapshot = inputs();
        removeRelation(snapshot, "CO-1066:SUPPORTS:BP-1535");
        saveSnapshot(snapshot);
        var result = ScenarioRelationQuality.write(directory);
        assertEquals(Verdict.FAIL, result.verdict());
        assertEquals(List.of(new Relation("CO-1066", "SUPPORTS", "BP-1535")), result.notObservedRequired());
        assertTrue(result.falsePositives().isEmpty());
    }

    @Test void reversingAReadRelationIsAFalsePositiveAndLosesTheRequiredRead() throws Exception {
        var snapshot = inputs();
        var read = (ObjectNode) snapshot.at("/analysis/architectureView/includedRelationships/0");
        read.put("sourceCode", "IP-1116").put("targetCode", "UA-1580");
        saveSnapshot(snapshot);
        var result = ScenarioRelationQuality.write(directory);
        assertEquals(Verdict.FAIL, result.verdict());
        assertEquals(List.of(new Relation("IP-1116", "CONSUMES", "UA-1580")), result.falsePositives());
        assertEquals(List.of(new Relation("UA-1580", "CONSUMES", "IP-1116")), result.notObservedRequired());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CI-1052:FULFILLS:CP-1041", "CI-1052:PRODUCES:IP-1116",
            "BP-1017:CONSUMES:IP-1116", "UA-1580:USES:CR-1097"})
    void oldUnsupportedRequiredClaimsFailEvenWhenTheNewCoreIsComplete(String signature) throws Exception {
        var snapshot = inputs();
        addRelation((ArrayNode) snapshot.at("/analysis/architectureView/includedRelationships"), signature);
        saveSnapshot(snapshot);
        var result = ScenarioRelationQuality.write(directory);
        String[] parts = signature.split(":");
        assertEquals(Verdict.FAIL, result.verdict());
        assertEquals(19, result.matchedRequired());
        assertEquals(List.of(new Relation(parts[0], parts[1], parts[2])), result.falsePositives());
        assertTrue(result.notObservedRequired().isEmpty());
        var report = json.readTree(Files.readString(directory.resolve("relation-quality.json")));
        assertEquals(1, report.path("rejectedRequiredClaimsObserved").size());
    }

    @Test void aSeparatelyPermittedScopedPictureProducerDoesNotLowerPrecision() throws Exception {
        var snapshot = inputs();
        addRelation((ArrayNode) snapshot.at("/analysis/architectureView/includedRelationships"),
                "CI-1096:PRODUCES:IP-1116");
        saveSnapshot(snapshot);
        var result = ScenarioRelationQuality.write(directory);
        assertEquals(Verdict.PASS, result.verdict());
        assertEquals(19, result.matchedRequired());
        assertEquals(1, result.matchedAllowed());
        assertEquals(1.0, result.precision());
    }

    @Test void sourceLimitCannotHideBehindAnEmptyUnfinishedArrayOrSuccessStatus() throws Exception {
        var snapshot = inputs();
        var progress = (ObjectNode) snapshot.at("/analysis/relationSearchReport/progress");
        progress.put("totalSources", progress.path("assessedSources").asInt() + 42);
        assertInconclusive(snapshot);
        var report = json.readTree(Files.readString(directory.resolve("relation-quality.json")));
        assertEquals(42, report.path("unassessedSourceCount").asInt());
        assertEquals(0, report.path("unfinishedSearchCount").asInt());
    }

    @Test void missingRetainedSourcesCannotBeOverruledByCompleteProgressCounters() throws Exception {
        var snapshot = inputs();
        ((ArrayNode) snapshot.at("/analysis/relationSearchReport/sources")).remove(0);
        assertInconclusive(snapshot);
    }

    @Test void pendingSearchesCannotHideBehindAnEmptyUnfinishedArray() throws Exception {
        var snapshot = inputs();
        var progress = (ObjectNode) snapshot.at("/analysis/relationSearchReport/progress");
        progress.put("completedSearches", 37).put("pendingSearches", 1);
        assertInconclusive(snapshot);
        var report = json.readTree(Files.readString(directory.resolve("relation-quality.json")));
        assertEquals(1, report.path("pendingSearchCount").asInt());
    }

    @Test void explicitNonExhaustionPreventsCompletePass() throws Exception {
        var snapshot = inputs();
        ((ObjectNode) snapshot.at("/analysis/relationSearchReport")).put("searchExhausted", false);
        assertInconclusive(snapshot);
    }

    @Test void missingSourceCompletenessEvidenceCannotCertifyQuality() throws Exception {
        var snapshot = inputs();
        ((ObjectNode) snapshot.at("/analysis/relationSearchReport")).remove("progress");
        saveSnapshot(snapshot);
        assertThrows(IllegalArgumentException.class, () -> ScenarioRelationQuality.write(directory));
    }

    @Test void anotherRequirementCannotReuseThisTopologyAsQualityEvidence() throws Exception {
        var snapshot = inputs();
        ((ObjectNode) snapshot.at("/analysis/relationSearchReport")).put("originalSha256", "f".repeat(64));
        saveSnapshot(snapshot);
        assertThrows(IllegalArgumentException.class, () -> ScenarioRelationQuality.write(directory));
    }

    @Test void independentReferenceCarriesAllBoundedConstraintsAndVerbatimQuotes() throws Exception {
        try (var input = getClass().getResourceAsStream("/scenarios/flood-quality-reference.json")) {
            assertNotNull(input);
            var reference = json.readTree(input.readAllBytes());
            assertEquals(19, reference.path("requiredRelations").size());
            assertTrue(reference.path("allowedRelations").size() >= 17);
            assertEquals(13, reference.path("rejectedRequiredClaims").size()); // Original seven plus six exact F5 bindings.
            assertEquals(20, reference.path("coverageConstraints").size());
            var inputBindings = new TreeSet<String>();
            for (var entry : reference.path("openIdentityQuestions")) {
                inputBindings.add(entry.path("sourceId").asString() + ":" + entry.path("type").asString()
                        + ":" + entry.path("targetId").asString());
                assertEquals("F5_OBSERVATION_INPUT", entry.path("questionGroup").asString());
                assertEquals(INPUT_QUESTION, entry.path("question").asString());
            }
            assertEquals(new TreeSet<>(OBSERVATION_INPUT_BINDINGS), inputBindings);
            String original = reference.at("/requirement/text").stringValue();
            for (String field : List.of("requiredRelations", "allowedRelations", "rejectedRequiredClaims", "coverageConstraints", "openIdentityQuestions")) {
                for (var entry : reference.path(field)) {
                    assertFalse(entry.path("quotes").isEmpty(), entry.path("id").asString());
                    for (var quote : entry.path("quotes")) assertTrue(original.contains(quote.stringValue()));
                }
            }
            for (var constraint : reference.path("coverageConstraints")) {
                assertFalse(constraint.at("/binding/elementIds").isEmpty());
                assertFalse(constraint.path("requiredBehavior").stringValue().isBlank());
            }
            assertTrue(reference.at("/requirement/clauses/A1").stringValue().contains("test design assumption"));
        }
    }

    @Test void missingStatusIsRejectedAndRemovesEarlierSuccessReports() throws Exception {
        var snapshot = inputs();
        ScenarioRelationQuality.write(directory);
        ((ObjectNode) snapshot.path("analysis")).remove("status");
        saveSnapshot(snapshot);
        assertThrows(IllegalArgumentException.class, () -> ScenarioRelationQuality.write(directory));
        assertFalse(Files.exists(directory.resolve("relation-quality.json")));
        assertFalse(Files.exists(directory.resolve("relation-quality.html")));
        assertFalse(Files.exists(directory.resolve("relation-quality.csv")));
    }

    @Test void missingCompletenessEvidenceIsNotInterpretedAsAnEmptySearch() throws Exception {
        var snapshot = inputs();
        ((ObjectNode) snapshot.at("/analysis/relationSearchReport/result")).remove("unfinished");
        saveSnapshot(snapshot);
        assertThrows(IllegalArgumentException.class, () -> ScenarioRelationQuality.write(directory));
    }

    @Test void foreignSnapshotAndUnversionedCaseAreRejected() throws Exception {
        var snapshot = inputs();
        ((ObjectNode) snapshot.path("summary")).put("id", "another-snapshot");
        saveSnapshot(snapshot);
        assertThrows(IllegalArgumentException.class, () -> ScenarioRelationQuality.write(directory));
        inputs();
        var run = (ObjectNode) json.readTree(Files.readString(directory.resolve("run.json")));
        run.put("scenario", "another-case");
        Files.writeString(directory.resolve("run.json"), run.toString());
        assertThrows(IllegalArgumentException.class, () -> ScenarioRelationQuality.write(directory));
    }

    @Test void referenceErrorsAreWrittenBeforeVerificationFails() throws Exception {
        var snapshot = inputs();
        ((ObjectNode) snapshot.at("/analysis/architectureView/includedRelationships/0")).put("relationType", "PRODUCES");
        saveSnapshot(snapshot);
        assertThrows(AssertionError.class, () -> ScenarioRelationQuality.verify(directory));
        var report = json.readTree(Files.readString(directory.resolve("relation-quality.json")));
        assertEquals("FAIL", report.at("/evaluation/verdict").stringValue());
        assertEquals(1, report.at("/evaluation/falsePositives").size());
        assertEquals(1, report.at("/evaluation/notObservedRequired").size());
    }

    @Test void globalWarningsAreUnreviewedEvidenceNotWrongRelations() throws Exception {
        var snapshot = inputs();
        ((ObjectNode) snapshot.path("analysis")).putArray("warnings").add("source classification incomplete");
        assertInconclusive(snapshot);
        var report = json.readTree(Files.readString(directory.resolve("relation-quality.json")));
        assertEquals(1, report.path("analysisWarningCount").asInt());
        assertEquals(0, report.at("/evaluation/falsePositives").size());
        assertEquals(19, report.at("/evaluation/matchedRequired").asInt());
        assertFalse(Files.readString(directory.resolve("relation-quality.html"))
                .contains("source classification incomplete"));
    }

    @Test void relationWarningsPreventCompletePass() throws Exception {
        var snapshot = inputs();
        ((ObjectNode) snapshot.at("/analysis/relationSearchReport")).putArray("warnings").add("budget exhausted");
        assertInconclusive(snapshot);
    }

    @Test void stopReasonPreventsCompletePassEvenWithoutUnfinishedEntries() throws Exception {
        var snapshot = inputs();
        ((ObjectNode) snapshot.at("/analysis/relationSearchReport")).put("stopReason", "CALL_BUDGET");
        assertInconclusive(snapshot);
    }

    @Test void missingOrMalformedGlobalWarningsCannotCertifyCompleteness() throws Exception {
        var snapshot = inputs();
        ((ObjectNode) snapshot.path("analysis")).remove("warnings");
        saveSnapshot(snapshot);
        assertThrows(IllegalArgumentException.class, () -> ScenarioRelationQuality.write(directory));
        ((ObjectNode) snapshot.path("analysis")).put("warnings", "not-an-array");
        saveSnapshot(snapshot);
        assertThrows(IllegalArgumentException.class, () -> ScenarioRelationQuality.write(directory));
    }

    @Test void failedAndCancelledStatesAreNotOverwrittenByWarnings() throws Exception {
        for (String state : List.of("FAILED", "CANCELLED", "PARTIAL")) {
            var snapshot = inputs();
            ((ObjectNode) snapshot.path("summary")).put("status", state);
            ((ObjectNode) snapshot.path("analysis")).put("status", state).putArray("warnings").add("incomplete");
            saveSnapshot(snapshot);
            var result = ScenarioRelationQuality.write(directory);
            assertEquals(RunState.valueOf(state), result.state());
            assertEquals(Verdict.INCONCLUSIVE, result.verdict());
            assertNull(result.precision());
            assertNull(result.recall());
        }
    }

    @Test void optionalCapabilityViewCannotBePromotedIntoTheRequiredProjection() throws Exception {
        var snapshot = inputs();
        addRelation((ArrayNode) snapshot.at("/analysis/architectureView/includedRelationships"),
                "CP-1041:REALIZES:CR-1037");
        saveSnapshot(snapshot);
        assertEquals(Verdict.FAIL, ScenarioRelationQuality.write(directory).verdict());
    }

    @ParameterizedTest @ValueSource(strings = {"OUTGOING", "INCOMING"})
    void explicitlyScopedOptionalProviderIsRetainedWithoutBecomingRequired(String direction) throws Exception {
        var snapshot = inputs();
        optionalProvider(snapshot, direction, "OPTIONAL", "Only if the existing hydrographic read-access provider is selected; no new survey, forecast or warning authority.");
        saveSnapshot(snapshot);
        assertEquals(Verdict.PASS, ScenarioRelationQuality.write(directory).verdict());
        var report = json.readTree(Files.readString(directory.resolve("relation-quality.json")));
        assertEquals(1, report.path("validatedOptionalContextCount").asInt());
        assertEquals(19, report.at("/evaluation/predictedCount").asInt());
        assertTrue(report.path("rejectedRequiredClaimsObserved").isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"REQUIRED", "ALTERNATIVE"})
    void optionalProviderDoesNotPermitAnotherNecessityEvenIfProjectionDropsIt(String necessity) throws Exception {
        var snapshot = inputs();
        optionalProvider(snapshot, "OUTGOING", necessity, "Only if the read provider is selected.");
        saveSnapshot(snapshot);
        assertThrows(IllegalArgumentException.class, () -> ScenarioRelationQuality.write(directory));
    }

    @Test void optionalProviderNeedsAnExplicitCondition() throws Exception {
        var snapshot = inputs();
        optionalProvider(snapshot, "OUTGOING", "OPTIONAL", "");
        saveSnapshot(snapshot);
        assertThrows(IllegalArgumentException.class, () -> ScenarioRelationQuality.write(directory));
    }

    @Test void originalRequiredHydrographyBanSurvivesOptionalProviderPermission() throws Exception {
        var snapshot = inputs();
        addRelation((ArrayNode) snapshot.at("/analysis/architectureView/includedRelationships"),
                "UA-1580:USES:CI-1052");
        saveSnapshot(snapshot);
        assertEquals(Verdict.FAIL, ScenarioRelationQuality.write(directory).verdict());
        var report = json.readTree(Files.readString(directory.resolve("relation-quality.json")));
        assertEquals(1, report.path("rejectedRequiredClaimsObserved").size());
    }

    @Test void exactNoncoreInputIdentityQuestionCanPassWithoutClaimingSearchCompletion() throws Exception {
        var snapshot = inputs();
        identityQuestion(snapshot, false);
        saveSnapshot(snapshot);
        var result = ScenarioRelationQuality.write(directory);
        assertEquals(Verdict.PASS, result.verdict());
        assertEquals(RunState.PARTIAL, result.state());
        assertNull(result.precision());
        assertNull(result.recall());
        var report = json.readTree(Files.readString(directory.resolve("relation-quality.json")));
        assertEquals("PASS_WITH_OPEN_POINTS", report.path("outcome").asString());
        assertTrue(report.path("functionalAcceptanceComplete").asBoolean());
        assertFalse(report.path("sourceAndSearchEvidenceComplete").asBoolean());
        assertEquals(1, report.path("openPoints").size());
        assertEquals("X03", report.at("/openPoints/0/id").asString());
        assertEquals(1, report.at("/openPoints/0/evidenceCount").asInt());
        assertDoesNotThrow(() -> ScenarioRelationQuality.verify(directory));
    }

    @Test void bothOrientationsRetainOneCanonicalInputQuestion() throws Exception {
        var snapshot = inputs(); identityQuestion(snapshot, true); saveSnapshot(snapshot);
        assertEquals(Verdict.PASS, ScenarioRelationQuality.write(directory).verdict());
        var report = json.readTree(Files.readString(directory.resolve("relation-quality.json")));
        assertEquals(1, report.path("openPoints").size());
        assertEquals(2, report.at("/openPoints/0/evidenceCount").asInt());
    }

    // Independently reviewed endpoint identities, not populated from playback or oracle output.
    private static final List<String> OBSERVATION_INPUT_BINDINGS = List.of(
            "BP-1017:CONSUMES:IP-1116", "BP-1529:CONSUMES:IP-1116", "BP-1534:CONSUMES:IP-1116",
            "BP-1866:CONSUMES:IP-1116", "CI-1096:CONSUMES:IP-1116",
            "CP-1020:REQUIRES:IP-1116", "CP-1097:REQUIRES:IP-1116");

    @ParameterizedTest
    @ValueSource(strings = {"BP-1529:CONSUMES:IP-1116", "BP-1534:CONSUMES:IP-1116", "BP-1866:CONSUMES:IP-1116",
            "CI-1096:CONSUMES:IP-1116", "CP-1020:REQUIRES:IP-1116", "CP-1097:REQUIRES:IP-1116"})
    void reviewedInputBindingRetainsBothOrientationsOfTheSameQuestion(String signature) throws Exception {
        var snapshot = inputs(); inputQuestionBindings(snapshot, List.of(signature), true); saveSnapshot(snapshot);
        var result = ScenarioRelationQuality.write(directory);
        assertEquals(Verdict.PASS, result.verdict());
        assertEquals(RunState.PARTIAL, result.state());
        var report = json.readTree(Files.readString(directory.resolve("relation-quality.json")));
        assertEquals(1, report.path("openQuestionGroupCount").asInt());
        assertEquals(1, report.path("openRelationBindingCount").asInt());
        assertEquals("F5_OBSERVATION_INPUT", report.at("/openPoints/0/questionGroup").asString());
        assertEquals(2, report.at("/openPoints/0/evidenceCount").asInt());
        assertFalse(report.path("sourceAndSearchEvidenceComplete").asBoolean());
        assertNull(result.precision()); assertNull(result.recall());
    }

    @Test void sevenExactBindingsAreOneQuestionWithFourteenRetainedOrientations() throws Exception {
        var snapshot = inputs(); inputQuestionBindings(snapshot, OBSERVATION_INPUT_BINDINGS, true); saveSnapshot(snapshot);
        assertEquals(Verdict.PASS, ScenarioRelationQuality.write(directory).verdict());
        var report = json.readTree(Files.readString(directory.resolve("relation-quality.json")));
        assertEquals("PASS_WITH_OPEN_POINTS", report.path("outcome").asString());
        assertEquals(1, report.path("openQuestionGroupCount").asInt());
        assertEquals(7, report.path("openRelationBindingCount").asInt());
        assertEquals(7, report.path("openPoints").size());
        assertEquals(14, report.path("unfinishedSearchCount").asInt());
        assertEquals(10, report.path("unresolvedSearchCount").asInt());
        assertEquals(9, report.path("analysisWarningCount").asInt()); // Summary plus the actual first eight warnings.
        for (var point : report.path("openPoints")) {
            assertEquals("F5_OBSERVATION_INPUT", point.path("questionGroup").asString());
            assertEquals(INPUT_QUESTION, point.path("question").asString());
            assertEquals(2, point.path("evidenceCount").asInt());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"BP-1529:CONSUMES:IP-1116", "BP-1534:CONSUMES:IP-1116", "BP-1866:CONSUMES:IP-1116",
            "CI-1096:CONSUMES:IP-1116", "CP-1020:REQUIRES:IP-1116", "CP-1097:REQUIRES:IP-1116"})
    void aNamedOpenInputCanNeverBeAssertedAsARequiredRelationship(String signature) throws Exception {
        var snapshot = inputs(); inputQuestionBindings(snapshot, List.of(signature), true);
        addRelation((ArrayNode) snapshot.at("/analysis/architectureView/includedRelationships"), signature);
        saveSnapshot(snapshot);
        assertEquals(Verdict.FAIL, ScenarioRelationQuality.write(directory).verdict());
    }

    @ParameterizedTest
    @ValueSource(strings = {"BP-1521:CONSUMES:IP-1116", "BP-1529:CONSUMES:IP-2130",
            "BP-1534:PRODUCES:IP-1116", "CP-1041:REQUIRES:IP-1116", "CP-1097:CONSUMES:IP-1116"})
    void theSharedQuestionDoesNotGrantWildcardEndpointOrTypePermission(String signature) throws Exception {
        var snapshot = inputs(); inputQuestionBindings(snapshot, List.of(signature), true); saveSnapshot(snapshot);
        assertNotEquals(Verdict.PASS, ScenarioRelationQuality.write(directory).verdict());
    }

    @ParameterizedTest
    @ValueSource(strings = {"drop-duplicate", "reorder", "append-ninth-question", "omit-undisplayed-trace", "change-undisplayed-question"})
    void displayWarningLimitDoesNotWeakenFullInputEvidence(String mutation) throws Exception {
        var snapshot = inputs(); inputQuestionBindings(snapshot, OBSERVATION_INPUT_BINDINGS, true);
        var warnings = (ArrayNode) snapshot.at("/analysis/warnings");
        var search = (ObjectNode) snapshot.at("/analysis/relationSearchReport");
        switch (mutation) {
            case "drop-duplicate" -> warnings.remove(4);
            case "reorder" -> { var first = warnings.get(1).deepCopy(); warnings.set(1, warnings.get(2)); warnings.set(2, first); }
            case "append-ninth-question" -> warnings.add("CI-1096: " + INPUT_QUESTION);
            case "omit-undisplayed-trace" -> ((ArrayNode) search.at("/result/trace")).remove(13);
            case "change-undisplayed-question" -> ((ObjectNode) search.at("/result/unfinished/13"))
                    .put("question", "May the service issue its own warnings?");
            default -> fail("Unknown mutation");
        }
        saveSnapshot(snapshot);
        assertNotEquals(Verdict.PASS, ScenarioRelationQuality.write(directory).verdict());
    }

    @ParameterizedTest
    @ValueSource(strings = {"question", "target", "direction", "reason", "missing-trace", "task-state",
            "pending", "unassessed", "unretained", "warning", "stop", "missing-core", "false-edge", "pretend-success"})
    void acceptedIdentityDetailCannotHideUnfinishedWorkOrSemanticDefects(String mutation) throws Exception {
        var snapshot = inputs(); identityQuestion(snapshot, false);
        var search = (ObjectNode) snapshot.at("/analysis/relationSearchReport");
        var progress = (ObjectNode) search.path("progress");
        var unfinished = (ObjectNode) search.at("/result/unfinished/0");
        switch (mutation) {
            case "question" -> unfinished.put("question", "Which phone delivery channel should be implemented?");
            case "target" -> unfinished.putArray("targets").add("IP-2130");
            case "direction" -> unfinished.put("direction", "INCOMING");
            case "reason" -> unfinished.put("reason", "CALL_BUDGET");
            case "missing-trace" -> ((ArrayNode) search.at("/result/trace")).removeAll();
            case "task-state" -> ((ObjectNode) search.at("/tasks/0")).put("state", "PENDING");
            case "pending" -> progress.put("pendingSearches", 1).put("totalSearches", progress.path("totalSearches").asInt() + 1);
            case "unassessed" -> progress.put("totalSources", progress.path("totalSources").asInt() + 1);
            case "unretained" -> ((ArrayNode) search.path("sources")).remove(0);
            case "warning" -> ((ArrayNode) search.path("warnings")).add("SOURCE_LIMIT 1");
            case "stop" -> search.put("stopReason", "AWAITING_DECISION: active work stopped");
            case "missing-core" -> removeRelation(snapshot, "CO-1066:SUPPORTS:BP-1535");
            case "false-edge" -> addRelation((ArrayNode) snapshot.at("/analysis/architectureView/includedRelationships"), "BP-1017:CONSUMES:IP-1116");
            case "pretend-success" -> {
                ((ObjectNode) snapshot.path("summary")).put("status", "SUCCESS");
                ((ObjectNode) snapshot.path("analysis")).put("status", "SUCCESS");
            }
            default -> fail("Unknown mutation");
        }
        saveSnapshot(snapshot);
        assertNotEquals(Verdict.PASS, ScenarioRelationQuality.write(directory).verdict());
        assertThrows(AssertionError.class, () -> ScenarioRelationQuality.verify(directory));
    }

    private static final String INPUT_QUESTION = "Is the F5 incoming observation batch already a Recognized Environmental Picture, or is its product identity unspecified?";

    private void optionalProvider(ObjectNode snapshot, String direction, String necessity, String condition) {
        var result = (ObjectNode) snapshot.at("/analysis/relationSearchReport/result");
        ArrayNode edges = result.has("edges") ? (ArrayNode) result.path("edges") : result.putArray("edges");
        var edge = edges.addObject().put("type", "USES").put("direction", direction);
        boolean outgoing = direction.equals("OUTGOING");
        edge.putObject("contribution").putObject("source").put("id", outgoing ? "UA-1580" : "CI-1052");
        edge.putObject("target").put("id", outgoing ? "CI-1052" : "UA-1580");
        edge.putObject("evidence").put("outcome", "VERIFIED").put("necessity", necessity)
                .put("condition", condition).put("question", "");
    }

    private void identityQuestion(ObjectNode snapshot, boolean bothDirections) {
        ((ObjectNode) snapshot.path("summary")).put("status", "PARTIAL");
        var analysis = (ObjectNode) snapshot.path("analysis"); analysis.put("status", "PARTIAL");
        var search = (ObjectNode) analysis.path("relationSearchReport"); search.put("searchExhausted", false);
        var result = (ObjectNode) search.path("result");
        var unfinished = result.putArray("unfinished"); var trace = result.putArray("trace");
        int questionCount = bothDirections ? 2 : 1;
        for (int index = 0; index < questionCount; index++) {
            String source = index == 0 ? "BP-1017" : "IP-1116";
            String target = index == 0 ? "IP-1116" : "BP-1017";
            String direction = index == 0 ? "OUTGOING" : "INCOMING";
            unfinished.addObject().put("sourceId", source).put("type", "CONSUMES").put("direction", direction)
                    .put("reason", "UNRESOLVED").put("question", INPUT_QUESTION).putArray("targets").add(target);
            trace.addObject().put("sourceId", source).put("type", "CONSUMES").put("direction", direction)
                    .put("phase", "NAVIGATE").put("targetId", target).put("outcome", "UNRESOLVED")
                    .put("rationale", "F5 names an observation batch without identifying it as an already recognised environmental picture.");
        }
        var tasks = search.putArray("tasks");
        tasks.addObject().put("sourceId", "BP-1017").put("targetRoot", "IP").put("state", "UNRESOLVED");
        if (bothDirections) tasks.addObject().put("sourceId", "IP-1116").put("targetRoot", "BP").put("state", "UNRESOLVED");
        tasks.addObject().put("sourceId", "UA-1580").put("targetRoot", "IP").put("state", "COMPLETED");
        ((ObjectNode) search.path("progress")).put("totalSearches", tasks.size()).put("completedSearches", 1)
                .put("unresolvedSearches", questionCount).put("pendingSearches", 0);
        var warnings = analysis.putArray("warnings");
        warnings.add("RELATION_SEARCH_PARTIAL: 136/256 evaluation attempts; " + questionCount
                + " unfinished search batches. 0 unassessed sources; 0 pending and " + questionCount + " unresolved source/target searches.");
        warnings.add("BP-1017: " + INPUT_QUESTION);
        if (bothDirections) warnings.add("IP-1116: " + INPUT_QUESTION);
    }

    private void inputQuestionBindings(ObjectNode snapshot, List<String> signatures, boolean bothDirections) {
        ((ObjectNode) snapshot.path("summary")).put("status", "PARTIAL");
        var analysis = (ObjectNode) snapshot.path("analysis"); analysis.put("status", "PARTIAL");
        var search = (ObjectNode) analysis.path("relationSearchReport"); search.put("searchExhausted", false);
        var result = (ObjectNode) search.path("result");
        var unfinished = result.putArray("unfinished"); var trace = result.putArray("trace");
        var taskKeys = new TreeSet<String>(); var retained = new TreeSet<String>();
        for (var source : search.path("sources")) retained.add(source.at("/node/id").asString());
        for (String signature : signatures) {
            String[] parts = signature.split(":");
            for (String direction : bothDirections ? List.of("OUTGOING", "INCOMING") : List.of("OUTGOING")) {
                String source = direction.equals("OUTGOING") ? parts[0] : parts[2];
                String target = direction.equals("OUTGOING") ? parts[2] : parts[0];
                retained.add(source); retained.add(target);
                unfinished.addObject().put("sourceId", source).put("type", parts[1]).put("direction", direction)
                        .put("reason", "UNRESOLVED").put("question", INPUT_QUESTION).putArray("targets").add(target);
                trace.addObject().put("sourceId", source).put("type", parts[1]).put("direction", direction)
                        .put("phase", "VERIFY").put("targetId", target).put("outcome", "UNRESOLVED")
                        .put("rationale", "F5 leaves the incoming observation product identity unspecified for this exact input function.");
                taskKeys.add(source + ":" + target.substring(0, target.indexOf('-')));
            }
        }
        var sources = search.putArray("sources");
        for (String source : retained) sources.addObject().put("question", "").putObject("node").put("id", source);
        var tasks = search.putArray("tasks");
        for (String key : taskKeys) {
            String[] parts = key.split(":");
            tasks.addObject().put("sourceId", parts[0]).put("targetRoot", parts[1]).put("state", "UNRESOLVED");
        }
        tasks.addObject().put("sourceId", "UA-1580").put("targetRoot", "IP").put("state", "COMPLETED");
        ((ObjectNode) search.path("progress")).put("totalSources", retained.size()).put("assessedSources", retained.size())
                .put("totalSearches", tasks.size()).put("completedSearches", 1)
                .put("unresolvedSearches", taskKeys.size()).put("pendingSearches", 0);
        var warnings = analysis.putArray("warnings");
        warnings.add("RELATION_SEARCH_PARTIAL: 136/256 evaluation attempts; " + unfinished.size()
                + " unfinished search batches. 0 unassessed sources; 0 pending and " + taskKeys.size() + " unresolved source/target searches.");
        for (int index = 0; index < Math.min(8, unfinished.size()); index++) {
            warnings.add(unfinished.get(index).path("sourceId").asString() + ": " + INPUT_QUESTION);
        }
    }

    private void assertInconclusive(ObjectNode snapshot) throws Exception {
        saveSnapshot(snapshot);
        var result = ScenarioRelationQuality.write(directory);
        assertEquals(RunState.PARTIAL, result.state());
        assertEquals(Verdict.INCONCLUSIVE, result.verdict());
        assertNull(result.precision());
        assertNull(result.recall());
        assertThrows(AssertionError.class, () -> ScenarioRelationQuality.verify(directory));
    }

    private ObjectNode inputs() throws Exception {
        var snapshot = json.createObjectNode();
        snapshot.putObject("summary").put("id", "snapshot-1").put("status", "SUCCESS")
                .put("taxonomyFingerprint", "a".repeat(64)).put("promptFingerprint", "b".repeat(64))
                .put("requirementKey", "FLOOD-001");
        var analysis = snapshot.putObject("analysis").put("status", "SUCCESS");
        analysis.putArray("warnings");
        var search = analysis.putObject("relationSearchReport").put("totalCalls", 136).put("maxCalls", 256)
                .put("durationMillis", 100).put("stopReason", "").put("originalSha256", ORIGINAL_SHA256)
                .put("searchExhausted", true);
        search.putArray("warnings");
        var relationResult = search.putObject("result");
        relationResult.putArray("unfinished"); relationResult.putArray("edges");
        var relations = analysis.putObject("architectureView").putArray("includedRelationships");
        // Contract-test evidence, not a live application or model-quality result.
        var sourceIds = new TreeSet<String>();
        for (String signature : CORE) {
            String[] parts = signature.split(":");
            sourceIds.add(parts[0]); sourceIds.add(parts[2]);
            addRelation(relations, signature);
        }
        var sources = search.putArray("sources");
        for (String source : sourceIds) sources.addObject().put("question", "").putObject("node").put("id", source);
        search.putObject("progress").put("totalSources", sourceIds.size()).put("assessedSources", sourceIds.size())
                .put("totalSearches", 38).put("completedSearches", 38).put("unresolvedSearches", 0).put("pendingSearches", 0);
        saveSnapshot(snapshot);
        var run = json.createObjectNode().put("scenario", "flood-information-v1")
                .put("fixtureSha256", "c".repeat(64)).put("snapshotId", "snapshot-1")
                .put("sourceRevision", "d".repeat(40)).put("llmCalls", 350);
        Files.writeString(directory.resolve("run.json"), run.toString());
        return snapshot;
    }

    private void saveSnapshot(ObjectNode snapshot) throws Exception {
        Files.writeString(directory.resolve("snapshot.json"), snapshot.toString());
    }

    private static void addRelation(ArrayNode relations, String signature) {
        String[] parts = signature.split(":");
        relations.addObject().put("sourceCode", parts[0]).put("relationType", parts[1]).put("targetCode", parts[2]);
    }

    private static void removeRelation(ObjectNode snapshot, String signature) {
        var relations = (ArrayNode) snapshot.at("/analysis/architectureView/includedRelationships");
        for (int i = 0; i < relations.size(); i++) {
            var relation = relations.get(i);
            String key = relation.path("sourceCode").asString() + ":" + relation.path("relationType").asString()
                    + ":" + relation.path("targetCode").asString();
            if (key.equals(signature)) { relations.remove(i); return; }
        }
        fail("Mutation target missing: " + signature);
    }
}
