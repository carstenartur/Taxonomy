package com.taxonomy.versioning.service;

import com.taxonomy.dsl.storage.DslGitRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticGitMergeServiceTest {

    @Test
    void mergesIndependentRequirementContributionsIntoARealMergeCommit() throws Exception {
        try (DslGitRepository repository = new DslGitRepository()) {
            repository.commitDsl("draft", document("""
                    project P-001 {
                      title: "Architecture";
                      description: "Initial";
                    }
                    """), "system", "base");
            repository.createBranch("alice", "draft");
            repository.createBranch("bob", "draft");

            repository.commitDsl("alice", document("""
                    project P-001 {
                      title: "Reviewed architecture";
                      description: "Initial";
                    }
                    projectRequirement P-001 REQ-A-001 {
                      title: "Alice requirement";
                      text: "Secure voice";
                    }
                    """), "alice", "Alice requirements");
            repository.commitDsl("bob", document("""
                    project P-001 {
                      title: "Architecture";
                      description: "Shared target";
                    }
                    projectRequirement P-001 REQ-B-001 {
                      title: "Bob requirement";
                      text: "Offline operation";
                    }
                    """), "bob", "Bob requirements");

            SemanticGitMergeService service = new SemanticGitMergeService();
            SemanticGitMergeService.MergeOutcome outcome = service.mergeBranches(
                    repository, "alice", "bob", "architect");

            assertThat(outcome.success()).isTrue();
            assertThat(repository.getDslAtHead("bob"))
                    .contains("REQ-A-001")
                    .contains("REQ-B-001")
                    .contains("Reviewed architecture")
                    .contains("Shared target");

            try (RevWalk walk = new RevWalk(repository.getGitRepository())) {
                RevCommit mergeCommit = walk.parseCommit(ObjectId.fromString(outcome.commitId()));
                assertThat(mergeCommit.getParentCount()).isEqualTo(2);
            }
        }
    }

    @Test
    void refusesTwoDifferentEditsToTheSameRequirementText() throws Exception {
        try (DslGitRepository repository = new DslGitRepository()) {
            repository.commitDsl("draft", requirement("Original"), "system", "base");
            repository.createBranch("alice", "draft");
            repository.createBranch("bob", "draft");
            repository.commitDsl("alice", requirement("Alice text"), "alice", "Alice edit");
            repository.commitDsl("bob", requirement("Bob text"), "bob", "Bob edit");

            SemanticGitMergeService.MergeOutcome outcome = new SemanticGitMergeService()
                    .mergeBranches(repository, "alice", "bob", "architect");

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.conflicts())
                    .contains("projectRequirement P-001 REQ-001:text");
        }
    }

    private static String requirement(String text) {
        return document("""
                projectRequirement P-001 REQ-001 {
                  title: "Requirement";
                  text: "%s";
                }
                """.formatted(text));
    }

    private static String document(String blocks) {
        return """
                meta {
                  language: "taxdsl";
                  version: "2.1";
                  namespace: "test";
                }

                %s
                """.formatted(blocks);
    }
}
