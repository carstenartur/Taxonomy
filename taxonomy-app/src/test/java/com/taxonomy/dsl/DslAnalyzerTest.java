package com.taxonomy.dsl;

import com.taxonomy.shared.config.CsvKeywordAnalyzer;
import com.taxonomy.shared.config.DslAnalyzer;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for custom Lucene analyzers used by the DSL commit history search.
 */
class DslAnalyzerTest {

    // ── DslAnalyzer ──────────────────────────────────────────────────

    @Test
    void dslAnalyzerSplitsOnWhitespace() throws IOException {
        List<String> tokens = analyze(new DslAnalyzer(), "CP-1023 BP-1327 REQ-001");
        assertThat(tokens).containsExactly("cp-1023", "bp-1327", "req-001");
    }

    @Test
    void dslAnalyzerLowercasesTokens() throws IOException {
        List<String> tokens = analyze(new DslAnalyzer(), "STRUCT:element REL:REALIZES DOM:Capability");
        assertThat(tokens).containsExactly("struct:element", "rel:realizes", "dom:capability");
    }

    @Test
    void dslAnalyzerPreservesPrefixes() throws IOException {
        List<String> tokens = analyze(new DslAnalyzer(), "STRUCT:relation REL:SUPPORTS DOM:Process");
        assertThat(tokens).allMatch(t -> t.contains(":"));
    }

    @Test
    void dslAnalyzerHandlesEmptyInput() throws IOException {
        List<String> tokens = analyze(new DslAnalyzer(), "");
        assertThat(tokens).isEmpty();
    }

    @Test
    void dslAnalyzerHandlesMixedTokens() throws IOException {
        List<String> tokens = analyze(new DslAnalyzer(),
                "CP-1023 STRUCT:element REL:REALIZES CR-1047 DOM:Capability");
        assertThat(tokens).hasSize(5);
        assertThat(tokens).contains("cp-1023", "struct:element", "rel:realizes", "cr-1047", "dom:capability");
    }

    // ── CsvKeywordAnalyzer ──────────────────────────────────────────

    @Test
    void csvAnalyzerSplitsOnCommas() throws IOException {
        List<String> tokens = analyze(new CsvKeywordAnalyzer(), "CP-1023,BP-1327,CR-1047");
        assertThat(tokens).containsExactly("cp-1023", "bp-1327", "cr-1047");
    }

    @Test
    void csvAnalyzerSplitsOnSemicolons() throws IOException {
        List<String> tokens = analyze(new CsvKeywordAnalyzer(),
                "CP-1023 REALIZES CR-1047;CR-1047 SUPPORTS BP-1327");
        assertThat(tokens).containsExactly("cp-1023 realizes cr-1047", "cr-1047 supports bp-1327");
    }

    @Test
    void csvAnalyzerHandlesMixedDelimiters() throws IOException {
        List<String> tokens = analyze(new CsvKeywordAnalyzer(), "CP-1023,BP-1327;CR-1047");
        assertThat(tokens).containsExactly("cp-1023", "bp-1327", "cr-1047");
    }

    @Test
    void csvAnalyzerHandlesEmptyInput() throws IOException {
        List<String> tokens = analyze(new CsvKeywordAnalyzer(), "");
        assertThat(tokens).isEmpty();
    }

    @Test
    void csvAnalyzerLowercases() throws IOException {
        List<String> tokens = analyze(new CsvKeywordAnalyzer(), "CP-1023,REQ-001");
        assertThat(tokens).containsExactly("cp-1023", "req-001");
    }

    // ── Helper ──────────────────────────────────────────────────────

    private List<String> analyze(Analyzer analyzer, String text) throws IOException {
        List<String> tokens = new ArrayList<>();
        try (TokenStream stream = analyzer.tokenStream("test", text)) {
            CharTermAttribute attr = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                tokens.add(attr.toString());
            }
            stream.end();
        }
        return tokens;
    }
}
