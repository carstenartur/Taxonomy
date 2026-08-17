package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DevelopmentVersionWorkflowCleanlinessContractTest {

    @Test
    void workflowRejectsUnstagedAndUntrackedTransitionByproductsBeforeCommit()
            throws Exception {
        Path root = findRepositoryRoot();
        String workflow = Files.readString(
                root.resolve(".github/workflows/prepare-development-version.yml"));

        int stage = workflow.indexOf("git add -- \"${tracked_poms[@]}\"");
        int unstaged = workflow.indexOf("if ! git diff --quiet --; then", stage);
        int untracked = workflow.indexOf(
                "untracked=$(git ls-files --others --exclude-standard)", stage);
        int stagedNonEmpty = workflow.indexOf(
                "if git diff --cached --quiet; then", stage);
        int commit = workflow.indexOf(
                "git commit -m \"Prepare development version $NEXT_VERSION\"", stage);

        assertThat(stage).isGreaterThanOrEqualTo(0);
        assertThat(unstaged).isGreaterThan(stage);
        assertThat(untracked).isGreaterThan(unstaged);
        assertThat(stagedNonEmpty).isGreaterThan(untracked);
        assertThat(commit).isGreaterThan(stagedNonEmpty);
        assertThat(workflow)
                .contains("Version transition left unstaged tracked changes")
                .contains("Version transition produced untracked files")
                .contains("git diff --name-only -- >&2")
                .contains("printf '%s\\n' \"$untracked\" >&2");
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty(
                        "maven.multiModuleProjectDirectory", "."))
                .toAbsolutePath()
                .normalize();
        while (current != null) {
            if (Files.isRegularFile(
                    current.resolve(".github/workflows/prepare-development-version.yml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }
}
