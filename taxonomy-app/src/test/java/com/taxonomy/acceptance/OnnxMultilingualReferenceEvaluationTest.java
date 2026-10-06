package com.taxonomy.acceptance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnnxMultilingualReferenceEvaluationTest {
    @TempDir Path temporary;

    @Test
    void failedPreflightReplacesStaleSuccessAndRetainsEveryUnexecutedCase() throws Exception {
        Path output = Files.createDirectory(temporary.resolve("reports"));
        Files.writeString(output.resolve("report.json"), "STALE SUCCESS");
        assertThatThrownBy(() -> OnnxMultilingualReferenceEvaluation.verify(
                URI.create("http://127.0.0.1:1"), "AUTHORIZATION_MUST_NOT_APPEAR",
                temporary.resolve("missing-model"), temporary.resolve("missing.jar"), output))
                .isInstanceOf(java.io.IOException.class);
        String text = Files.readString(output.resolve("report.json"));
        var report = new tools.jackson.databind.ObjectMapper().readTree(text);
        assertThat(report.path("status").stringValue()).isEqualTo("ERROR");
        assertThat(report.path("evidenceKind").stringValue()).isEqualTo("LOCAL_ONNX_MULTILINGUAL_EVALUATION_ATTEMPT");
        assertThat(report.path("cases").size()).isEqualTo(16);
        for (var row : report.path("cases")) {
            assertThat(row.path("status").stringValue()).isEqualTo("NOT_RUN");
            assertThat(row.path("measurement").isNull()).isTrue();
        }
        assertThat(text).doesNotContain("STALE SUCCESS", "AUTHORIZATION_MUST_NOT_APPEAR");
    }

    @Test
    void sixteenPredeclaredCasesRetainTheSixUnchangedOriginalAnchors() throws Exception {
        var cases = OnnxMultilingualReferenceEvaluation.load();
        assertThat(cases).hasSize(16);
        Map<String, Long> counts = cases.stream().collect(Collectors.groupingBy(
                OnnxMultilingualReferenceEvaluation.Case::kind, Collectors.counting()));
        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of(
                "ANCHOR", 6L, "PARAPHRASE", 6L, "DISTRACTOR", 2L, "AMBIGUOUS", 2L));
        assertThat(cases.stream().filter(test -> test.kind().equals("ANCHOR")))
                .allSatisfy(test -> {
                    var original = OnnxReferenceCases.load().stream().filter(anchor -> anchor.id().equals(test.id()))
                            .findFirst().orElseThrow();
                    assertThat(test.query()).isEqualTo(original.query());
                    assertThat(test.required()).isEqualTo(original.required());
                    assertThat(test.language()).isEqualTo(original.language());
                });
    }

    @Test
    void difficultParaphrasesAndAmbiguitiesAreRetainedAsObservations() throws Exception {
        var cases = OnnxMultilingualReferenceEvaluation.load();
        var payroll = cases.stream().filter(test -> test.id().equals("payroll-de-paraphrase")).findFirst().orElseThrow();
        assertThat(payroll.query()).contains("Nettolohn", "Steuerabzug", "Fehlzeiten");
        assertThat(payroll.kind()).isEqualTo("PARAPHRASE");
        assertThat(payroll.required()).containsExactly("UA-1604");
        assertThat(cases.stream().filter(test -> test.kind().equals("AMBIGUOUS")))
                .allSatisfy(test -> assertThat(test.required()).containsExactlyInAnyOrder("UA-1583", "UA-1222"));
        assertThat(cases.stream().filter(test -> test.kind().equals("DISTRACTOR")))
                .allSatisfy(test -> {
                    assertThat(test.required()).isEmpty();
                    assertThat(test.excluded()).containsExactlyInAnyOrder("UA-1583", "UA-1222", "UA-1604");
                });
    }
}
