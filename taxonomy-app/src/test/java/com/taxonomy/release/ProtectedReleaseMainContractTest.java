package com.taxonomy.release;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the release handoff to the protected main branch.
 *
 * <p>A release may prepare commits and temporary refs, but it must never bypass
 * the repository ruleset to update main. The next-development snapshot is
 * accepted only through a pull request after the canonical Maven verification
 * succeeds on that exact head SHA.</p>
 */
class ProtectedReleaseMainContractTest {

    @Test
    void releaseScriptDelegatesMainAdvanceInsteadOfPatchingProtectedRef() throws Exception {
        String release = repositoryFile(".github/scripts/release.sh");

        assertThat(release)
                .contains("PROTECTED_MAIN_ADVANCE_WORKFLOW=\"protected-release-main-advance.yml\"")
                .contains("advance_main_via_protected_pr()")
                .contains("gh workflow run \"$PROTECTED_MAIN_ADVANCE_WORKFLOW\"")
                .contains("gh run watch \"$advance_run_id\" --exit-status")
                .contains("advance_main_via_protected_pr \"$NEXT_COMMIT\"")
                .doesNotContain("git/refs/heads/main");

        int tagCall = release.indexOf("create_tag_ref \"$RELEASE_COMMIT\"");
        int protectedAdvance = release.indexOf("advance_main_via_protected_pr \"$NEXT_COMMIT\"");
        int draftCreation = release.indexOf("gh release create \"$TAG_NAME\"");
        assertThat(tagCall).isGreaterThanOrEqualTo(0);
        assertThat(protectedAdvance).isGreaterThan(tagCall);
        assertThat(draftCreation).isGreaterThan(protectedAdvance);
    }

    @Test
    void protectedAdvanceWorkflowUsesExactShaCanonicalCiAndNormalPullRequestMerge()
            throws Exception {
        String workflow = repositoryFile(
                ".github/workflows/protected-release-main-advance.yml");
        String ci = repositoryFile(".github/workflows/ci-cd.yml");

        assertThat(workflow)
                .contains("pull-requests: write")
                .contains("actions: write")
                .contains("contents: write")
                .contains("expected_sha:")
                .contains("expected_base_sha:")
                .contains("test \"$actual_main\" = \"$EXPECTED_BASE_SHA\"")
                .contains("test \"$actual_head\" = \"$EXPECTED_SHA\"")
                .contains("gh pr create")
                .contains("gh workflow run ci-cd.yml --ref \"$TEMP_BRANCH\"")
                .contains("--event workflow_dispatch")
                .contains("--commit \"$EXPECTED_SHA\"")
                .contains("gh run watch \"$run_id\" --exit-status")
                .contains("gh pr merge \"$PR_NUMBER\" --merge")
                .contains("git merge-base --is-ancestor \"$EXPECTED_SHA\" origin/main")
                .doesNotContain("bypass")
                .doesNotContain("--admin");

        assertThat(workflow.indexOf("gh pr create"))
                .isLessThan(workflow.indexOf("gh workflow run ci-cd.yml"));
        assertThat(workflow.indexOf("gh run watch \"$run_id\" --exit-status"))
                .isLessThan(workflow.indexOf("gh pr merge \"$PR_NUMBER\" --merge"));
        assertThat(ci).contains("workflow_dispatch:");
    }

    private static String repositoryFile(String relative) throws IOException {
        Path root = findRepositoryRoot();
        Path file = root.resolve(relative).normalize();
        assertThat(file).exists();
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve(".github"))
                    && Files.isRegularFile(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate repository root from "
                + Path.of("").toAbsolutePath());
    }
}
