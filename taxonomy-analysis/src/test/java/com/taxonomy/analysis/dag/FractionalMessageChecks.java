package com.taxonomy.analysis.dag;

import com.taxonomy.analysis.dag.json.AnalysisMessageCodec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;

/** Actual codec contract, shared with its ordinary JUnit adapter. No broker or remote model. */
public final class FractionalMessageChecks {
    private static final AnalysisMessageCodec CODEC = new AnalysisMessageCodec();
    private static final AnalysisMessageFactory FACTORY = new AnalysisMessageFactory(
            new AnalysisOperationContext("fraction-test", new AnalysisSourceAuthority("repo", null, "draft", "abcdef"),
                    RequirementReference.adHoc("record working time"), "corr"), Clock.systemUTC());
    private static SubtaxonomyAnalysisTask task() {
        return (SubtaxonomyAnalysisTask) FACTORY.task(AnalysisTaskGraph.plan("fraction-test",
                List.of(TaxonomyShardRoot.of("BP")), false).tasks().getFirst());
    }
    public static void roundTrip() {
        var complete = FACTORY.completed(task(), AnalysisTaskOutcome.COMPLETED, .125, 2, null);
        require(complete.envelope().schemaVersion() == 3, "fractional results require wire version3");
        require(CODEC.decode(CODEC.encode(complete)).equals(complete), "fractional wire value changed");
    }
    public static void legacy() {
        var original = task();
        String taskJson = new String(CODEC.encode(original), StandardCharsets.UTF_8)
                .replace("\"schemaVersion\":" + original.envelope().schemaVersion(), "\"schemaVersion\":1");
        var legacyTask = (SubtaxonomyAnalysisTask) CODEC.decode(taskJson.getBytes(StandardCharsets.UTF_8));
        var complete = FACTORY.completed(legacyTask, AnalysisTaskOutcome.COMPLETED, .125, 2, null);
        require(complete.envelope().schemaVersion() == 3, "legacy task produced fractional evidence under integer schema");
        String json = new String(CODEC.encode(FACTORY.completed(task(), AnalysisTaskOutcome.COMPLETED, 85, 2, null)), StandardCharsets.UTF_8);
        String old = json.replace("\"schemaVersion\":3", "\"schemaVersion\":1");
        require(((SubtaxonomyAnalysisCompleted)CODEC.decode(old.getBytes(StandardCharsets.UTF_8))).rootScore() == 85,
                "legacy85 was rescaled");
        reject(() -> CODEC.decode(old.replace("\"rootScore\":85.0", "\"rootScore\":0.125").getBytes(StandardCharsets.UTF_8)));
    }
    public static void invalid() {
        var c = FACTORY.completed(task(), AnalysisTaskOutcome.COMPLETED, .125, 2, null);
        reject(() -> new SubtaxonomyAnalysisCompleted(c.envelope(), c.root(), c.outcome(), Double.NaN, 2, null));
        String json = new String(CODEC.encode(c), StandardCharsets.UTF_8);
        for (String value : List.of("-0.1", "100.00000000000000001", "1e-9999", "\"0.125\""))
            reject(() -> CODEC.decode(json.replace("\"rootScore\":0.125", "\"rootScore\":"+value).getBytes(StandardCharsets.UTF_8)));
        reject(() -> CODEC.decode(json.replace("\"scoredNodes\":2", "\"scoredNodes\":2.5").getBytes(StandardCharsets.UTF_8)));
    }
    private static void reject(Runnable action) {
        try { action.run(); throw new AssertionError("invalid numeric message accepted"); }
        catch (com.taxonomy.analysis.dag.json.AnalysisMessageFormatException | IllegalArgumentException expected) { }
    }
    private static void require(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
    public static void main(String[] args) {
        switch (args[0]) { case "round-trip" -> roundTrip(); case "legacy" -> legacy(); case "invalid" -> invalid();
            default -> throw new IllegalArgumentException(args[0]); }
        System.out.println("PASS message " + args[0]);
    }
}
