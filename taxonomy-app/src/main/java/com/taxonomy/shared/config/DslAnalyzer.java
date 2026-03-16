package com.taxonomy.shared.config;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import com.taxonomy.dsl.parser.DslTokenizer;

/**
 * Custom Lucene {@link Analyzer} for DSL-tokenized text.
 *
 * <p>The {@link com.taxonomy.dsl.parser.DslTokenizer} produces space-separated
 * tokens with category prefixes ({@code STRUCT:element}, {@code REL:REALIZES},
 * {@code DOM:Capability}) and raw identifiers ({@code CP-1023}). This analyzer:
 * <ol>
 *   <li>Splits on whitespace (tokens are already pre-tokenized)</li>
 *   <li>Lowercases for case-insensitive matching</li>
 * </ol>
 *
 * <p>Prefixed tokens remain intact so that queries like {@code "rel:realizes"}
 * perform precise faceted searches.
 */
public class DslAnalyzer extends Analyzer {

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
        Tokenizer tokenizer = new WhitespaceTokenizer();
        TokenStream filter = new LowerCaseFilter(tokenizer);
        return new TokenStreamComponents(tokenizer, filter);
    }
}
