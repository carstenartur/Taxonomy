package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class HibernateSearchAlignmentPolicyTest {

    private static final String SEARCH_VERSION = "8.4.0.Final";
    private static final String ORM_PREFIX = "7.4.";
    private static final String LUCENE_VERSION = "9.12.3";

    private final HibernateSearchAlignmentPolicy policy =
            new HibernateSearchAlignmentPolicy();

    @Test
    void acceptsOneAlignedResolvedDependencyFamily(@TempDir Path root)
            throws Exception {
        Path tree = alignedTree(root);

        HibernateSearchAlignmentPolicy.Evaluation evaluation = policy.evaluate(
                tree, SEARCH_VERSION, ORM_PREFIX, LUCENE_VERSION);

        assertThat(evaluation.passed()).isTrue();
        assertThat(evaluation.failures()).isEmpty();
        assertThat(evaluation.report())
                .contains("hibernate-search-mapper-orm = 8.4.0.Final")
                .contains("hibernate-core = 7.4.2.Final")
                .contains("lucene-core = 9.12.3")
                .contains("Result: PASS");
    }

    @Test
    void normalizesWhitespaceAroundConfiguredVersions(@TempDir Path root)
            throws Exception {
        HibernateSearchAlignmentPolicy.Evaluation evaluation = policy.evaluate(
                alignedTree(root),
                "  " + SEARCH_VERSION + "\n",
                "\t" + ORM_PREFIX + "  ",
                " " + LUCENE_VERSION + " ");

        assertThat(evaluation.passed()).isTrue();
        assertThat(evaluation.failures()).isEmpty();
        assertThat(evaluation.report()).contains("Result: PASS");
    }

    @Test
    void reportsEveryMisalignedFamilyInsteadOfStoppingAtTheFirst(@TempDir Path root)
            throws Exception {
        Path tree = write(root, """
                +- org.hibernate.search:hibernate-search-mapper-orm:jar:8.4.0.Final:compile
                +- org.hibernate.search:hibernate-search-mapper-orm:jar:8.3.1.Final:test
                +- org.hibernate.orm:hibernate-core:jar:7.3.9.Final:compile
                \\- org.apache.lucene:lucene-core:jar:9.12.2:compile
                """);

        HibernateSearchAlignmentPolicy.Evaluation evaluation = policy.evaluate(
                tree, SEARCH_VERSION, ORM_PREFIX, LUCENE_VERSION);

        assertThat(evaluation.passed()).isFalse();
        assertThat(evaluation.failures())
                .hasSize(3)
                .anySatisfy(failure -> assertThat(failure)
                        .contains("hibernate-search-mapper-orm")
                        .contains("8.3.1.Final"))
                .anySatisfy(failure -> assertThat(failure)
                        .contains("hibernate-core")
                        .contains("7.4.x"))
                .anySatisfy(failure -> assertThat(failure)
                        .contains("lucene-core")
                        .contains("9.12.3"));
        assertThat(evaluation.report()).contains("Result: FAIL");
    }

    @Test
    void failsClosedWhenRequiredFamiliesAreMissing(@TempDir Path root)
            throws Exception {
        Path tree = write(root, "com.taxonomy:taxonomy-app:jar:1.3.2-SNAPSHOT\n");

        HibernateSearchAlignmentPolicy.Evaluation evaluation = policy.evaluate(
                tree, SEARCH_VERSION, ORM_PREFIX, LUCENE_VERSION);

        assertThat(evaluation.passed()).isFalse();
        assertThat(evaluation.failures())
                .containsExactly(
                        "No org.hibernate.search artifacts were found in the dependency tree",
                        "hibernate-core resolved to [], expected one 7.4.x version",
                        "lucene-core resolved to [], expected 9.12.3");
        assertThat(evaluation.report())
                .contains("hibernate-core = missing")
                .contains("lucene-core = missing");
    }

    @Test
    void parsesCoordinatesWithOptionalClassifierAndIgnoresUnrelatedGroups() {
        Map<HibernateSearchAlignmentPolicy.Coordinate, Set<String>> resolved =
                HibernateSearchAlignmentPolicy.parse("""
                        org.hibernate.search:hibernate-search-engine:jar:tests:8.4.0.Final:test
                        org.hibernate.orm:hibernate-core:jar:7.4.1.Final:compile
                        org.apache.lucene:lucene-core:jar:9.12.3:compile
                        org.example:lucene-core:jar:0.0.1:compile
                        """);

        assertThat(resolved)
                .containsEntry(
                        new HibernateSearchAlignmentPolicy.Coordinate(
                                "org.hibernate.search", "hibernate-search-engine"),
                        Set.of("8.4.0.Final"))
                .containsEntry(
                        new HibernateSearchAlignmentPolicy.Coordinate(
                                "org.hibernate.orm", "hibernate-core"),
                        Set.of("7.4.1.Final"))
                .doesNotContainKey(new HibernateSearchAlignmentPolicy.Coordinate(
                        "org.example", "lucene-core"));
    }

    @Test
    void rejectsUnreadableInputAndBlankPolicyParameters(@TempDir Path root) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.evaluate(
                        root.resolve("missing.txt"),
                        SEARCH_VERSION,
                        ORM_PREFIX,
                        LUCENE_VERSION))
                .withMessageContaining("Cannot read Hibernate Search dependency tree");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.evaluate(
                        write(root, ""), " ", ORM_PREFIX, LUCENE_VERSION))
                .withMessageContaining("expectedSearchVersion must not be blank");
    }

    private static Path alignedTree(Path root) throws Exception {
        return write(root, """
                com.taxonomy:taxonomy-app:jar:1.3.2-SNAPSHOT
                +- org.hibernate.search:hibernate-search-mapper-orm:jar:8.4.0.Final:compile
                |  +- org.hibernate.search:hibernate-search-engine:jar:8.4.0.Final:compile
                |  \\- org.hibernate.orm:hibernate-core:jar:7.4.2.Final:compile
                +- org.hibernate.search:hibernate-search-backend-lucene:jar:8.4.0.Final:compile
                \\- org.apache.lucene:lucene-core:jar:9.12.3:compile
                """);
    }

    private static Path write(Path root, String content) throws Exception {
        Path path = root.resolve("dependencies.txt");
        Files.writeString(path, content);
        return path;
    }
}
