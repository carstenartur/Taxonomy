package com.taxonomy.dsl.storage;

import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceDslReadPort;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Adapts exact DSL/branch reads without normalizing missing files to empty DSL. */
@Component
public final class DslWorkspaceReadAdapter implements WorkspaceDslReadPort {

    private final DslGitRepositoryFactory repositories;

    public DslWorkspaceReadAdapter(DslGitRepositoryFactory repositories) {
        this.repositories = Objects.requireNonNull(repositories, "repositories");
    }

    @Override
    public RepositoryRead openRead(RepositoryContext context) {
        Objects.requireNonNull(context, "context");
        return new BoundRead(repositories.resolveRepository(context), context.branch());
    }

    private static final class BoundRead implements RepositoryRead {
        private final DslGitRepository repository;
        private final String branch;
        private final ExpectedHeadDslCommitter verifier = new ExpectedHeadDslCommitter();

        private BoundRead(DslGitRepository repository, String branch) {
            this.repository = Objects.requireNonNull(repository, "repository");
            this.branch = branch;
        }

        @Override
        public String currentHead() throws IOException {
            return repository.getHeadCommit(branch);
        }

        @Override
        public Optional<String> dslAtCommit(String commitId) throws IOException {
            Objects.requireNonNull(commitId, "commitId");
            return Optional.ofNullable(repository.getDslAtCommit(commitId));
        }

        @Override
        public String verifyExpectedHead(String expectedHeadCommit) throws IOException {
            return verifier.verifyExpectedHead(repository, branch, expectedHeadCommit);
        }

        @Override
        public CommitMetadata commitMetadata(String commitId) throws IOException {
            try (var walk = new RevWalk(repository.getGitRepository())) {
                RevCommit commit = walk.parseCommit(ObjectId.fromString(
                        WorkspaceDslReadPort.normalizeCommitId(commitId)));
                return new CommitMetadata(commit.getShortMessage(), commit.getAuthorIdent().getName(),
                        commit.getFullMessage(), Arrays.stream(commit.getParents()).map(RevCommit::name).toList());
            }
        }

        @Override
        public List<CommitRelationship> relationshipsTo(String descendantCommitId,
                                                        List<String> candidateCommitIds) throws IOException {
            Objects.requireNonNull(candidateCommitIds, "candidateCommitIds");
            try (var walk = new RevWalk(repository.getGitRepository())) {
                RevCommit descendant = walk.parseCommit(ObjectId.fromString(
                        WorkspaceDslReadPort.normalizeCommitId(descendantCommitId)));
                List<CommitRelationship> relationships = new ArrayList<>(candidateCommitIds.size());
                for (String candidate : candidateCommitIds) {
                    relationships.add(relationship(walk, descendant, candidate));
                }
                return List.copyOf(relationships);
            }
        }

        private static CommitRelationship relationship(RevWalk walk, RevCommit descendant, String candidateId) {
            try {
                RevCommit candidate = walk.parseCommit(ObjectId.fromString(
                        WorkspaceDslReadPort.normalizeCommitId(candidateId)));
                if (candidate.equals(descendant)) {
                    return CommitRelationship.SAME;
                }
                return walk.isMergedInto(candidate, descendant)
                        ? CommitRelationship.ANCESTOR : CommitRelationship.UNRELATED;
            } catch (IOException | IllegalArgumentException error) {
                return CommitRelationship.UNAVAILABLE;
            }
        }
    }
}
