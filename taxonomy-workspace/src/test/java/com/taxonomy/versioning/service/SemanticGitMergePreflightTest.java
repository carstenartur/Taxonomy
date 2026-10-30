package com.taxonomy.versioning.service;

import com.taxonomy.workspace.service.BranchHeadConflictException;
import com.taxonomy.workspace.signing.SignatureStatus;
import com.taxonomy.workspace.signing.SigningTestKeys;
import com.taxonomy.workspace.storage.DslGitRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticGitMergePreflightTest {
    enum MergeKind { FAST_FORWARD, ORDINARY, SEMANTIC, ALREADY_MERGED }
    @TempDir Path signingKeys;
    private static final String BASE = """
            project P-A {
              title: "Original architecture";
              description: "Original description";
            }

            project P-B {
              title: "Original operations";
              description: "Unchanged second description";
            }
            """;

    @ParameterizedTest
    @EnumSource(MergeKind.class)
    void rejectedExactMergedSourceNeverAdvancesEitherRef(MergeKind kind) throws Exception {
        try (var repository = repository(kind)) {
            String from = repository.getHeadCommit("from"), into = repository.getHeadCommit("into");
            var checked = new ArrayList<String>();
            var rejection = new IllegalStateException("Rejected merged portfolio");

            assertThatThrownBy(() -> new SemanticGitMergeService().mergeBranches(repository,
                    "from", "into", "alice", "Reviewed merge", candidate -> {
                        assertThat(Thread.holdsLock(repository.getGitRepository())).isFalse();
                        checked.add(candidate);
                        throw rejection;
                    })).isSameAs(rejection);

            assertThat(checked).hasSize(1);
            assertThat(checked.getFirst()).contains(kind == MergeKind.ALREADY_MERGED
                    ? "Original architecture" : "Reviewed architecture");
            assertThat(repository.getHeadCommit("from")).isEqualTo(from);
            assertThat(repository.getHeadCommit("into")).isEqualTo(into);
        }
    }

    @Test
    void acceptedSemanticCandidateIsTheSignedTwoParentCommitThatGetsPublished() throws Exception {
        try (var repository = repository(MergeKind.SEMANTIC)) {
            repository.withSignatureService(SigningTestKeys.createService(signingKeys), "central");
            String from = repository.getHeadCommit("from"), into = repository.getHeadCommit("into");
            var checked = new ArrayList<String>();
            var outcome = new SemanticGitMergeService().mergeBranches(repository, "from", "into",
                    "alice", "Approved portfolio source", candidate -> {
                        assertThat(Thread.holdsLock(repository.getGitRepository())).isFalse();
                        checked.add(candidate);
                    });

            assertThat(outcome.success()).isTrue();
            assertThat(outcome.semanticFallback()).isTrue();
            assertThat(checked).containsExactly(repository.getDslAtCommit(outcome.commitId()));
            assertThat(checked.getFirst()).contains("Reviewed architecture", "Reviewed description");
            assertThat(repository.getHeadCommit("into")).isEqualTo(outcome.commitId());
            assertThat(repository.verifyCommit(outcome.commitId()).status()).isEqualTo(SignatureStatus.VERIFIED);
            try (var walk = new RevWalk(repository.getGitRepository())) {
                var commit = walk.parseCommit(ObjectId.fromString(outcome.commitId()));
                assertThat(commit.getParents()).extracting(ObjectId::name).containsExactly(into, from);
                assertThat(commit.getFullMessage()).isEqualTo("Approved portfolio source");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"from", "into"})
    void refMovementDuringValidationCannotPublishTheCapturedCandidate(String movedBranch) throws Exception {
        try (var repository = repository(MergeKind.ORDINARY)) {
            String from = repository.getHeadCommit("from"), into = repository.getHeadCommit("into");
            var winner = new ArrayList<String>();
            assertThatThrownBy(() -> new SemanticGitMergeService().mergeBranches(repository,
                    "from", "into", "alice", "Stale merge", candidate -> {
                        try {
                            winner.add(repository.commitDsl(movedBranch, candidate + "# Concurrent winner\n", "bob", "Concurrent winner"));
                        } catch (java.io.IOException failure) { throw new UncheckedIOException(failure); }
                    })).isInstanceOf(BranchHeadConflictException.class);

            assertThat(winner).hasSize(1);
            assertThat(repository.getHeadCommit("from")).isEqualTo(movedBranch.equals("from") ? winner.getFirst() : from);
            assertThat(repository.getHeadCommit("into")).isEqualTo(movedBranch.equals("into") ? winner.getFirst() : into);
        }
    }

    private static DslGitRepository repository(MergeKind kind) throws Exception {
        var repository = new DslGitRepository();
        repository.commitDsl("main", BASE, "alice", "Shared base");
        repository.createBranch("from", "main");
        repository.createBranch("into", "main");
        if (kind != MergeKind.ALREADY_MERGED)
            repository.commitDsl("from", BASE.replace("Original architecture", "Reviewed architecture"), "alice", "Source change");
        if (kind == MergeKind.ORDINARY)
            repository.commitDsl("into", BASE.replace("Original operations", "Reviewed operations"), "bob", "Independent target change");
        if (kind == MergeKind.SEMANTIC)
            repository.commitDsl("into", BASE.replace("Original description", "Reviewed description"), "bob", "Adjacent target change");
        return repository;
    }
}
