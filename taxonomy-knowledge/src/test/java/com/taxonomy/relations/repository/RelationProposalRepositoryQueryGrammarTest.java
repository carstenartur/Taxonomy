package com.taxonomy.relations.repository;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class RelationProposalRepositoryQueryGrammarTest {

    @Test
    void primaryScopeStartsWithWhitespaceForTextBlockComposition() {
        assertThat(RelationProposalRepository.PRIMARY_SCOPE)
                .startsWith(" ")
                .contains("p.repositoryId");
    }

    @Test
    void declaredQueriesKeepTenantPredicateSeparatedFromJpqlKeywords() {
        Arrays.stream(RelationProposalRepository.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(Query.class))
                .filter(annotation -> annotation != null)
                .map(Query::value)
                .forEach(query -> assertThat(query)
                        .doesNotContain("WHEREp.", "ANDp."));
    }

    @Test
    void primaryCompatibilityFindAllHasStableOrder() throws Exception {
        Query query = RelationProposalRepository.class
                .getDeclaredMethod("findAll")
                .getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(query.value()).contains("ORDER BY p.id");
    }
}
