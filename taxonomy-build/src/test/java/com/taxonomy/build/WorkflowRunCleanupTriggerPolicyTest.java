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
                .contains("issue_comment:")
                .contains("types: [created]")
                .contains("github.event.comment.user.login == github.repository_owner")
                .contains("github.event.comment.body == '/actions-cleanup'")
                .contains("github.event_name != 'issue_comment'")
                .contains("DRY_RUN: ${{ github.event_name == 'workflow_dispatch'"
                        + " && inputs.dry_run || false }}");
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
