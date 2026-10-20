package com.taxonomy;

import com.taxonomy.dto.RelationSearchModel.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Completion evidence is checked for each original pass, independently of export selection. */
class ScenarioIndependentPassCompletionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long PROJECT_ID = 7;
    private static final String QUESTION = "Is the F5 incoming observation batch already a Recognized Environmental Picture, or is its product identity unspecified?";
    private static final String F5 = "F5: Consume the latest observation batch at most every 15 minutes; show stale or unavailable data and retain the official warning channels.";

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void acceptsBothIndependentlyCompletedPasses(boolean openIdentity) throws Exception {
        var app = new Application(openIdentity, "first");
        app.verifyIndependentPasses(PROJECT_ID, app.operation, app.selectedAnalysis());
        assertThat(app.reads).containsExactly(snapshotPath("first"), snapshotPath("second"));
        for (JsonNode snapshot : app.snapshots.values()) {
            assertThat(snapshot.at("/analysis/recovery/state").asText())
                    .isEqualTo(openIdentity ? "COMPLETED_WITH_GAPS" : "COMPLETED");
        }
    }

    @ParameterizedTest(name = "{index}: {0}, {1}") @MethodSource("incompletePasses")
    void rejectsTechnicalFailureInTheUnselectedPassDespiteIdenticalArchitecture(
            String selected, String expectedFailure, Consumer<ObjectNode> mutation) {
        var app = new Application(true, selected);
        String unselected = selected.equals("first") ? "second" : "first";
        ObjectNode snapshot = app.snapshots.get(unselected);
        ObjectNode analysis = (ObjectNode) snapshot.path("analysis");
        mutation.accept(snapshot);
        for (String field : List.of("scores", "reasons", "architectureView")) {
            assertThat(analysis.path(field)).as("The old cross-pass comparison still accepts %s", field)
                    .isEqualTo(app.selectedAnalysis().path(field));
        }
        assertThat(app.selectedAnalysis().at("/recovery/state").asText()).isEqualTo("COMPLETED_WITH_GAPS");
        assertThatThrownBy(() -> app.verifyIndependentPasses(PROJECT_ID, app.operation, app.selectedAnalysis()))
                .isInstanceOf(AssertionError.class).hasMessageContaining(expectedFailure);
        assertThat(app.reads).contains(snapshotPath(unselected));
    }

    private static Stream<Arguments> incompletePasses() {
        return Stream.of("first", "second").flatMap(selected -> Stream.of(
                Arguments.of(selected, "durable recovery state", (Consumer<ObjectNode>) snapshot ->
                        snapshot.withObject("/analysis/recovery").put("state", "PAUSED")),
                Arguments.of(selected, "skipped or uncertain transport", (Consumer<ObjectNode>) snapshot ->
                        ((ObjectNode) snapshot.at("/analysis/recovery/openQuestions/0")).put("outcomeUncertain", true)),
                Arguments.of(selected, "skipped or uncertain transport", (Consumer<ObjectNode>) snapshot ->
                        ((ObjectNode) snapshot.at("/analysis/recovery/openQuestions/0")).put("skipped", true)),
                Arguments.of(selected, "Unrelated or uncertain durable question", (Consumer<ObjectNode>) snapshot ->
                        ((ObjectNode) snapshot.at("/analysis/recovery/openQuestions/0"))
                                .put("error", "RELATION_UNRESOLVED: CR-1097: Is queue durability required?")),
                Arguments.of(selected, "Analysis warnings", (Consumer<ObjectNode>) snapshot ->
                        ((ArrayNode) snapshot.at("/analysis/warnings")).add("MEMORY_PRESSURE: analysis stopped")),
                Arguments.of(selected, "analysis completion status", (Consumer<ObjectNode>) snapshot -> {
                    snapshot.withObject("/analysis").put("status", "FAILED");
                    snapshot.withObject("/summary").put("status", "FAILED");
                }),
                Arguments.of(selected, "snapshot analysis completion status", (Consumer<ObjectNode>) snapshot ->
                        snapshot.withObject("/summary").put("status", "FAILED"))));
    }

    /** Small completion-only evidence; the complete exported graph has its existing independent QA suite. */
    private static ObjectNode snapshot(String id, boolean openIdentity) {
        String status = openIdentity ? "PARTIAL" : "SUCCESS";
        Node acquisition = new Node("BP-1017", "BP", "Data Acquisition", "Acquire incoming observations.", false);
        Node picture = new Node("IP-1116", "IP", "Recognized Environmental Picture", "Recognized environmental information.", false);
        Contribution contribution = new Contribution(acquisition,
                "Consume the F5 observation batch without assuming that its input product is already recognized.", F5, "");
        var snapshot = JSON.createObjectNode();
        snapshot.putObject("summary").put("id", id).put("projectId", PROJECT_ID).put("requirementVersionId", 11).put("status", status);
        ObjectNode analysis = snapshot.putObject("analysis");
        analysis.put("status", status);
        analysis.set("scores", JSON.valueToTree(Map.of("BP-1017", 80)));
        analysis.set("reasons", JSON.valueToTree(Map.of("BP-1017", contribution.text())));
        ObjectNode search = analysis.putObject("relationSearchReport");
        search.put("durationMillis", 5).put("totalCalls", 5).put("maxCalls", 24).put("searchExhausted", !openIdentity);
        search.putArray("warnings");
        search.set("sources", JSON.valueToTree(List.of(
                new SourceAssessment(acquisition, List.of(contribution), "F5 expressly requires acquisition of the incoming batch.", ""),
                new SourceAssessment(picture, List.of(), "The product identity of the F5 input is not specified.", ""))));
        // This completion-only fixture still needs the entire compatible plan,
        // including completed scopes of the source with no positive contribution.
        var rootsBySource = new LinkedHashMap<String, List<String>>();
        rootsBySource.put("BP-1017", List.of("BR", "CI", "CO", "CR", "IP", "UA"));
        rootsBySource.put("IP-1116", List.of("BP", "BR", "CI", "CP", "CR", "UA"));
        var tasks = search.putArray("tasks");
        rootsBySource.forEach((source, roots) -> roots.forEach(root -> tasks.addObject()
                .put("sourceId", source).put("targetRoot", root)
                .put("state", openIdentity && source.equals("BP-1017") && root.equals("IP")
                        ? "UNRESOLVED" : "COMPLETED")));
        search.putObject("progress").put("totalSources", 2).put("assessedSources", 2)
                .put("totalSearches", tasks.size()).put("completedSearches", tasks.size() - (openIdentity ? 1 : 0))
                .put("pendingSearches", 0).put("unresolvedSearches", openIdentity ? 1 : 0);
        search.set("result", JSON.valueToTree(new Result(List.of(),
                openIdentity ? List.of(new Unfinished("BP-1017", "CONSUMES", Direction.OUTGOING, List.of("IP-1116"), "UNRESOLVED", QUESTION)) : List.of(),
                openIdentity ? List.of(new Trace("BP-1017", "CONSUMES", Direction.OUTGOING, Phase.NAVIGATE,
                        "IP-1116", Outcome.UNRESOLVED, "F5 names an observation batch but does not establish a recognized input product.")) : List.of(),
                5, 5, 4)));
        ObjectNode recovery = analysis.putObject("recovery");
        recovery.put("id", "journal-" + id).put("version", 12).put("state", openIdentity ? "COMPLETED_WITH_GAPS" : "COMPLETED");
        ArrayNode questions = recovery.putArray("openQuestions");
        ArrayNode warnings = analysis.putArray("warnings");
        if (openIdentity) {
            ObjectNode checkpoint = questions.addObject();
            checkpoint.put("key", "R" + "a".repeat(63)).put("error", "RELATION_UNRESOLVED: IP-1116: " + QUESTION)
                    .put("attempts", 1).put("skipped", false).put("outcomeUncertain", false);
            checkpoint.putArray("nodes").add("IP-1116");
            String diagnostic = "RELATION_SEARCH_PARTIAL: 5/24 evaluation attempts; 1 unfinished search batches. "
                    + "0 unassessed sources; 0 pending and 1 unresolved source/target searches.";
            analysis.put("errorMessage", diagnostic);
            warnings.add(diagnostic).add("BP-1017: " + QUESTION);
        }
        analysis.putObject("architectureView").put("analysisStatus", status).set("relationSearchReport", search.deepCopy());
        return snapshot;
    }

    private static String snapshotPath(String id) {
        return "/api/projects/" + PROJECT_ID + "/snapshots/" + id;
    }

    /** Isolated response fixture for the actual acceptance pass loop, without Spring or provider calls. */
    private static final class Application extends ScenarioArchitectureAcceptanceTest {
        final Map<String, ObjectNode> snapshots = new LinkedHashMap<>();
        final List<String> reads = new ArrayList<>();
        final ObjectNode operation = JSON.createObjectNode();

        Application(boolean openIdentity, String selected) {
            operation.put("selectedSnapshotId", selected).put("completedPasses", 2);
            ArrayNode jobs = operation.putArray("jobs");
            for (String id : List.of("first", "second")) {
                snapshots.put(id, snapshot(id, openIdentity));
                jobs.addObject().put("id", "job-" + id).putArray("items").addObject().put("snapshotId", id);
            }
        }

        JsonNode selectedAnalysis() {
            return snapshots.get(operation.path("selectedSnapshotId").asText()).path("analysis");
        }

        @Override JsonNode get(String path) {
            reads.add(path);
            for (var entry : snapshots.entrySet()) if (path.equals(snapshotPath(entry.getKey()))) return entry.getValue().deepCopy();
            throw new AssertionError("Unexpected snapshot read: " + path);
        }
    }
}
