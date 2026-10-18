package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Executes the acceptance predicates themselves, without Kubernetes or Docker. */
class HelmArtemisSequenceTest {
    @TempDir Path temporary;

    @Test
    void sequenceEvidenceDistinguishesOneInitialIntentFromTwoFinalIntents() throws Exception {
        for (String stage : List.of("waiting-broker", "first-dispatched", "successor-dispatched", "completed")) {
            Map<String, Object> snapshot = snapshot(stage);
            snapshot.put("sourceText", "must-not-be-published");
            work(snapshot, 0).put("message", "must-not-be-published");
            Result result = observe(stage, snapshot);
            assertThat(result.exitCode()).as(stage + ": " + result.output()).isZero();
            assertThat(result.output()).contains("\"observed\":true", "\"stage\":\"" + stage + "\"")
                    .doesNotContain("must-not-be-published");
            Map<String, Object> evidence = FlatJson.parseObject(result.output());
            assertThat((List<?>) evidence.get("intents")).hasSize(stage.equals("waiting-broker") || stage.equals("first-dispatched") ? 1 : 2);
        }
    }

    @Test
    void sequenceEvidenceRejectsPrematureSuccessorMissingEffectsAndDuplicateDebits() throws Exception {
        for (String stage : List.of("waiting-broker", "first-dispatched", "successor-dispatched", "completed")) {
            List<Map<String, Object>> invalid = new ArrayList<>();
            Map<String, Object> missingIntent = snapshot(stage);
            missingIntent.put("intents", List.of());
            invalid.add(missingIntent);
            Map<String, Object> duplicateIntent = snapshot(stage);
            List<Object> intents = new ArrayList<>((List<?>) duplicateIntent.get("intents"));
            intents.add(Map.of("root", "IP", "status", "DISPATCHED"));
            duplicateIntent.put("intents", intents);
            invalid.add(duplicateIntent);
            Map<String, Object> missingInput = snapshot(stage);
            missingInput.put("inputs", List.of("CP"));
            invalid.add(missingInput);
            Map<String, Object> wrongEffects = snapshot(stage);
            wrongEffects.put("completions", List.of("CP", "CP"));
            invalid.add(wrongEffects);
            Map<String, Object> wrongPredecessor = snapshot(stage);
            work(wrongPredecessor, 0).put("settled", !stage.equals("completed") && !stage.equals("successor-dispatched"));
            invalid.add(wrongPredecessor);
            Map<String, Object> wrongSuccessor = snapshot(stage);
            work(wrongSuccessor, 1).put("state", stage.equals("completed") ? "QUEUED" : "RUNNING");
            invalid.add(wrongSuccessor);
            Map<String, Object> wrongAttempts = snapshot(stage);
            work(wrongAttempts, 0).put("attempts", stage.equals("completed") || stage.equals("successor-dispatched") ? 1 : 2);
            invalid.add(wrongAttempts);
            for (Map<String, Object> value : invalid) {
                Result rejected = observe(stage, value);
                assertThat(rejected.exitCode()).as(stage + ": " + FlatJson.pretty(value)).isNotZero();
                assertThat(rejected.output()).contains("Serial root dispatch observation unavailable or inconsistent")
                        .doesNotContain("\"observed\":true");
            }
        }
    }

