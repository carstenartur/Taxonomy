package com.taxonomy.analysis.demo.evaluation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compares two real analyses (A0 for R0, A1 for R1) against the independent
 * reference annotation. Missing work is reported as NOT_EXECUTED, never as a
 * failed or passed quality finding.
 */
public final class DemoReferenceEvaluator {

    public enum Status { PASS, FAIL, NOT_EXECUTED }

    public enum Overall { PASS, FAIL, INCOMPLETE }

    /** One analysed iteration; {@code null} scores mean the analysis was not executed or not completed. */
    public record Observation(String requirementText, Map<String, Integer> scores, Set<String> relations) {
        public boolean executed() { return requirementText != null && scores != null; }
    }

    public record Check(String id, String kind, String iteration, Status status, String expected, String actual) { }

    public record Report(String scenario, int referenceVersion, Overall overall, List<Check> checks,
                         List<String> improvements) { }

    private DemoReferenceEvaluator() { }

    public static Report evaluate(DemoReference reference, Observation a0, Observation a1) {
        Objects.requireNonNull(reference);
        var checks = new ArrayList<Check>();
        var improvements = new ArrayList<String>();
        for (var aspect : reference.requiredAspects()) {
            Status r0 = textStatus(a0, aspect.anyOf());
            Status r1 = textStatus(a1, aspect.anyOf());
            checks.add(new Check("aspect:" + aspect.id(), "REQUIRED_ASPECT", "R1", r1,
                    "one of " + aspect.anyOf(), describe(r1)));
            if (r0 == Status.FAIL && r1 == Status.PASS) improvements.add("aspect:" + aspect.id());
        }
        for (var forbidden : reference.forbiddenPhrases()) {
            for (var entry : iterations(a0, a1)) {
                Status status = !executed(entry.getValue()) ? Status.NOT_EXECUTED
                        : entry.getValue().requirementText().contains(forbidden.phrase()) ? Status.FAIL : Status.PASS;
                checks.add(new Check("forbidden-phrase:" + forbidden.id(), "FORBIDDEN_PHRASE", entry.getKey(), status,
                        "absent: " + forbidden.phrase(), describe(status)));
            }
        }
        for (var node : reference.expectedNodes()) {
            for (String iteration : node.iterations()) {
                var observation = "R0".equals(iteration) ? a0 : a1;
                Status status;
                String actual;
                if (!executed(observation)) { status = Status.NOT_EXECUTED; actual = "not executed"; }
                else {
                    int score = observation.scores().getOrDefault(node.code(), 0);
                    status = score >= node.minimumScore() ? Status.PASS : Status.FAIL;
                    actual = Integer.toString(score);
                }
                checks.add(new Check("node:" + node.code(), "EXPECTED_NODE", iteration, status,
                        ">= " + node.minimumScore(), actual));
            }
            if (executed(a0) && executed(a1)
                    && a1.scores().getOrDefault(node.code(), 0) > a0.scores().getOrDefault(node.code(), 0))
                improvements.add("node:" + node.code());
        }
        for (var node : reference.forbiddenNodes()) {
            for (var entry : iterations(a0, a1)) {
                Status status;
                String actual;
                if (!executed(entry.getValue())) { status = Status.NOT_EXECUTED; actual = "not executed"; }
                else {
                    int score = entry.getValue().scores().getOrDefault(node.code(), 0);
                    status = score > 0 ? Status.FAIL : Status.PASS;
                    actual = Integer.toString(score);
                }
                checks.add(new Check("forbidden-node:" + node.code(), "FORBIDDEN_NODE", entry.getKey(), status, "0", actual));
            }
        }
        for (var relation : reference.expectedRelations()) {
            for (String iteration : relation.iterations()) {
                var observation = "R0".equals(iteration) ? a0 : a1;
                Status status = !executed(observation) || observation.relations() == null ? Status.NOT_EXECUTED
                        : observation.relations().contains(relation.key()) ? Status.PASS : Status.FAIL;
                checks.add(new Check("relation:" + relation.key(), "EXPECTED_RELATION", iteration, status,
                        "present", describe(status)));
            }
            if (executed(a0) && executed(a1) && a0.relations() != null && a1.relations() != null
                    && !a0.relations().contains(relation.key()) && a1.relations().contains(relation.key()))
                improvements.add("relation:" + relation.key());
        }
        Overall overall = checks.stream().anyMatch(c -> c.status() == Status.FAIL) ? Overall.FAIL
                : checks.stream().anyMatch(c -> c.status() == Status.NOT_EXECUTED) ? Overall.INCOMPLETE : Overall.PASS;
        return new Report(reference.scenario(), reference.version(), overall, List.copyOf(checks), List.copyOf(improvements));
    }

    /** Null-tolerant iteration pairs: an absent analysis is NOT_EXECUTED, never an error. */
    private static List<Map.Entry<String, Observation>> iterations(Observation a0, Observation a1) {
        return List.of(new java.util.AbstractMap.SimpleImmutableEntry<>("R0", a0),
                new java.util.AbstractMap.SimpleImmutableEntry<>("R1", a1));
    }

    private static boolean executed(Observation observation) { return observation != null && observation.executed(); }

    private static Status textStatus(Observation observation, List<String> anyOf) {
        if (!executed(observation)) return Status.NOT_EXECUTED;
        return anyOf.stream().anyMatch(observation.requirementText()::contains) ? Status.PASS : Status.FAIL;
    }

    private static String describe(Status status) {
        return switch (status) {
            case PASS -> "present";
            case FAIL -> "missing";
            case NOT_EXECUTED -> "not executed";
        };
    }
}
