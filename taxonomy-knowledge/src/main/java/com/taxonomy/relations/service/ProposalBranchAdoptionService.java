package com.taxonomy.relations.service;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.ast.PropertyAst;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationIdentity;
import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.model.ProposalStatus;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.AdoptRelation;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandMetadata;
import com.taxonomy.relations.service.ProposalReviewStateStore.ProposalSnapshot;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.WorkspaceDslReadPort;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ProposalBranchAdoptionService {
    private static final String PROPOSAL = "x-proposal-id";
    private static final String REVIEW_SCOPE = "x-proposal-review-scope";
    private static final String REVIEW_BRANCH = "x-proposal-review-branch";
    private static final String SOURCE_BRANCH = "x-proposal-adopted-from-branch";
    private static final String SOURCE_SCOPE = "x-proposal-adopted-from-scope";
    private static final String SOURCE_COMMIT = "x-proposal-adopted-from-commit";
    private static final String REVIEW = "proposal-review";
    private static final String ADOPTION = "proposal-adoption";

    private final ProposalReviewStateStore store;
    private final WorkspaceDslReadPort reads;
    private final GitAuthoritativeRelationMutationService mutations;
    private final ArchitectureRelationDslTransformer relations = new ArchitectureRelationDslTransformer();

    public ProposalBranchAdoptionService(ProposalReviewStateStore store, WorkspaceDslReadPort reads,
                                         GitAuthoritativeRelationMutationService mutations) {
        this.store = store;
        this.reads = reads;
        this.mutations = mutations;
    }

    public enum State { AVAILABLE, ALREADY_PRESENT, CONFLICT, UNVERIFIED_SOURCE }

    public record Preview(long proposalId, String repositoryId, String sourceBranch, String sourceCommit,
                          String targetWorkspaceId, String targetBranch, String targetCommit, State state,
                          boolean reviewedOnSourceBranch) {}

    public static final class ProposalNotFoundException extends RuntimeException {
        public ProposalNotFoundException() { super("No central proposal in the selected repository"); }
    }
    public static final class AdoptionConflictException extends RuntimeException {
        public AdoptionConflictException() { super("The selected decision cannot be adopted at the reviewed revisions"); }
    }

    /** Only a server-observed review can label a branch as the place of the original decision. */
    static Map<String, String> reviewProperties(Long id, RepositoryContext context) {
        var properties = new HashMap<>(reviewOrigin(context));
        properties.put(PROPOSAL, id.toString());
        return Map.copyOf(properties);
    }

    static Map<String, String> reviewOrigin(RepositoryContext context) {
        return Map.of(REVIEW_SCOPE, scopeKey(context), REVIEW_BRANCH, context.branch());
    }

    private static String scopeKey(RepositoryContext context) {
        String routing = context.workspaceId() == null ? "central" : "workspace\0" + context.workspaceId();
        return StableIdentityHash.sha256(routing + "\0" + context.repositoryId() + "\0" + context.branch());
    }

    public Preview preview(Long id, RepositoryContext source, RepositoryContext target) throws IOException {
        ProposalSnapshot row = requireCentralRow(id, source, target);
        var from = reads.openRead(source);
        var into = reads.openRead(target);
        String sourceCommit = from.currentHead();
        String targetCommit = into.currentHead();
        var selection = inspect(row, source, sourceCommit, from, targetCommit, into);
        return previewOf(id, source, sourceCommit, target, targetCommit, selection);
    }

    public Preview adopt(Long id, RepositoryContext source, String sourceCommit, RepositoryContext target,
                         String targetCommit, CommandMetadata metadata) throws IOException {
        ProposalReviewStateStore.requireWritableContext(target);
        ProposalSnapshot row = requireCentralRow(id, source, target);
        var from = reads.openRead(source);
        var into = reads.openRead(target);
        String sourcePin = WorkspaceDslReadPort.normalizeCommitId(sourceCommit);
        String targetPin = WorkspaceDslReadPort.normalizeCommitId(targetCommit);
        var selection = inspect(row, source, sourcePin, from, targetPin, into);
        if (selection.state() == State.ALREADY_PRESENT) {
            return previewOf(id, source, sourcePin, target, targetPin, selection);
        }
        if (selection.state() != State.AVAILABLE) throw new AdoptionConflictException();
        // The source is immutable. The target ref is compared atomically again when the commit is written.
        from.verifyExpectedHead(sourcePin);
        var command = new AdoptRelation(identity(row), selection.adoption(), metadata);
        var result = mutations.adopt(target, targetPin, command);
        return new Preview(id, source.repositoryId(), source.branch(), sourcePin, target.workspaceId(), target.branch(),
                result.authority().authoritativeCommitId(), State.ALREADY_PRESENT, selection.reviewedOnSourceBranch());
    }

    private ProposalSnapshot requireCentralRow(Long id, RepositoryContext source, RepositoryContext target) {
        if (source.scope() != RepositoryScope.CENTRAL_READ || !source.repositoryId().equals(target.repositoryId())
                || !source.username().equals(target.username())) {
            throw new IllegalArgumentException("Proposal source and target must belong to the same authorized repository and actor");
        }
        return store.centralProposal(id, source.repositoryId()).orElseThrow(ProposalNotFoundException::new);
    }

    private static RelationIdentity identity(ProposalSnapshot row) {
        return new RelationIdentity(row.sourceCode(), row.relationType().name(), row.targetCode());
    }

    private record Selection(State state, BlockAst adoption, boolean reviewedOnSourceBranch) {}

    private Preview previewOf(long id, RepositoryContext source, String sourceCommit, RepositoryContext target,
                              String targetCommit, Selection selection) {
        return new Preview(id, source.repositoryId(), source.branch(), sourceCommit, target.workspaceId(),
                target.branch(), targetCommit, selection.state(), selection.reviewedOnSourceBranch());
    }

    private Selection inspect(ProposalSnapshot row, RepositoryContext source, String sourceCommit,
                              WorkspaceDslReadPort.RepositoryRead from, String targetCommit,
                              WorkspaceDslReadPort.RepositoryRead into) throws IOException {
        from.verifyExpectedHead(sourceCommit);
        into.verifyExpectedHead(targetCommit);
        if (row.status() != ProposalStatus.ACCEPTED || sourceCommit == null) {
            return new Selection(State.UNVERIFIED_SOURCE, null, false);
        }
        RelationIdentity identity = identity(row);
        Optional<BlockAst> proof;
        try {
            proof = relations.findProposalRelation(from.dslAtCommit(sourceCommit).orElse(""), identity, row.id().toString());
        } catch (CommandProblem | ArchitectureRelationDslTransformer.AmbiguousRelationException
                 | ArchitectureRelationDslTransformer.InvalidRelationBlockException malformed) {
            return new Selection(State.UNVERIFIED_SOURCE, null, false);
        }
        if (proof.isEmpty() || !accepted(proof.get(), row.id())) {
            return new Selection(State.UNVERIFIED_SOURCE, null, false);
        }
        BlockAst accepted = proof.get();
        boolean original = has(accepted, REVIEW_BRANCH, source.branch()) && has(accepted, REVIEW_SCOPE, scopeKey(source));
        if (targetCommit == null) return new Selection(State.CONFLICT, null, original);
        Optional<String> targetDsl = into.dslAtCommit(targetCommit);
        if (targetDsl.isEmpty()) return new Selection(State.CONFLICT, null, original);
        BlockAst adoption = adoptionBlock(accepted, source, sourceCommit);
        try {
            Optional<BlockAst> existing = relations.findProposalRelation(targetDsl.get(), identity, row.id().toString());
            if (existing.isEmpty()) {
                // Verify that a parser-tolerated, unterminated target cannot swallow the inserted decision.
                relations.insertAbsent(targetDsl.get(), identity, adoption);
                return new Selection(State.AVAILABLE, adoption, original);
            }
            BlockAst present = existing.get();
            boolean inherited = properties(present).equals(properties(accepted));
            boolean adopted = matchesAdoption(present, adoption, from, sourceCommit, identity, accepted);
            return new Selection(inherited || adopted ? State.ALREADY_PRESENT : State.CONFLICT, null, original);
        } catch (CommandProblem | ArchitectureRelationDslTransformer.AmbiguousRelationException
                 | ArchitectureRelationDslTransformer.InvalidRelationBlockException malformed) {
            return new Selection(State.CONFLICT, null, original);
        }
    }

    private static boolean accepted(BlockAst block, Long id) {
        return has(block, "status", "accepted") && has(block, "provenance", REVIEW) && has(block, PROPOSAL, id.toString())
                && block.propertyValues(SOURCE_BRANCH).isEmpty() && block.propertyValues(SOURCE_SCOPE).isEmpty()
                && block.propertyValues(SOURCE_COMMIT).isEmpty();
    }

    private static boolean has(BlockAst block, String key, String value) {
        return block.propertyValues(key).equals(List.of(value));
    }

    private static Map<String, List<String>> properties(BlockAst block) {
        Map<String, List<String>> properties = new HashMap<>();
        for (PropertyAst property : block.getProperties()) {
            properties.computeIfAbsent(property.key(), ignored -> new ArrayList<>()).add(property.value());
        }
        return properties;
    }

    private static BlockAst adoptionBlock(BlockAst accepted, RepositoryContext source, String commit) {
        var properties = new ArrayList<>(accepted.getProperties());
        properties.removeIf(p -> p.key().equals("provenance"));
        properties.add(new PropertyAst("provenance", ADOPTION, null));
        properties.add(new PropertyAst(SOURCE_SCOPE, scopeKey(source), null));
        properties.add(new PropertyAst(SOURCE_BRANCH, source.branch(), null));
        properties.add(new PropertyAst(SOURCE_COMMIT, commit, null));
        Map<String, String> extensions = new HashMap<>(accepted.getExtensions());
        extensions.put(SOURCE_SCOPE, scopeKey(source));
        extensions.put(SOURCE_BRANCH, source.branch());
        extensions.put(SOURCE_COMMIT, commit);
        return new BlockAst(accepted.getKind(), accepted.getHeaderTokens(), properties, List.of(), extensions, null);
    }

    private boolean matchesAdoption(BlockAst target, BlockAst adoption, WorkspaceDslReadPort.RepositoryRead from,
                                    String current, RelationIdentity identity, BlockAst accepted) throws IOException {
        var actual = properties(target);
        var expected = properties(adoption);
        // A later unrelated source commit leaves the already adopted decision valid.
        List<String> recorded = actual.remove(SOURCE_COMMIT);
        expected.remove(SOURCE_COMMIT);
        if (recorded == null || recorded.size() != 1 || !expected.equals(actual)) return false;
        String adoptedFrom = recorded.getFirst();
        if (current.equals(adoptedFrom)) return true;
        if (!from.relationshipsTo(current, List.of(adoptedFrom)).equals(List.of(
                WorkspaceDslReadPort.CommitRelationship.ANCESTOR))) return false;
        var prior = from.dslAtCommit(adoptedFrom);
        return prior.isPresent() && relations.findProposalRelation(prior.get(), identity, accepted.property(PROPOSAL))
                .map(original -> properties(original).equals(properties(accepted))).orElse(false);
    }
}
