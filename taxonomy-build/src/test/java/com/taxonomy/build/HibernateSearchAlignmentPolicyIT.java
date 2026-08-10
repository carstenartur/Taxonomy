package com.taxonomy.build;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Post-reactor gate for the dependency tree resolved by {@code taxonomy-app}. */
class HibernateSearchAlignmentPolicyIT {

    @Test
    void resolvedHibernateSearchOrmAndLuceneFamilyIsAligned() throws Exception {
        Path root = findRepositoryRoot();
        Path dependencyTree = root.resolve("target/hibernate-search-dependencies.txt");
        String searchVersion = requiredProperty("hibernate.search.version");
        String ormPrefix = requiredProperty("hibernate.orm.version.prefix");
        String luceneVersion = requiredProperty("lucene.version");

        assertThat(dependencyTree)
                .as("Maven-resolved Hibernate Search dependency tree")
                .isRegularFile();

        HibernateSearchAlignmentPolicy.Evaluation evaluation =
                new HibernateSearchAlignmentPolicy().evaluate(
                        dependencyTree,
                        searchVersion,
                        ormPrefix,
                        luceneVersion);
        System.out.print(evaluation.report());

        assertThat(evaluation.passed())
                .as("Hibernate Search dependency alignment:%n%s", evaluation.report())
                .isTrue();
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Missing Maven-provided system property " + name);
        }
        return value.strip();
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("taxonomy-app"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }
}
