package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JavaToolingWorkflowRepositoryTest {

    @Test
    void everyProductiveWorkflowBuildsAndValidatesTheToolingJar()
            throws Exception {
        Path root = findRepositoryRoot();

        assertWorkflowBuild(
                root.resolve(".github/workflows/ci-cd.yml"),
                "Java release tooling jar was not produced");
        assertWorkflowBuild(
                root.resolve(".github/workflows/deploy-release.yml"),
                "Java release tooling jar was not produced");
        assertWorkflowBuild(
                root.resolve(".github/workflows/protected-release-main-advance.yml"),
                "Java release tooling jar was not produced");
        assertWorkflowBuild(
                root.resolve(".github/workflows/prepare-development-version.yml"),
                "Java version tooling jar was not produced");

        String releaseScript = read(root.resolve(".github/scripts/release.sh"));
        assertThat(releaseScript)
                .contains("${TOOLING_JAR:?TOOLING_JAR is required}")
                .contains("! -name 'taxonomy-tooling-*.jar'");
    }

    @Test
    void protectedMainAdvanceWaitsForEveryRequiredCheckOnTheExactHead()
            throws Exception {
        Path root = findRepositoryRoot();
        String workflow = read(root.resolve(
                ".github/workflows/protected-release-main-advance.yml"));

        String canonical = "- name: Run canonical verification on exact snapshot";
        String completeGates =
                "- name: Wait for complete protected-main pull-request gates";
        String merge = "- name: Merge through protected main";

        assertThat(workflow)
                .contains(canonical)
                .contains(completeGates)
                .contains(merge)
                .contains("gh pr checks \"$PR_NUMBER\" --required --watch --fail-fast")
                .contains("head_before=$(gh pr view \"$PR_NUMBER\" --json headRefOid")
                .contains("head_after=$(gh pr view \"$PR_NUMBER\" --json headRefOid")
                .contains("gh pr merge \"$PR_NUMBER\" --merge --match-head-commit \"$EXPECTED_SHA\"");
        assertThat(workflow.indexOf(canonical))
                .isLessThan(workflow.indexOf(completeGates));
        assertThat(workflow.indexOf(completeGates))
                .isLessThan(workflow.indexOf(merge));
    }

    private static void assertWorkflowBuild(Path workflow, String diagnostic)
            throws Exception {
        String text = read(workflow);
        assertThat(text)
                .contains("./mvnw -B -pl taxonomy-tooling -am package -DskipTests")
                .contains("-name 'taxonomy-tooling-*.jar'")
                .contains("if [[ -z \"$tooling_jar\" || ! -f \"$tooling_jar\" ]]")
                .contains("::error::" + diagnostic)
                .contains("$RUNNER_TEMP/taxonomy-tooling.jar");
    }

    private static String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isRegularFile(current.resolve(
                            "taxonomy-tooling/pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }
}
