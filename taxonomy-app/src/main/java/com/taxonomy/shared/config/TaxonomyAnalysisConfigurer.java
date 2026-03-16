package com.taxonomy.shared.config;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.de.GermanAnalyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;

import java.util.LinkedHashMap;
import java.util.Map;
import com.taxonomy.catalog.service.SearchService;

/**
 * Provides Lucene analyzers for English and German full-text fields.
 * Used by {@link com.taxonomy.catalog.service.SearchService} to configure per-field analysis.
 */
public class TaxonomyAnalysisConfigurer {

    public static final String ANALYZER_ENGLISH = "english";
    public static final String ANALYZER_GERMAN  = "german";

    /**
     * Build a per-field {@link Analyzer} that applies:
     * <ul>
     *   <li>{@link EnglishAnalyzer} for fields whose name ends with "En"</li>
     *   <li>{@link GermanAnalyzer} for fields whose name ends with "De"</li>
     *   <li>{@link StandardAnalyzer} for all other fields</li>
     * </ul>
     *
     * @param enFields field names that should use the English analyzer
     * @param deFields field names that should use the German analyzer
     * @return configured {@link PerFieldAnalyzerWrapper}
     */
    public static Analyzer buildPerFieldAnalyzer(String[] enFields, String[] deFields) {
        Map<String, Analyzer> fieldAnalyzers = new LinkedHashMap<>();
        for (String f : enFields) {
            fieldAnalyzers.put(f, new EnglishAnalyzer());
        }
        for (String f : deFields) {
            fieldAnalyzers.put(f, new GermanAnalyzer());
        }
        return new PerFieldAnalyzerWrapper(new StandardAnalyzer(), fieldAnalyzers);
    }
}
