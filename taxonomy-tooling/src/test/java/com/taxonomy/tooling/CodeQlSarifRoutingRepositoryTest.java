package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class CodeQlSarifRoutingRepositoryTest {

    @Test
    void bothCodeQlJobsUseTheSameBuiltJavaGateWithoutPythonFallback()
            throws Exception {
        Path root = findRepositoryRoot();
        Path workflowPath = root.resolve(".github/workflows/codeql.yml");
        Path removedPythonGate = root.resolve(
                ".github/scripts/check-codeql-sarif.py");
        String workflow = Files.readString(
                workflowPath, StandardCharsets.UTF_8);

        assertThat(workflow)
                .contains("Build Java reactor and SARIF gate for CodeQL")
                .contains("Build and preserve Java SARIF gate")
                .contains("taxonomy-app,taxonomy-tooling")
                .contains("-pl taxonomy-tooling package -DskipTests")
                .contains("cp \"$tooling_jar\" \"$RUNNER_TEMP/taxonomy-tooling.jar\"")
                .contains("java -jar \"$RUNNER_TEMP/taxonomy-tooling.jar\" check-codeql-sarif")
                .contains("find codeql-java -type f -name '*.sarif' -print0 | sort -z")
                .contains("find codeql-javascript -type f -name '*.sarif' -print0 | sort -z")
                .contains("target/codeql-java-gate.json")
                .contains("target/codeql-javascript-gate.json")
                .doesNotContain("check-codeql-sarif.py")
                .doesNotContainPattern(
                        "(?<![A-Za-z0-9_-])python(?:3)?(?![A-Za-z0-9_-])")
                .doesNotContain("setup-python");
        assertThat(count(workflow, "check-codeql-sarif"))
                .as("one invocation for Java and one for JavaScript")
                .isEqualTo(2);
        assertThat(count(workflow, "Java CodeQL SARIF gate jar was not produced"))
                .as("both jobs fail closed when the immutable Java jar is missing")
                .isEqualTo(2);
        assertThat(count(workflow, "-print0 | sort -z"))
                .as("both jobs order their discovered SARIF inputs deterministically")
                .isEqualTo(2);
        assertThat(removedPythonGate).doesNotExist();
    }

    @Test
    void sourceRatchetNoLongerAllowsTheRemovedPythonGate() throws Exception {
        Path root = findRepositoryRoot();
        String ratchet = Files.readString(
                root.resolve("taxonomy-tooling/src/test/java/com/taxonomy/tooling/"
                        + "PythonSourceRatchetRepositoryTest.java"),
                StandardCharsets.UTF_8);

        assertThat(ratchet).doesNotContain("check-codeql-sarif.py");
    }

    private static int count(String value, String needle) {
        return value.split(Pattern.quote(needle), -1).length - 1;
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isRegularFile(current.resolve(
                            ".github/workflows/codeql.yml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }
}
