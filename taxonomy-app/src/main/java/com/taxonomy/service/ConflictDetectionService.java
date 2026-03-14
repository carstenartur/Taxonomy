package com.taxonomy.service;

import com.taxonomy.dsl.storage.DslGitRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.ThreeWayMerger;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * Detects potential conflicts before merge or cherry-pick operations.
 *
 * <p>Provides preview functionality so the UI can warn the user before
 * they start an operation that would fail.
 */
@Service
public class ConflictDetectionService {

    private static final Logger log = LoggerFactory.getLogger(ConflictDetectionService.class);

    private final DslGitRepository gitRepository;

    public ConflictDetectionService(DslGitRepository gitRepository) {
        this.gitRepository = gitRepository;
    }

    /**
     * Result of a merge preview.
     *
     * @param canMerge         whether the merge would succeed without conflicts
     * @param fromBranch       the source branch
     * @param intoBranch       the target branch
     * @param fromCommit       HEAD commit of source branch
     * @param intoCommit       HEAD commit of target branch
     * @param alreadyMerged    true if source is already merged into target
     * @param fastForwardable  true if a fast-forward merge is possible
     * @param warnings         any warnings about the merge
     */
    public record MergePreview(
            boolean canMerge,
            String fromBranch,
            String intoBranch,
            String fromCommit,
            String intoCommit,
            boolean alreadyMerged,
            boolean fastForwardable,
            List<String> warnings
    ) {}

    /**
     * Result of a cherry-pick preview.
     *
     * @param canCherryPick  whether the cherry-pick would succeed without conflicts
     * @param commitId       the commit to cherry-pick
     * @param targetBranch   the target branch
     * @param targetCommit   HEAD commit of target branch
     * @param warnings       any warnings about the cherry-pick
     */
    public record CherryPickPreview(
            boolean canCherryPick,
            String commitId,
            String targetBranch,
            String targetCommit,
            List<String> warnings
    ) {}

    /**
     * Preview a merge to check for conflicts.
     *
     * @param fromBranch the source branch
     * @param intoBranch the target branch
     * @return the merge preview result
     */
    public MergePreview previewMerge(String fromBranch, String intoBranch) {
        try {
            var repo = gitRepository.getGitRepository();
            String fromRefName = Constants.R_HEADS + fromBranch;
            String intoRefName = Constants.R_HEADS + intoBranch;

            Ref fromRef = repo.getRefDatabase().exactRef(fromRefName);
            Ref intoRef = repo.getRefDatabase().exactRef(intoRefName);

            if (fromRef == null) {
                return new MergePreview(false, fromBranch, intoBranch, null, null,
                        false, false, List.of("Source branch '" + fromBranch + "' not found"));
            }
            if (intoRef == null) {
                return new MergePreview(false, fromBranch, intoBranch, null, null,
                        false, false, List.of("Target branch '" + intoBranch + "' not found"));
            }

            String fromCommit = fromRef.getObjectId().name();
            String intoCommit = intoRef.getObjectId().name();

            try (RevWalk walk = new RevWalk(repo)) {
                RevCommit fromRev = walk.parseCommit(fromRef.getObjectId());
                RevCommit intoRev = walk.parseCommit(intoRef.getObjectId());

                // Check if already merged
                if (walk.isMergedInto(fromRev, intoRev)) {
                    return new MergePreview(true, fromBranch, intoBranch, fromCommit, intoCommit,
                            true, false, List.of("Already merged: '" + fromBranch + "' is ancestor of '" + intoBranch + "'"));
                }

                // Check fast-forward
                if (walk.isMergedInto(intoRev, fromRev)) {
                    return new MergePreview(true, fromBranch, intoBranch, fromCommit, intoCommit,
                            false, true, List.of());
                }

                // Try three-way merge (dry run)
                ThreeWayMerger merger = MergeStrategy.RECURSIVE.newMerger(repo, true);
                boolean success = merger.merge(intoRev, fromRev);

                if (success) {
                    return new MergePreview(true, fromBranch, intoBranch, fromCommit, intoCommit,
                            false, false, List.of());
                } else {
                    return new MergePreview(false, fromBranch, intoBranch, fromCommit, intoCommit,
                            false, false, List.of("Merge would result in conflicts"));
                }
            }
        } catch (IOException e) {
            log.error("Failed to preview merge '{}' → '{}'", fromBranch, intoBranch, e);
            return new MergePreview(false, fromBranch, intoBranch, null, null,
                    false, false, List.of("Error: " + e.getMessage()));
        }
    }

    /**
     * Preview a cherry-pick to check for conflicts.
     *
     * @param commitId     the commit to cherry-pick
     * @param targetBranch the target branch
     * @return the cherry-pick preview result
     */
    public CherryPickPreview previewCherryPick(String commitId, String targetBranch) {
        try {
            var repo = gitRepository.getGitRepository();
            String targetRefName = Constants.R_HEADS + targetBranch;
            Ref targetRef = repo.getRefDatabase().exactRef(targetRefName);

            if (targetRef == null) {
                return new CherryPickPreview(false, commitId, targetBranch, null,
                        List.of("Target branch '" + targetBranch + "' not found"));
            }

            String targetCommit = targetRef.getObjectId().name();

            try (RevWalk walk = new RevWalk(repo)) {
                RevCommit pickCommit = walk.parseCommit(
                        org.eclipse.jgit.lib.ObjectId.fromString(commitId));
                RevCommit targetHead = walk.parseCommit(targetRef.getObjectId());

                // Try three-way merge (dry run)
                ThreeWayMerger merger = MergeStrategy.RECURSIVE.newMerger(repo, true);
                boolean success = merger.merge(targetHead, pickCommit);

                if (success) {
                    return new CherryPickPreview(true, commitId, targetBranch, targetCommit,
                            List.of());
                } else {
                    return new CherryPickPreview(false, commitId, targetBranch, targetCommit,
                            List.of("Cherry-pick would result in conflicts"));
                }
            }
        } catch (Exception e) {
            log.error("Failed to preview cherry-pick {} → '{}'", commitId, targetBranch, e);
            return new CherryPickPreview(false, commitId, targetBranch, null,
                    List.of("Error: " + e.getMessage()));
        }
    }
}