    @Test
    void frozenAdmissionRetainsBothRootsButOnlyThePredecessorDispatchAuthority() throws Exception {
        String identity = """
                {"run":["op","admin","scope","context","command","view","QUEUED",2,0,1],
                 "work":[["cp","task-cp","SUBTAXONOMY_ANALYSIS","CP",0,"message-cp","QUEUED",false,0],
                         ["ip","task-ip","SUBTAXONOMY_ANALYSIS","IP",1,"message-ip","WAITING_PREDECESSOR",false,0]],
                 "inputs":[["cp","CP","frozen-cp"],["ip","IP","frozen-ip"]],
                 "intents":[["intent-cp","task-cp","SUBTAXONOMY_ANALYSIS","CP","message-cp"]],
                 "events":[["first",1,"event"]]}
                """;
        String source = Files.readString(root().resolve("deploy/helm/taxonomy/artemis-constrained-smoke.sh"));
        var matcher = Pattern.compile("^frozen_identity\\(\\) \\{[\\s\\S]*?^\\}", Pattern.MULTILINE).matcher(source);
        assertThat(matcher.find()).isTrue();
        String predicate = matcher.group();
        Files.writeString(temporary.resolve("identity.json"), identity);
        var valid = run(List.of("bash", "-c", "set -euo pipefail\n"
                + "sql() { cat '" + temporary.resolve("identity.json") + "'; }\n"
                + predicate + "\nOPERATION=op\nfrozen_identity"));
        assertThat(valid.exitCode()).as(valid.output()).isZero();
        assertThat(valid.output()).contains("WAITING_PREDECESSOR", "frozen-ip", "intent-cp");

        for (String invalid : List.of(
                identity.replace("\"intents\":[[\"intent-cp\",\"task-cp\",\"SUBTAXONOMY_ANALYSIS\",\"CP\",\"message-cp\"]]", "\"intents\":[]"),
                identity.replace("\"IP\",\"frozen-ip\"", "\"CP\",\"frozen-ip\""),
                identity.replace("\"WAITING_PREDECESSOR\"", "\"QUEUED\""))) {
            Files.writeString(temporary.resolve("identity.json"), invalid);
            assertThat(run(List.of("bash", "-c", "set -euo pipefail\n"
                    + "sql() { cat '" + temporary.resolve("identity.json") + "'; }\n"
                    + predicate + "\nOPERATION=op\nfrozen_identity")).exitCode())
                    .as("Incomplete or prematurely dispatched selected graph must be rejected").isNotZero();
        }
    }

    private Result observe(String stage, Map<String, Object> snapshot) throws Exception {
        Path input = temporary.resolve("snapshot.json");
        Files.writeString(input, FlatJson.pretty(snapshot));
        return run(List.of("bash", "-c", "exec '" + JavascriptJUnitBridge.nodeExecutable() + "' '"
                + root().resolve("deploy/helm/taxonomy/verify-artemis-sequence.mjs") + "' '" + stage + "' < '" + input + "'"));
    }

    private static Map<String, Object> snapshot(String stage) {
        boolean completed = stage.equals("completed");
        boolean settled = completed || stage.equals("successor-dispatched");
        return new LinkedHashMap<>(Map.of(
                "state", completed ? "COMPLETED" : settled ? "RUNNING" : "QUEUED",
                "completedRoots", completed ? 2 : settled ? 1 : 0,
                "totalRoots", 2, "revision", settled ? 4 : 1,
                "inputs", List.of("CP", "IP"),
                "work", List.of(
                        new LinkedHashMap<>(Map.of("root", "CP", "state", settled ? "COMPLETED" : "QUEUED", "settled", settled, "hasResult", settled, "attempts", settled ? 2 : 0)),
                        new LinkedHashMap<>(Map.of("root", "IP", "state", completed ? "COMPLETED" : settled ? "QUEUED" : "WAITING_PREDECESSOR", "settled", completed, "hasResult", completed, "attempts", completed ? 1 : 0))),
                "intents", settled ? List.of(Map.of("root", "CP", "status", "DISPATCHED"), Map.of("root", "IP", "status", "DISPATCHED"))
                        : List.of(Map.of("root", "CP", "status", stage.equals("waiting-broker") ? "WAITING_FOR_BROKER" : "DISPATCHED")),
                "completions", completed ? List.of("CP", "IP") : settled ? List.of("CP") : List.of()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> work(Map<String, Object> snapshot, int ordinal) {
        return ((List<Map<String, Object>>) snapshot.get("work")).get(ordinal);
    }

    private Result run(List<String> command) throws Exception {
        Path log = Files.createTempFile(temporary, "sequence-", ".log");
        Process process = new ProcessBuilder(command).directory(root().toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
            return new Result(process.exitValue(), Files.readString(log));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static Path root() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("deploy/helm/taxonomy/artemis-constrained-smoke.sh")))
            current = current.getParent();
        if (current == null) throw new IllegalStateException("Repository not found");
        return current;
    }

    private record Result(int exitCode, String output) { }
}
