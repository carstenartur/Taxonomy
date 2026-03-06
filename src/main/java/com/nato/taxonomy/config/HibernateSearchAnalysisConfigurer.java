package com.nato.taxonomy.config;

import org.apache.lucene.analysis.de.GermanAnalyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.hibernate.search.backend.lucene.analysis.LuceneAnalysisConfigurationContext;
import org.hibernate.search.backend.lucene.analysis.LuceneAnalysisConfigurer;
import org.springframework.stereotype.Component;

/**
 * Hibernate Search {@link LuceneAnalysisConfigurer} that registers English and German
 * Lucene analyzers for use with {@code @FullTextField(analyzer = "english")} /
 * {@code @FullTextField(analyzer = "german")} field annotations.
 *
 * <p>Referenced in {@code application.properties} as
 * {@code hibernate.search.backend.analysis.configurer=bean:hibernateSearchAnalysisConfigurer}.
 */
@Component("hibernateSearchAnalysisConfigurer")
public class HibernateSearchAnalysisConfigurer implements LuceneAnalysisConfigurer {

    @Override
    public void configure(LuceneAnalysisConfigurationContext context) {
        context.analyzer("english").instance(new EnglishAnalyzer());
        context.analyzer("german").instance(new GermanAnalyzer());
        context.analyzer("standard").instance(new StandardAnalyzer());
    }
}
