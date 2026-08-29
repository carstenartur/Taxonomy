package com.taxonomy.build;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowRunCleanupTriggerPolicyTest {

    @Test
    void ownerCommentRequiresTheExactProductiveCleanupCommand() throws Exception {
        String workflow = Files.readString(repositoryRoot().resolve(
                ".github/workflows/cleanup-workflow-runs.yml"));

        assertThat(workflow)
                .contains("issue_comment:\n    types: [created]")
                .doesNotContain("  issues: read");
        assertThat(cleanupJobGate(workflow)).isEqualTo("""
                cleanup:
                    if: >-
                      github.event_name != 'issue_comment' ||
                      (github.event.comment.user.login == github.repository_owner &&
                       github.event.comment.body == '/actions-cleanup')
                """);
        assertThat(workflow).contains(
                "DRY_RUN: ${{ github.event_name == 'workflow_dispatch'"
                        + " && inputs.dry_run || false }}");
    }

    private static String cleanupJobGate(String workflow) {
        int jobStart = workflow.indexOf("\n  cleanup:\n");
        int runsOn = workflow.indexOf("\n    runs-on:", jobStart);
        if (jobStart < 0 || runsOn < 0) {
            throw new IllegalArgumentException(
                    "cleanup job and its runs-on boundary must exist");
        }
        return workflow.substring(jobStart + 3, runsOn + 1);
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve(
                    ".github/workflows/cleanup-workflow-runs.yml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }
}
