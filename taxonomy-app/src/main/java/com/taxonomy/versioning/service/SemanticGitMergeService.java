package com.taxonomy.versioning.service;

import com.taxonomy.dsl.merge.TaxDslMergeResult;
import com.taxonomy.dsl.merge.TaxDslSemanticMerger;
import com.taxonomy.dsl.storage.DslGitRepository;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Executes the normal Git merge first and falls back to a block-semantic DSL
 * merge when the canonical architecture file conflicts textually.
 */
@Service
public class SemanticGitMergeService {

    private static final String DSL_FILE = "architecture.taxdsl";

    private final TaxDslSemanticMerger semanticMerger = new TaxDslSemanticMerger();

    public MergeOutcome mergeBranches(DslGitRepository repository,
                                      String fromBranch,
                                      String intoBranch,
                                      String author) throws IOException {
        return mergeBranches(repository, fromBranch, intoBranch, author, null);
    }

    public MergeOutcome mergeBranches(DslGitRepository repository,
                                      String fromBranch,
                                      String intoBranch,
                                      String author,
                                      String message) throws IOException {
        String requestedMessage = normalizeMessage(message);
        String ordinaryCommit = repository.merge(fromBranch, intoBranch);
        if (ordinaryCommit != null) {
            String effectiveCommit = applyRequestedMergeMessage(
                    repository.getGitRepository(),
                    intoBranch,
                    ordinaryCommit,
                    author,
                    requestedMessage);
            return new MergeOutcome(true, effectiveCommit, false, List.of(), null);
        }

        SemanticInputs inputs = semanticInputs(repository, fromBranch, intoBranch);
        if (inputs.error() != null) {
            return new MergeOutcome(false, null, false, List.of(inputs.error()), null);
        }
        TaxDslMergeResult merge = semanticMerger.merge(
                inputs.baseDsl(), inputs.oursDsl(), inputs.theirsDsl());
        if (!merge.isSuccessful()) {
            return new MergeOutcome(
                    false, null, true, merge.conflictIdentifiers(), merge);
        }

        String commitId = createMergeCommit(
                repository.getGitRepository(),
                inputs.oursCommit(),
                inputs.theirsCommit(),
                intoBranch,
                fromBranch,
                merge.mergedText(),
                author,
                requestedMessage);
        return new MergeOutcome(true, commitId, true, List.of(), merge);
    }

    public MergeOutcome preview(DslGitRepository repository,
                                String fromBranch,
                                String intoBranch) throws IOException {
        SemanticInputs inputs = semanticInputs(repository, fromBranch, intoBranch);
        if (inputs.error() != null) {
            return new MergeOutcome(false, null, false, List.of(inputs.error()), null);
        }
        TaxDslMergeResult merge = semanticMerger.merge(
                inputs.baseDsl(), inputs.oursDsl(), inputs.theirsDsl());
        return new MergeOutcome(
                merge.isSuccessful(), null, true,
                merge.conflictIdentifiers(), merge);
    }

    /** Merge three DSL payloads when source and target live in different repositories. */
    public TaxDslMergeResult mergeContent(String baseDsl, String oursDsl, String theirsDsl) {
        return semanticMerger.merge(baseDsl, oursDsl, theirsDsl);
    }

    private SemanticInputs semanticInputs(DslGitRepository repository,
                                          String fromBranch,
                                          String intoBranch) throws IOException {
        Repository git = repository.getGitRepository();
        Ref fromRef = git.getRefDatabase().exactRef(Constants.R_HEADS + fromBranch);
        Ref intoRef = git.getRefDatabase().exactRef(Constants.R_HEADS + intoBranch);
        if (fromRef == null) {
            return SemanticInputs.error("Source branch '" + fromBranch + "' not found");
        }
        if (intoRef == null) {
            return SemanticInputs.error("Target branch '" + intoBranch + "' not found");
        }

        try (RevWalk walk = new RevWalk(git)) {
            RevCommit theirs = walk.parseCommit(fromRef.getObjectId());
            RevCommit ours = walk.parseCommit(intoRef.getObjectId());
            RevCommit base = findMergeBase(git, ours, theirs);
            String baseDsl = base == null ? "" : repository.getDslAtCommit(base.name());
            String oursDsl = repository.getDslAtCommit(ours.name());
            String theirsDsl = repository.getDslAtCommit(theirs.name());
            return new SemanticInputs(baseDsl, oursDsl, theirsDsl, ours, theirs, null);
        }
    }

    private static RevCommit findMergeBase(Repository repository,
                                           RevCommit ours,
                                           RevCommit theirs) throws IOException {
        try (RevWalk baseWalk = new RevWalk(repository)) {
            baseWalk.setRevFilter(RevFilter.MERGE_BASE);
            baseWalk.markStart(baseWalk.parseCommit(ours));
            baseWalk.markStart(baseWalk.parseCommit(theirs));
            return baseWalk.next();
        }
    }

