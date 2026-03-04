package com.nato.taxonomy.config;

import org.apache.lucene.analysis.de.GermanAnalyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;

/**
 * Provides standard Lucene analyzers for English and German text.
 * Used by {@link com.nato.taxonomy.service.SearchService} to configure per-field analysis.
 */
public class TaxonomyAnalysisConfigurer {

    /** Returns a Lucene {@link EnglishAnalyzer} for full-text indexing and searching of English content. */
    public static EnglishAnalyzer englishAnalyzer() {
        return new EnglishAnalyzer();
    }

    /** Returns a Lucene {@link GermanAnalyzer} for full-text indexing and searching of German content. */
    public static GermanAnalyzer germanAnalyzer() {
        return new GermanAnalyzer();
    }
}
