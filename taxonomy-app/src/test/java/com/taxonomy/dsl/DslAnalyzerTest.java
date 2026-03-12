package com.taxonomy.dsl;

import com.taxonomy.config.CsvKeywordAnalyzer;
import com.taxonomy.config.DslAnalyzer;
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
        List<String> tokens = analyze(new DslAnalyzer(), "CP-1001 BP-1040 REQ-001");
        assertThat(tokens).containsExactly("cp-1001", "bp-1040", "req-001");
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
                "CP-1001 STRUCT:element REL:REALIZES CR-2001 DOM:Capability");
        assertThat(tokens).hasSize(5);
        assertThat(tokens).contains("cp-1001", "struct:element", "rel:realizes", "cr-2001", "dom:capability");
    }

    // ── CsvKeywordAnalyzer ──────────────────────────────────────────

    @Test
    void csvAnalyzerSplitsOnCommas() throws IOException {
        List<String> tokens = analyze(new CsvKeywordAnalyzer(), "CP-1001,BP-1040,CR-2001");
        assertThat(tokens).containsExactly("cp-1001", "bp-1040", "cr-2001");
    }

    @Test
    void csvAnalyzerSplitsOnSemicolons() throws IOException {
        List<String> tokens = analyze(new CsvKeywordAnalyzer(),
                "CP-1001 REALIZES CR-2001;CR-2001 SUPPORTS BP-1040");
        assertThat(tokens).containsExactly("cp-1001 realizes cr-2001", "cr-2001 supports bp-1040");
    }

    @Test
    void csvAnalyzerHandlesMixedDelimiters() throws IOException {
        List<String> tokens = analyze(new CsvKeywordAnalyzer(), "CP-1001,BP-1040;CR-2001");
        assertThat(tokens).containsExactly("cp-1001", "bp-1040", "cr-2001");
    }

    @Test
    void csvAnalyzerHandlesEmptyInput() throws IOException {
        List<String> tokens = analyze(new CsvKeywordAnalyzer(), "");
        assertThat(tokens).isEmpty();
    }

    @Test
    void csvAnalyzerLowercases() throws IOException {
        List<String> tokens = analyze(new CsvKeywordAnalyzer(), "CP-1001,REQ-001");
        assertThat(tokens).containsExactly("cp-1001", "req-001");
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