    /**
     * The generic repository merge supplies a conventional default message.
     * When a caller explicitly requested a message, replace only a newly created
     * two-parent merge commit and keep fast-forward/already-merged results intact.
     */
    private static String applyRequestedMergeMessage(Repository repository,
                                                     String intoBranch,
                                                     String commitId,
                                                     String authorName,
                                                     String requestedMessage) throws IOException {
        if (requestedMessage == null) {
            return commitId;
        }

        ObjectId originalId = ObjectId.fromString(commitId);
        Ref branch = repository.getRefDatabase().exactRef(Constants.R_HEADS + intoBranch);
        if (branch == null || !originalId.equals(branch.getObjectId())) {
            return commitId;
        }

        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit original = walk.parseCommit(originalId);
            if (original.getParentCount() < 2) {
                return commitId;
            }

            PersonIdent actor = actor(authorName);
            try (ObjectInserter inserter = repository.newObjectInserter()) {
                CommitBuilder replacement = new CommitBuilder();
                replacement.setTreeId(original.getTree());
                replacement.setParentIds(original.getParents());
                replacement.setAuthor(actor);
                replacement.setCommitter(actor);
                replacement.setMessage(requestedMessage);
                ObjectId replacementId = inserter.insert(replacement);
                inserter.flush();

                RefUpdate update = repository.updateRef(Constants.R_HEADS + intoBranch);
                update.setExpectedOldObjectId(originalId);
                update.setNewObjectId(replacementId);
                update.setForceUpdate(true);
                update.setRefLogIdent(actor);
                update.setRefLogMessage("merge: " + requestedMessage, false);
                requireUpdated(update.update(), intoBranch);
                return replacementId.name();
            }
        }
    }

    private static String createMergeCommit(Repository repository,
                                            RevCommit ours,
                                            RevCommit theirs,
                                            String intoBranch,
                                            String fromBranch,
                                            String mergedDsl,
                                            String authorName,
                                            String requestedMessage) throws IOException {
        PersonIdent actor = actor(authorName);

        try (ObjectInserter inserter = repository.newObjectInserter()) {
            ObjectId blob = inserter.insert(Constants.OBJ_BLOB,
                    mergedDsl.getBytes(StandardCharsets.UTF_8));
            TreeFormatter tree = new TreeFormatter();
            tree.append(DSL_FILE, org.eclipse.jgit.lib.FileMode.REGULAR_FILE, blob);
            ObjectId treeId = inserter.insert(tree);

            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(treeId);
            commit.setParentIds(ours, theirs);
            commit.setAuthor(actor);
            commit.setCommitter(actor);
            commit.setMessage(requestedMessage != null
                    ? requestedMessage
                    : "Semantic merge branch '" + fromBranch + "' into " + intoBranch);
            ObjectId commitId = inserter.insert(commit);
            inserter.flush();

            RefUpdate update = repository.updateRef(Constants.R_HEADS + intoBranch);
            update.setExpectedOldObjectId(ours);
            update.setNewObjectId(commitId);
            update.setRefLogIdent(actor);
            update.setRefLogMessage("semantic merge: " + fromBranch + " into " + intoBranch, false);
            requireUpdated(update.update(), intoBranch);
            return commitId.name();
        }
    }

    private static PersonIdent actor(String authorName) {
        String effectiveAuthor = authorName == null || authorName.isBlank()
                ? "semantic-merge" : authorName.strip();
        return new PersonIdent(effectiveAuthor, "noreply@taxonomy.local");
    }

    private static String normalizeMessage(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        return message.strip();
    }

    private static void requireUpdated(RefUpdate.Result result,
                                       String branch) throws IOException {
        if (result != RefUpdate.Result.NEW
                && result != RefUpdate.Result.FAST_FORWARD
                && result != RefUpdate.Result.FORCED
                && result != RefUpdate.Result.NO_CHANGE) {
            throw new IOException("Could not update branch '" + branch
                    + "' after merge: " + result);
        }
    }

    public record MergeOutcome(
            boolean success,
            String commitId,
            boolean semanticFallback,
            List<String> conflicts,
            TaxDslMergeResult semanticResult) {
        public MergeOutcome {
            conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        }
    }

    private record SemanticInputs(
            String baseDsl,
            String oursDsl,
            String theirsDsl,
            RevCommit oursCommit,
            RevCommit theirsCommit,
            String error) {
        static SemanticInputs error(String error) {
            return new SemanticInputs(null, null, null, null, null, error);
        }
    }
}
