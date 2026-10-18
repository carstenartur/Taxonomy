package com.taxonomy.portfolio.service;

import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.AnalysisScoreSemantics;
import com.taxonomy.dto.TaxonomyNodeDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class PortfolioSelectionAnalysisReadTest {
    private final PortfolioJsonCodec codec = new PortfolioJsonCodec(JsonMapper.builder().build());

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"scores\":{\"A\":40,\"B\":20}}",
            "{\"scores\":{\"A\":99},\"rawScores\":{\"A\":30}}",
            "{\"rawScores\":{\"A\":30},\"scores\":{\"A\":99}}",
            "{\"scores\":{\"A\":99},\"rawScores\":{}}",
            "{\"rawScores\":{},\"scores\":{\"A\":99}}",
            "{\"scores\":{\"A\":40},\"rawScores\":null}",
            "{\"rawScores\":null,\"scores\":{\"A\":40}}",
            "{\"rawScores\":{\" F \" :30,\"P\":80},\"tree\":[{\"code\":\"F\",\"children\":[{\"code\":\"P\",\"analysisRole\":\"PRODUCT\"}]}]}",
            "{\"rawScores\":{\"F\":30,\"F2\":70,\"P\":80},\"tree\":[{\"code\":\"F\",\"children\":[{\"code\":\"P\",\"parentCode\":\"F2\",\"analysisRole\":\" product \"}]}]}",
            "{\"rawScores\":{\"F\":30,\"P\":80},\"scoreSemanticsContext\":{\"F\":{\"analysisRole\":\"CATEGORY\"},\"P\":{\"parentCode\":\"F\",\"analysisRole\":\"PRODUCT\"}}}",
            "{\"rawScores\":{\"P\":80},\"tree\":[{\"code\":\"P\",\"analysisRole\":\"PRODUCT\"}],\"warnings\":null}",
            "{\"scores\":{},\"warnings\":[\"first warning\",\"second warning\"]}"
    })
    void selectionReadUsesTheSameLegacyAndProductScoreSemanticsAsFullDeserialization(String json) {
        assertSameScoreEvidence(json);
    }

    @Test
    void retainsCompleteHistoricalTreeLessScoreEnvelopesRegardlessOfPropertyOrder() {
        var analysis = new AnalysisResult();
        analysis.setRawScores(Map.of("F", 30d, "P", 80d));
        analysis.setScoreSemanticsContext(Map.of("F", new AnalysisScoreSemantics.NodeContext(null, "CATEGORY"),
                "P", new AnalysisScoreSemantics.NodeContext("F", "PRODUCT")));
        assertThat(analysis.getScores()).containsEntry("P", 24d);
        var mapper = JsonMapper.builder().build();
        var stored = mapper.readTree(codec.write(analysis)).deepCopy();
        // Historical envelopes may have derived product context but no retained tree/context.
        // Preserve precisely what the regular deserializer accepts, including its cache validation.
        var object = (tools.jackson.databind.node.ObjectNode) stored;
        object.remove("tree"); object.remove("scoreSemanticsContext");
        assertSameScoreEvidence(object.toString());
        var reversed = mapper.createObjectNode();
        var names = new java.util.ArrayList<String>(); object.propertyNames().forEach(names::add);
        java.util.Collections.reverse(names);
        for (String name : names) reversed.set(name, object.get(name));
        assertSameScoreEvidence(reversed.toString());
    }

    @Test
    void skipsDescriptionsRelationsAndAnalysisGraphsButKeepsHierarchyForProductWeighting() {
        var product = new TaxonomyNodeDto(); product.setCode("P"); product.setAnalysisRole("PRODUCT");
        product.setDescriptionEn("Product documentation ".repeat(50_000));
        var family = new TaxonomyNodeDto(); family.setCode("F"); family.setChildren(List.of(product));
        family.setDescriptionEn("Family documentation ".repeat(50_000));
        var analysis = new AnalysisResult(Map.of("F", 25d, "P", 80d), List.of(family));
        analysis.setReasons(Map.of("F", "Full decision reasoning ".repeat(50_000)));
        analysis.setArchitectureView(new com.taxonomy.dto.RequirementArchitectureView());
        analysis.setWarnings(List.of("Provider retained an unresolved branch"));
        String json = codec.write(analysis);
        var reduced = codec.readSelectionAnalysis(json);
        assertThat(reduced.getScores()).containsEntry("F", 25d).containsEntry("P", 20d);
        assertThat(reduced.getWarnings()).containsExactlyElementsOf(analysis.getWarnings());
        assertThat(reduced.getReasons()).isEmpty();
        assertThat(reduced.getArchitectureView()).isNull();
        assertThat(reduced.getTree()).singleElement().satisfies(node -> {
            assertThat(node.getDescriptionEn()).isNull();
            assertThat(node.getChildren()).singleElement().satisfies(child -> {
                assertThat(child.getCode()).isEqualTo("P");
                assertThat(child.getAnalysisRole()).isEqualTo("PRODUCT");
                assertThat(child.getDescriptionEn()).isNull();
            });
        });
        assertSameScoreEvidence(json);
    }

    @Test
    void invalidFrozenHierarchyCannotBecomeValidInTheSelectionRead() {
        String json = """
                {"rawScores":{"A":40},"tree":[{"code":"A"},{"code":"A"}]}
                """;
        assertThatThrownBy(() -> codec.read(json, AnalysisResult.class).getScores())
                .isInstanceOf(PortfolioException.class).hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause().hasMessageContaining("duplicate node code A");
        assertThatThrownBy(() -> codec.readSelectionAnalysis(json).getScores())
                .isInstanceOf(PortfolioException.class).hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause().hasMessageContaining("duplicate node code A");
        assertThatThrownBy(() -> codec.readSelectionAnalysis(new StringReader(json)).getScores())
                .isInstanceOf(PortfolioException.class).hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause().hasMessageContaining("duplicate node code A");
    }

    @Test
    void absentAnalysisStaysUnusable() {
        assertThat(codec.readSelectionAnalysis((String) null)).isNull();
        assertThat(codec.readSelectionAnalysis((Reader) null)).isNull();
        assertThat(codec.readSelectionAnalysis(" ")).isNull();
        assertThat(codec.readSelectionAnalysis("null")).isNull();
        assertThat(codec.readSelectionAnalysis(new StringReader("null"))).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \t\n\r", "\u2003\u2028\u001c", "\u001e\f"})
    void readerPreservesTheStringIsBlankContractIncludingUnicode(String whitespace) {
        assertThat(whitespace.isBlank()).isTrue();
        assertThat(codec.readSelectionAnalysis(whitespace)).isNull();
        assertThat(codec.readSelectionAnalysis(new StringReader(whitespace))).isNull();
        assertThat(codec.readSelectionAnalysis(new StringReader(" ".repeat(16_000) + whitespace))).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u2003", "\u2028", "\u001c", "\f", "\u00a0", "\ufeff"})
    void nonJsonWhitespaceCannotBeStrippedToMakeAnInvalidPayloadValid(String prefix) {
        String json = prefix + "{\"scores\":{\"A\":40}}";
        assertThatThrownBy(() -> codec.readSelectionAnalysis(json)).isInstanceOf(PortfolioException.class);
        assertThatThrownBy(() -> codec.readSelectionAnalysis(new StringReader(json)))
                .isInstanceOfSatisfying(PortfolioException.class,
                        failure -> assertThat(failure.getKind()).isEqualTo(PortfolioException.Kind.ANALYSIS_FAILED));
    }

    @Test
    void readerConsumesLateWarningsAndScoreContextWithoutClosingItsBorrowedSource() throws IOException {
        String json = """
                {"scores":{"P":99},"reasons":{"P":"%s"},"rawScores":{"F":25,"P":80},
                 "warnings":["late warning"],"scoreSemanticsContext":{
                   "F":{"analysisRole":"CATEGORY"},"P":{"parentCode":"F","analysisRole":"PRODUCT"}}}
                """.formatted("Retained full evidence. ".repeat(10_000));
        try (var input = new ObservedReader(json)) {
            var selected = codec.readSelectionAnalysis(input);
            assertThat(selected.getScores()).containsEntry("P", 20d);
            assertThat(selected.getWarnings()).containsExactly("late warning");
            assertThat(input.position).isEqualTo(json.length());
            assertThat(input.closed).isFalse();
        }
        assertSameScoreEvidence(json);
    }

    @Test
    void earlyScoresCannotHideAMalformedRemainder() {
        String json = "{\"scores\":{\"A\":40},\"reasons\":{\"A\":\"" + "Retained evidence. ".repeat(10_000);
        try (var input = new ObservedReader(json)) {
            assertThatThrownBy(() -> codec.readSelectionAnalysis(input))
                    .isInstanceOfSatisfying(PortfolioException.class,
                            failure -> assertThat(failure.getKind()).isEqualTo(PortfolioException.Kind.ANALYSIS_FAILED));
            assertThat(input.closed).isFalse();
        }
    }

    @Test
    void readerIoFailureIsReportedAndTheCallerStillOwnsTheSource() {
        var failure = new IOException("CLOB read failed");
        var input = new Reader() {
            boolean closed;
            @Override public int read(char[] buffer, int offset, int length) throws IOException { throw failure; }
            @Override public void close() { closed = true; }
        };
        assertThatThrownBy(() -> codec.readSelectionAnalysis(input)).isInstanceOf(PortfolioException.class)
                .hasCause(failure);
        assertThat(input.closed).isFalse();
    }

    private void assertSameScoreEvidence(String json) {
        var full = codec.read(json, AnalysisResult.class);
        for (var selection : List.of(codec.readSelectionAnalysis(json),
                codec.readSelectionAnalysis(new StringReader(json)))) {
            assertThat(selection.getScores()).containsExactlyEntriesOf(full.getScores());
            assertThat(selection.getRawScores()).containsExactlyEntriesOf(full.getRawScores());
            assertThat(selection.getWarnings()).isEqualTo(full.getWarnings());
            assertThat(selection.getScoreDetails()).containsExactlyEntriesOf(full.getScoreDetails());
            assertThat(selection.getEffectiveScores()).containsExactlyEntriesOf(full.getEffectiveScores());
            assertThat(selection.getProductSuitabilityScores()).containsExactlyEntriesOf(full.getProductSuitabilityScores());
            assertThat(selection.getScoreSemanticsContext()).containsExactlyEntriesOf(full.getScoreSemanticsContext());
            assertThat(selection.getScoreSemanticsVersion()).isEqualTo(full.getScoreSemanticsVersion());
            assertThat(selection.getScoreSemanticsFingerprintSha256()).isEqualTo(full.getScoreSemanticsFingerprintSha256());
            assertThat(selection.getScoreSemanticsWarnings()).isEqualTo(full.getScoreSemanticsWarnings());
        }
    }

    private static final class ObservedReader extends Reader {
        private final StringReader input;
        private int position;
        private boolean closed;
        private ObservedReader(String json) { input = new StringReader(json); }
        @Override public int read(char[] buffer, int offset, int length) throws IOException {
            int count = input.read(buffer, offset, Math.min(length, 17));
            if (count > 0) position += count;
            return count;
        }
        @Override public void close() { closed = true; input.close(); }
    }
}
