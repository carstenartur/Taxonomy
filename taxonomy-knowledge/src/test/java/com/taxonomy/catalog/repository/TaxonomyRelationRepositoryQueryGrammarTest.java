package com.taxonomy.catalog.repository;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class TaxonomyRelationRepositoryQueryGrammarTest {

    @Test
    void primaryScopeStartsWithWhitespaceForTextBlockComposition() {
        assertThat(TaxonomyRelationRepository.PRIMARY_SCOPE)
                .startsWith(" ")
                .contains("r.repositoryId");
    }

    @Test
    void declaredQueriesKeepTenantPredicateSeparatedFromJpqlKeywords() {
        Arrays.stream(TaxonomyRelationRepository.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(Query.class))
                .filter(annotation -> annotation != null)
                .map(Query::value)
                .forEach(query -> assertThat(query)
                        .doesNotContain("WHEREr.", "ANDr."));
    }
}
