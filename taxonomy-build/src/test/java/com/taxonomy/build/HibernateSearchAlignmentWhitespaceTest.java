package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class HibernateSearchAlignmentWhitespaceTest {

    @Test
    void policyUsesTheValidatedNormalizedVersionParameters(@TempDir Path root)
            throws Exception {
        Path tree = root.resolve("dependencies.txt");
        Files.writeString(tree, """
                +- org.hibernate.search:hibernate-search-mapper-orm:jar:8.4.0.Final:compile
                +- org.hibernate.orm:hibernate-core:jar:7.4.2.Final:compile
                \\- org.apache.lucene:lucene-core:jar:9.12.3:compile
                """, StandardCharsets.UTF_8);

        var evaluation = new HibernateSearchAlignmentPolicy().evaluate(
                tree,
                " 8.4.0.Final ",
                " 7.4. ",
                " 9.12.3 ");

        assertThat(evaluation.passed()).isTrue();
        assertThat(evaluation.failures()).isEmpty();
        assertThat(evaluation.report()).contains("Result: PASS");
    }
}
