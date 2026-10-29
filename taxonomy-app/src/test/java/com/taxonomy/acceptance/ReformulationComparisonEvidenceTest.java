package com.taxonomy.acceptance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ReformulationComparisonEvidenceTest {
    private final JsonMapper json = JsonMapper.builder().build();
    @TempDir Path root;

    @Test void anInterruptedRunWithNoRecordedFailuresMustNotClaimCompleteOutputs() {
        var manifest = json.createObjectNode();
        manifest.putArray("cases").addObject().put("id", "sparse").put("outcome", "PENDING");
        assertThat(ReformulationComparisonEvidence.completionStatus(manifest)).isEqualTo("INCOMPLETE_EXECUTION");
    }

    @Test void absentProviderRecordsNotRunWithoutTreatingItAsAQualityFailure() throws Exception {
        var run = create();
        run.notRun("No configured generative provider available");
        var manifest = read(run.directory().resolve("manifest.json"));
        assertThat(manifest.path("status").asText()).isEqualTo("NOT_RUN");
        assertThat(manifest.path("humanQualityGate").asText()).isEqualTo("NOT_REVIEWED");
        for (var entry : manifest.path("cases")) assertThat(entry.path("outcome").asText()).isEqualTo("NOT_RUN");
        assertThatThrownBy(() -> ReformulationComparisonEvidence.validateReviews(run.directory(), json, corpus()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void baselineFailureAndUnstartedCasesHaveExplicitIncompleteOutcomes() throws Exception {
        var run = create(); run.start("TEST_PROVIDER", "fixture-model");
        run.beginCase("sparse"); run.abort(new IllegalStateException("sensitive exception text"));
        var manifest = read(run.directory().resolve("manifest.json"));
        assertThat(manifest.path("status").asText()).isEqualTo("INCOMPLETE_EXECUTION");
        assertThat(manifest.path("cases").get(0).path("outcome").asText()).isEqualTo("INCOMPLETE");
        assertThat(manifest.path("cases").get(1).path("outcome").asText()).isEqualTo("NOT_EXECUTED");
        assertThat(manifest.toString()).doesNotContain("sensitive exception text");
    }

    @Test void staleOutputsCannotPopulateANewRun() throws Exception {
        var old = complete(); var next = create(); next.start("TEST_PROVIDER", "fixture-model"); next.finish();
        assertThat(old.directory()).isNotEqualTo(next.directory());
        assertThat(read(next.directory().resolve("manifest.json")).path("status").asText()).isEqualTo("INCOMPLETE_EXECUTION");
        assertThat(Files.exists(next.directory().resolve("sparse/walk-up.json"))).isFalse();
    }

    @Test void onlyFivePersistedPairsReachPendingHumanReview() throws Exception {
        var run = complete(); var manifest = read(run.directory().resolve("manifest.json"));
        assertThat(manifest.path("status").asText()).isEqualTo("OUTPUTS_REQUIRE_HUMAN_REVIEW");
        assertThat(manifest.path("humanQualityGate").asText()).isEqualTo("NOT_REVIEWED");
        assertThat(manifest.path("runId").asText()).isEqualTo(run.directory().getFileName().toString());
        assertThat(manifest.path("sourceCommit").asText()).isEqualTo("a".repeat(40));
        assertThat(manifest.path("cases")).hasSize(5);
        for (var entry : manifest.path("cases")) {
            assertThat(entry.path("outcome").asText()).isEqualTo("COMPLETE");
            assertThat(entry.path("contextSha256").asText()).hasSize(64);
            assertThat(entry.path("walk-up").path("outputSha256").asText()).hasSize(64);
        }
        assertThat(Files.readString(run.directory().resolve("summary.md")))
                .contains("10", "NOT_REVIEWED", "No semantic quality verdict");
    }

    @Test void schemaFailureIsDistinctFromIncompleteExecution() throws Exception {
        var run = create(); run.start("TEST_PROVIDER", "fixture-model");
        for (var c : corpus().path("cases")) {
            var id = c.path("id").asText(); prepare(run, c);
            var bad = result(); bad.put("schemaOutcome", "FAILED");
            run.recordVariant(id, "walk-up", bad); run.recordVariant(id, "single-complete-prompt", result()); run.completeCase(id);
        }
        run.finish();
        assertThat(read(run.directory().resolve("manifest.json")).path("status").asText()).isEqualTo("SCHEMA_FAILURE");
    }

    @Test void playbackAndAbsentTransportCannotBecomeRealModelEvidence() throws Exception {
        for (String source : List.of("RECORDING_REPLAY", "")) {
            var run = create(); run.start("TEST_PROVIDER", "fixture-model");
            for (var c : corpus().path("cases")) {
                var id = c.path("id").asText(); prepare(run, c);
                var output = result(); output.putArray("transportAttempts");
                if (!source.isEmpty()) output.withArray("transportAttempts").addObject().put("source", source).put("provider", "TEST_PROVIDER");
                run.recordVariant(id, "walk-up", output); run.recordVariant(id, "single-complete-prompt", result()); run.completeCase(id);
            }
            run.finish();
            assertThat(read(run.directory().resolve("manifest.json")).path("status").asText()).isEqualTo("SCHEMA_FAILURE");
        }
    }

    @Test void artifactWriteFailureCannotPublishACompletePair() throws Exception {
        var run = create(); run.start("TEST_PROVIDER", "fixture-model"); var c = corpus().path("cases").get(0); prepare(run, c);
        Files.createDirectory(run.directory().resolve("sparse/walk-up.json"));
        assertThatThrownBy(() -> run.recordVariant("sparse", "walk-up", result())).isInstanceOf(java.io.IOException.class);
        run.abort(new java.io.IOException());
        assertThat(read(run.directory().resolve("manifest.json")).path("status").asText()).isEqualTo("INCOMPLETE_EXECUTION");
    }

    @Test void summaryWriteFailureCannotPublishCompleteRun() throws Exception {
        var run = populated(); Files.createDirectory(run.directory().resolve("summary.md"));
        assertThatThrownBy(run::finish).isInstanceOf(java.io.IOException.class);
        assertThat(read(run.directory().resolve("manifest.json")).path("status").asText()).isNotEqualTo("OUTPUTS_REQUIRE_HUMAN_REVIEW");
    }

    @Test void deletedOutputAfterRecordingCannotReachPendingReview() throws Exception {
        var run = populated(); Files.delete(run.directory().resolve("sparse/walk-up.json"));
        assertThatThrownBy(run::finish).isInstanceOf(java.io.IOException.class);
        assertThat(read(run.directory().resolve("manifest.json")).path("status").asText()).isEqualTo("INCOMPLETE_EXECUTION");
    }

    @Test void templateWriteFailureCannotPublishACompleteCase() throws Exception {
        var run = create(); run.start("TEST_PROVIDER", "fixture-model");
        prepare(run, corpus().path("cases").get(0));
        run.recordVariant("sparse", "walk-up", result()); run.recordVariant("sparse", "single-complete-prompt", result());
        Files.createDirectory(run.directory().resolve("sparse/human-review.json"));
        assertThatThrownBy(() -> run.completeCase("sparse")).isInstanceOf(java.io.IOException.class);
        run.abort(new java.io.IOException());
        assertThat(read(run.directory().resolve("manifest.json")).path("cases").get(0).path("outcome").asText()).isEqualTo("INCOMPLETE");
    }

    @Test void incompleteOrDifferentCaseInventoryIsRejected() throws Exception {
        var run = complete(); fillReviews(run);
        var corpus = corpus().deepCopy(); ((ObjectNode) corpus.path("cases").get(0)).put("id", "different-case");
        assertThatThrownBy(() -> ReformulationComparisonEvidence.validateReviews(run.directory(), json, corpus))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("corpus");
        var manifest = (ObjectNode) read(run.directory().resolve("manifest.json"));
        manifest.withArray("cases").remove(4); write(run.directory().resolve("manifest.json"), manifest);
        assertThatThrownBy(() -> ReformulationComparisonEvidence.validateReviews(run.directory(), json, corpus()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("inventory");
    }

    @Test void outputHashMismatchRejectsAnOtherwiseCompleteReview() throws Exception {
        var run = complete(); fillReviews(run);
        Files.writeString(run.directory().resolve("sparse/walk-up.json"), "{}");
        assertThatThrownBy(() -> ReformulationComparisonEvidence.validateReviews(run.directory(), json, corpus()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("hash");
    }

    @Test void reviewFromAnotherRunOrOtherOutputIsRejected() throws Exception {
        for (String field : List.of("runId", "walkUpOutputSha256", "singlePromptOutputSha256")) {
            var run = complete(); fillReviews(run); Path path = run.directory().resolve("sparse/human-review.json");
            var review = (ObjectNode) read(path); review.put(field, "wrong"); write(path, review);
            assertThatThrownBy(() -> ReformulationComparisonEvidence.validateReviews(run.directory(), json, corpus()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("binding");
        }
    }

    @Test void missingDimensionsOutOfRangeFractionalScoresAndUncitedPassagesAreRejected() throws Exception {
        for (String fault : List.of("dimension", "range", "fraction", "citation", "one-sided", "reviewer", "date", "pending")) {
            var run = complete(); fillReviews(run); Path path = run.directory().resolve("sparse/human-review.json");
            var review = (ObjectNode) read(path); var criterion = (ObjectNode) review.path("sourceConditionLoss");
            switch (fault) {
                case "dimension" -> review.remove("readability");
                case "range" -> criterion.put("walkUpScore0to4", 5);
                case "fraction" -> criterion.put("singlePromptScore0to4", 2.5);
                case "citation" -> ((ObjectNode) criterion.path("citations").get(0)).put("passage", "not in the recorded output");
                case "one-sided" -> criterion.withArray("citations").remove(1);
                case "reviewer" -> review.put("reviewer", " ");
                case "date" -> review.put("reviewedAt", "yesterday");
                case "pending" -> review.put("status", "NOT_REVIEWED");
            }
            write(path, review);
            assertThatThrownBy(() -> ReformulationComparisonEvidence.validateReviews(run.directory(), json, corpus()))
                    .as(fault).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void exactCompleteHumanReviewIsRecordedWithoutInventingAQualityVerdict() throws Exception {
        var run = complete(); fillReviews(run);
        ReformulationComparisonEvidence.validateReviews(run.directory(), json, corpus());
        var manifest = read(run.directory().resolve("manifest.json"));
        assertThat(manifest.path("humanQualityGate").asText()).isEqualTo("REVIEW_RECORDED");
        assertThat(read(run.directory().resolve("human-review-validation.json")).path("reviews")).hasSize(5);
        assertThat(Files.readString(run.directory().resolve("summary.md")))
                .contains("REVIEW_RECORDED", "Test human reviewer", "No semantic quality verdict");
        assertThat(Files.exists(run.directory().resolveSibling(run.directory().getFileName() + "-reviewed.zip"))).isTrue();
        // Revalidation after review edits must not preserve a stale successful review marker.
        Path review = run.directory().resolve("sparse/human-review.json");
        var invalid = (ObjectNode) read(review); invalid.put("reviewer", ""); write(review, invalid);
        assertThatThrownBy(() -> ReformulationComparisonEvidence.validateReviews(run.directory(), json, corpus()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(read(run.directory().resolve("manifest.json")).path("humanQualityGate").asText()).isEqualTo("NOT_REVIEWED");
        assertThat(Files.readString(run.directory().resolve("summary.md"))).contains("NOT_REVIEWED").doesNotContain("Test human reviewer");
        assertThat(Files.exists(run.directory().resolveSibling(run.directory().getFileName() + "-reviewed.zip"))).isFalse();
    }

    @Test void outsideFileSymlinksAreRejectedBeforeRetention() throws Exception {
        var run = complete(); fillReviews(run);
        Path outside = Files.writeString(root.resolve("outside-private.txt"), "EXTERNAL_PRIVATE_SENTINEL");
        Files.createSymbolicLink(run.directory().resolve("unbound-link.txt"), outside);
        assertThatThrownBy(() -> ReformulationComparisonEvidence.validateReviews(run.directory(), json, corpus()))
                .isInstanceOf(java.io.IOException.class);
        assertNoPublishedReview(run);
    }

    @Test void expectedArtifactsThroughASymlinkedCaseDirectoryAreRejected() throws Exception {
        var run = complete(); fillReviews(run);
        Path outside = root.resolve("outside-case");
        Files.move(run.directory().resolve("sparse"), outside);
        Files.createSymbolicLink(run.directory().resolve("sparse"), outside);
        assertThatThrownBy(() -> ReformulationComparisonEvidence.validateReviews(run.directory(), json, corpus()))
                .isInstanceOf(java.io.IOException.class);
        assertNoPublishedReview(run);
    }

    @Test void archiveNameCollisionCannotLeaveSuccessfulReviewArtifacts() throws Exception {
        var run = complete(); fillReviews(run);
        Files.createDirectory(run.directory().resolve("x"));
        Files.writeString(run.directory().resolve("x/y"), "unvalidated nested file");
        Files.writeString(run.directory().resolve("x\\y"), "unvalidated backslash file");
        assertThatThrownBy(() -> ReformulationComparisonEvidence.validateReviews(run.directory(), json, corpus()))
                .isInstanceOf(java.io.IOException.class);
        assertNoPublishedReview(run);
    }

    @Test void extraRegularFilesAreRejectedRatherThanRetainedAsEvidence() throws Exception {
        var run = complete(); fillReviews(run);
        Files.writeString(run.directory().resolve("unbound.txt"), "not validated evidence");
        assertThatThrownBy(() -> ReformulationComparisonEvidence.validateReviews(run.directory(), json, corpus()))
                .isInstanceOf(java.io.IOException.class);
        assertNoPublishedReview(run);
    }

    @Test void leafArtifactAndRunRootSymlinksAreRejected() throws Exception {
        var run = complete(); fillReviews(run);
        Path review = run.directory().resolve("sparse/human-review.json");
        Path outside = root.resolve("outside-review.json"); Files.move(review, outside); Files.createSymbolicLink(review, outside);
        assertThatThrownBy(() -> ReformulationComparisonEvidence.validateReviews(run.directory(), json, corpus()))
                .isInstanceOf(java.io.IOException.class);
        assertNoPublishedReview(run);
        var other = complete(); fillReviews(other);
        Path rootLink = root.resolve("linked-run"); Files.createSymbolicLink(rootLink, other.directory());
        assertThatThrownBy(() -> ReformulationComparisonEvidence.validateReviews(rootLink, json, corpus()))
                .isInstanceOf(java.io.IOException.class);
        assertNoPublishedReview(other);
    }

    @Test void archiveAndFinalManifestWriteFailuresLeaveNoPublishedSuccess() throws Exception {
        for (String failedArtifact : List.of("archive", "validation", "summary", "manifest")) {
            var run = complete(); fillReviews(run);
            assertThatThrownBy(() -> ReformulationComparisonEvidence.validateReviews(run.directory(), json, corpus(), (path, bytes) -> {
                String name = path.getFileName().toString();
                boolean fail = switch (failedArtifact) {
                    case "archive" -> name.endsWith("-reviewed.zip");
                    case "validation" -> name.equals("human-review-validation.json");
                    case "summary" -> name.equals("summary.md");
                    default -> name.equals("manifest.json");
                };
                // Simulate a storage failure even after publishing bytes; recovery must remove
                // success artifacts, including a manifest whose write partially succeeded.
                Files.write(path, bytes);
                if (fail) throw new java.io.IOException("Injected publication failure");
            })).as(failedArtifact).isInstanceOf(java.io.IOException.class);
            assertNoPublishedReview(run);
        }
    }

    @Test void retainedArchiveContainsExactlyTheValidatedArtifactInventory() throws Exception {
        var run = complete(); fillReviews(run);
        ReformulationComparisonEvidence.validateReviews(run.directory(), json, corpus());
        var expected = new java.util.HashSet<>(List.of("manifest.json", "corpus.json", "summary.md", "human-review-validation.json"));
        for (var c : corpus().path("cases")) for (String artifact : List.of("input.json", "walk-up.json", "single-complete-prompt.json", "human-review.json"))
            expected.add(c.path("id").asText() + "/" + artifact);
        Path archive = run.directory().resolveSibling(run.directory().getFileName() + "-reviewed.zip");
        try (var zip = new java.util.zip.ZipFile(archive.toFile())) {
            assertThat(zip.stream().map(java.util.zip.ZipEntry::getName).toList()).containsExactlyInAnyOrderElementsOf(expected);
            for (var entry : zip.stream().toList()) try (var stream = zip.getInputStream(entry)) {
                assertThat(stream.readAllBytes()).as(entry.getName()).isEqualTo(Files.readAllBytes(run.directory().resolve(entry.getName())));
            }
        }
    }

    private void assertNoPublishedReview(ReformulationComparisonEvidence run) throws Exception {
        var manifest = read(run.directory().resolve("manifest.json"));
        assertThat(manifest.path("humanQualityGate").asText()).isEqualTo("NOT_REVIEWED");
        assertThat(manifest.has("reviewValidationSha256")).isFalse();
        assertThat(Files.readString(run.directory().resolve("summary.md"))).doesNotContain("REVIEW_RECORDED");
        assertThat(Files.exists(run.directory().resolve("human-review-validation.json"))).isFalse();
        assertThat(Files.exists(run.directory().resolveSibling(run.directory().getFileName() + "-reviewed.zip"))).isFalse();
    }

    private ReformulationComparisonEvidence create() throws Exception {
        return ReformulationComparisonEvidence.create(root, json, "a".repeat(40), corpus());
    }
    private ReformulationComparisonEvidence populated() throws Exception {
        var run = create(); run.start("TEST_PROVIDER", "fixture-model");
        for (var c : corpus().path("cases")) {
            String id = c.path("id").asText(); prepare(run, c);
            run.recordVariant(id, "walk-up", result()); run.recordVariant(id, "single-complete-prompt", result()); run.completeCase(id);
        }
        return run;
    }
    private ReformulationComparisonEvidence complete() throws Exception { var run = populated(); run.finish(); return run; }
    private void prepare(ReformulationComparisonEvidence run, JsonNode c) throws Exception {
        String id = c.path("id").asText(); run.beginCase(id);
        var input = json.createObjectNode().put("originalText", c.path("source").asText());
        input.putObject("frozenContext").put("catalogue", "fixture context");
        run.prepareCase(id, input);
    }
    private ObjectNode result() {
        var output = json.createObjectNode().put("schemaOutcome", "VALIDATED").put("durationMillis", 7)
                .put("actualHttpAttempts", 1).put("actualTransportRetries", 0);
        output.putObject("output").put("summary", "Recorded fixture passage; not real model quality evidence.");
        output.putArray("transportAttempts").addObject().put("source", "HTTP").put("provider", "TEST_PROVIDER")
                .put("retryIndex", 0).put("outcome", "RESPONSE").put("statusCode", 200);
        return output;
    }
    private void fillReviews(ReformulationComparisonEvidence run) throws Exception {
        for (var c : corpus().path("cases")) {
            Path path = run.directory().resolve(c.path("id").asText()).resolve("human-review.json");
            var review = (ObjectNode) read(path); review.put("status", "REVIEWED").put("reviewer", "Test human reviewer")
                    .put("reviewedAt", Instant.now().toString());
            for (String dimension : ReformulationComparisonEvidence.DIMENSIONS) {
                var criterion = (ObjectNode) review.path(dimension);
                criterion.put("walkUpScore0to4", 2).put("singlePromptScore0to4", 3).put("evidence", "Authored validation fixture only");
                var citations = criterion.putArray("citations");
                for (String variant : List.of("walk-up", "single-complete-prompt"))
                    citations.addObject().put("variant", variant).put("passage", "Recorded fixture passage");
            }
            write(path, review);
        }
    }
    private JsonNode corpus() throws Exception {
        try (var stream = getClass().getResourceAsStream("/scenarios/reformulation-model-comparison.json")) { return json.readTree(stream); }
    }
    private JsonNode read(Path path) throws Exception { return json.readTree(Files.readString(path)); }
    private void write(Path path, JsonNode value) throws Exception { Files.writeString(path, value.toPrettyString()); }
}
