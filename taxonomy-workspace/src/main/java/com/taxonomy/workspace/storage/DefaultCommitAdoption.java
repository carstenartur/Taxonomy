package com.taxonomy.workspace.storage;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import java.io.IOException;

/** One ordered parent-to-commit change, prepared without moving the user's branch. */
public record DefaultCommitAdoption(String result, String candidate, String detail) {
    /** Find the first new first-parent commit. Never infer a cursor following a force push. */
    public static String next(DslGitRepository git, String cursor, String tip) throws IOException {
        if (tip == null || cursor == null)
            throw new IllegalArgumentException("Source branch or cursor unavailable");
        if (tip.equals(cursor))
            return null;
        try (var walk = new RevWalk(git.getGitRepository())) {
            var commit = walk.parseCommit(ObjectId.fromString(tip));
            for (int count = 0; count < 4096 && commit.getParentCount() > 0; count++) {
                if (cursor.equals(commit.getParent(0).name()))
                    return commit.name();
                commit = walk.parseCommit(commit.getParent(0));
            }
            throw new IllegalArgumentException("Source history moved or exceeds the inspection limit");
        }
    }

    public static boolean related(DslGitRepository git, String target, String source) throws IOException {
        try (var walk = new RevWalk(git.getGitRepository())) {
            walk.setRevFilter(org.eclipse.jgit.revwalk.filter.RevFilter.MERGE_BASE);
            walk.markStart(walk.parseCommit(ObjectId.fromString(target)));
            walk.markStart(walk.parseCommit(ObjectId.fromString(source)));
            return walk.next() != null && walk.next() == null;
        }
    }

    /** One retention ref per rule keeps a prepared result reachable across SQL rollback, restart and Git GC. */
    public static void retain(DslGitRepository git, String ruleId, String candidate) throws IOException {
        if (!ruleId.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid adoption rule identity");
        String ref = "refs/taxonomy/branch-adoption/" + ruleId;
        var old = git.readFreshRef(ref);
        if (old != null && old.isSymbolic())
            throw new IOException("Invalid adoption retention ref");
        var update = git.getGitRepository().updateRef(ref);
        update.setExpectedOldObjectId(old == null ? ObjectId.zeroId() : old.getObjectId());
        update.setNewObjectId(ObjectId.fromString(candidate));
        update.setForceUpdate(true);
        switch (update.update()) {
        case NEW, FAST_FORWARD, FORCED, NO_CHANGE -> {
        }
        default -> throw new IOException("Could not retain the prepared adoption");
        }
    }

    static boolean sameContent(DslGitRepository git, ObjectId left, ObjectId right) throws IOException {
        try (var diff = new TreeWalk(git.getGitRepository())) {
            diff.addTree(left);
            diff.addTree(right);
            diff.setRecursive(true);
            diff.setFilter(TreeFilter.ANY_DIFF);
            while (diff.next())
                if (!CommitProvenance.FILENAME.equals(diff.getPathString()))
                    return false;
            return true;
        }
    }
}
