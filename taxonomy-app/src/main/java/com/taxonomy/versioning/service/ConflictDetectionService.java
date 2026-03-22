package com.taxonomy.versioning.service;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.service.WorkspaceContext;
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

    private final DslGitRepositoryFactory repositoryFactory;

    public ConflictDetectionService(DslGitRepositoryFactory repositoryFactory) {
        this.repositoryFactory = repositoryFactory;
    }

    /**
     * Resolve the Git repository for the given workspace context.
     *
     * @param ctx the workspace context (use {@link WorkspaceContext#SHARED}
     *            for the system repository)
     * @return the resolved DslGitRepository
     */
    private DslGitRepository resolveRepository(WorkspaceContext ctx) {
        return repositoryFactory.resolveRepository(ctx);
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
     * <p>Uses the system repository (SHARED context). Use
     * {@link #previewMerge(String, String, WorkspaceContext)} for workspace-aware resolution.
     *
     * @param fromBranch the source branch
     * @param intoBranch the target branch
     * @return the merge preview result
     */
    public MergePreview previewMerge(String fromBranch, String intoBranch) {
        return previewMerge(fromBranch, intoBranch, WorkspaceContext.SHARED);
    }

    /**
     * Preview a merge to check for conflicts.
     *
     * @param fromBranch the source branch
     * @param intoBranch the target branch
     * @param ctx        the workspace context for repository resolution
     * @return the merge preview result
     */
    public MergePreview previewMerge(String fromBranch, String intoBranch, WorkspaceContext ctx) {
        try {
            var repo = resolveRepository(ctx).getGitRepository();
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
     * Details about a merge or cherry-pick conflict, including the content
     * from both sides so the UI can display a side-by-side resolution view.
     *
     * @param conflictType  "merge" or "cherry-pick"
     * @param oursLabel     label for "our" side (e.g. target branch name)
     * @param theirsLabel   label for "their" side (e.g. source branch name)
     * @param oursContent   DSL content from the target branch HEAD
     * @param theirsContent DSL content from the source branch HEAD (or cherry-pick commit)
     * @param baseContent   DSL content from the common ancestor (may be null)
     */
    public record ConflictDetails(
            String conflictType,
            String oursLabel,
            String theirsLabel,
            String oursContent,
            String theirsContent,
            String baseContent
    ) {}

    /**
     * Get conflict details for a merge that would conflict.
     *
     * <p>Uses the system repository (SHARED context). Use
     * {@link #getMergeConflictDetails(String, String, WorkspaceContext)} for workspace-aware resolution.
     *
     * @param fromBranch the source branch
     * @param intoBranch the target branch
     * @return conflict details, or null if no conflict detected
     */
    public ConflictDetails getMergeConflictDetails(String fromBranch, String intoBranch) {
        return getMergeConflictDetails(fromBranch, intoBranch, WorkspaceContext.SHARED);
    }

    /**
     * Get conflict details for a merge that would conflict.
     *
     * <p>Returns the DSL content from both sides so the UI can display
     * a side-by-side comparison for manual resolution.
     *
     * @param fromBranch the source branch
     * @param intoBranch the target branch
     * @param ctx        the workspace context for repository resolution
     * @return conflict details, or null if no conflict detected
     */
    public ConflictDetails getMergeConflictDetails(String fromBranch, String intoBranch, WorkspaceContext ctx) {
        try {
            MergePreview preview = previewMerge(fromBranch, intoBranch, ctx);
            if (preview.canMerge()) {
                return null; // No conflict
            }

            // Non-conflict failures (branch not found, errors) should not
            // be presented as conflicts — return null so the controller
            // responds with conflict:false and the UI shows a warning toast.
            if (preview.fromCommit() == null || preview.intoCommit() == null) {
                return null;
            }

            DslGitRepository repo = resolveRepository(ctx);
            String oursContent = repo.getDslAtHead(intoBranch);
            String theirsContent = repo.getDslAtHead(fromBranch);

            return new ConflictDetails(
                    "merge",
                    intoBranch,
                    fromBranch,
                    oursContent != null ? oursContent : "",
                    theirsContent != null ? theirsContent : "",
                    null
            );
        } catch (Exception e) {
            log.error("Failed to get merge conflict details '{}' → '{}'", fromBranch, intoBranch, e);
            return null;
        }
    }

    /**
     * Get conflict details for a cherry-pick that would conflict.
     *
     * <p>Uses the system repository (SHARED context). Use
     * {@link #getCherryPickConflictDetails(String, String, WorkspaceContext)} for workspace-aware resolution.
     *
     * @param commitId     the commit to cherry-pick
     * @param targetBranch the target branch
     * @return conflict details, or null if no conflict detected
     */
    public ConflictDetails getCherryPickConflictDetails(String commitId, String targetBranch) {
        return getCherryPickConflictDetails(commitId, targetBranch, WorkspaceContext.SHARED);
    }

    /**
     * Get conflict details for a cherry-pick that would conflict.
     *
     * @param commitId     the commit to cherry-pick
     * @param targetBranch the target branch
     * @param ctx          the workspace context for repository resolution
     * @return conflict details, or null if no conflict detected
     */
    public ConflictDetails getCherryPickConflictDetails(String commitId, String targetBranch, WorkspaceContext ctx) {
        try {
            CherryPickPreview preview = previewCherryPick(commitId, targetBranch, ctx);
            if (preview.canCherryPick()) {
                return null; // No conflict
            }

            // Non-conflict failures (missing target branch, invalid commit)
            // should not be presented as conflicts.
            if (preview.targetCommit() == null) {
                return null;
            }

            DslGitRepository repo = resolveRepository(ctx);
            String oursContent = repo.getDslAtHead(targetBranch);
            String theirsContent = repo.getDslAtCommit(commitId);

            return new ConflictDetails(
                    "cherry-pick",
                    targetBranch,
                    "commit " + commitId.substring(0, Math.min(7, commitId.length())),
                    oursContent != null ? oursContent : "",
                    theirsContent != null ? theirsContent : "",
                    null
            );
        } catch (Exception e) {
            log.error("Failed to get cherry-pick conflict details {} → '{}'", commitId, targetBranch, e);
            return null;
        }
    }

    /**
     * Preview a cherry-pick to check for conflicts.
     *
     * <p>Uses the system repository (SHARED context). Use
     * {@link #previewCherryPick(String, String, WorkspaceContext)} for workspace-aware resolution.
     *
     * @param commitId     the commit to cherry-pick
     * @param targetBranch the target branch
     * @return the cherry-pick preview result
     */
    public CherryPickPreview previewCherryPick(String commitId, String targetBranch) {
        return previewCherryPick(commitId, targetBranch, WorkspaceContext.SHARED);
    }

    /**
     * Preview a cherry-pick to check for conflicts.
     *
     * @param commitId     the commit to cherry-pick
     * @param targetBranch the target branch
     * @param ctx          the workspace context for repository resolution
     * @return the cherry-pick preview result
     */
    public CherryPickPreview previewCherryPick(String commitId, String targetBranch, WorkspaceContext ctx) {
        try {
            var repo = resolveRepository(ctx).getGitRepository();
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

                // Try three-way merge (dry run) — mirrors the merge order used
                // in DslGitRepository.cherryPick() for consistent conflict prediction
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
