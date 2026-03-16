package com.taxonomy.shared.config;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.pattern.PatternTokenizer;

import java.util.regex.Pattern;
import com.taxonomy.architecture.model.ArchitectureCommitIndex;

/**
 * Custom Lucene {@link Analyzer} for comma/semicolon-separated keyword fields.
 *
 * <p>Used for {@code affectedElementIds} (comma-separated) and
 * {@code affectedRelationIds} (semicolon-separated) fields in
 * {@link com.taxonomy.model.ArchitectureCommitIndex}.
 *
 * <p>Splits on commas and semicolons, trims whitespace, and lowercases
 * so that individual IDs like {@code "CP-1023"} become searchable tokens.
 */
public class CsvKeywordAnalyzer extends Analyzer {

    /** Split on commas or semicolons, optionally surrounded by whitespace. */
    private static final Pattern DELIMITER = Pattern.compile("[,;]\\s*");

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
        Tokenizer tokenizer = new PatternTokenizer(DELIMITER, -1);
        TokenStream filter = new LowerCaseFilter(tokenizer);
        return new TokenStreamComponents(tokenizer, filter);
    }
}
