package com.taxonomy.build;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Keeps the production Docker build aligned with the declared Maven reactor. */
class ContainerReactorContractTest {

    private static final Pattern MODULE = Pattern.compile(
            "<module>\\s*([^<]+?)\\s*</module>");

    @Test
    void productionDockerfileCopiesEveryReactorDescriptorAndMainSourceTree()
            throws Exception {
        Path root = findRepositoryRoot();
        String pom = Files.readString(root.resolve("pom.xml"), StandardCharsets.UTF_8);
        String dockerfile = Files.readString(
                root.resolve("Dockerfile"), StandardCharsets.UTF_8);
        List<String> modules = modules(pom);

        assertThat(modules).isNotEmpty();
        for (String module : modules) {
            assertThat(dockerfile)
                    .as("Dockerfile reactor descriptor for %s", module)
                    .contains("COPY " + module + "/pom.xml " + module + "/pom.xml");
            if (Files.isDirectory(root.resolve(module + "/src/main"))) {
                assertThat(dockerfile)
                        .as("Dockerfile main source tree for %s", module)
                        .contains("COPY " + module + "/src " + module + "/src");
            }
        }
        assertThat(dockerfile)
                .contains("./mvnw -q -DskipTests package")
                .doesNotContain("-pl taxonomy-app");
    }

    private static List<String> modules(String pom) {
        Matcher matcher = MODULE.matcher(pom);
        return matcher.results()
                .map(result -> result.group(1).strip())
                .toList();
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isRegularFile(current.resolve("Dockerfile"))
                    && Files.isDirectory(current.resolve("deploy/helm/taxonomy"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }
}
