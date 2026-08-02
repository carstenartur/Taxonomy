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
        String ordinaryCommit = repository.merge(fromBranch, intoBranch);
        if (ordinaryCommit != null) {
            return new MergeOutcome(true, ordinaryCommit, false, List.of(), null);
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
                author);
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

    private static String createMergeCommit(Repository repository,
                                            RevCommit ours,
                                            RevCommit theirs,
                                            String intoBranch,
                                            String fromBranch,
                                            String mergedDsl,
                                            String authorName) throws IOException {
        String effectiveAuthor = authorName == null || authorName.isBlank()
                ? "semantic-merge" : authorName.strip();
        PersonIdent actor = new PersonIdent(effectiveAuthor, "noreply@taxonomy.local");

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
            commit.setMessage("Semantic merge branch '" + fromBranch + "' into " + intoBranch);
            ObjectId commitId = inserter.insert(commit);
            inserter.flush();

            RefUpdate update = repository.updateRef(Constants.R_HEADS + intoBranch);
            update.setExpectedOldObjectId(ours);
            update.setNewObjectId(commitId);
            update.setRefLogIdent(actor);
            update.setRefLogMessage("semantic merge: " + fromBranch + " into " + intoBranch, false);
            RefUpdate.Result result = update.update();
            if (result != RefUpdate.Result.NEW
                    && result != RefUpdate.Result.FAST_FORWARD
                    && result != RefUpdate.Result.FORCED) {
                throw new IOException("Could not update branch '" + intoBranch
                        + "' after semantic merge: " + result);
            }
            return commitId.name();
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
