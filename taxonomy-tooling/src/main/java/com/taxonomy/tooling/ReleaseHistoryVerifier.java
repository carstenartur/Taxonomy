package com.taxonomy.tooling;

import java.nio.file.Path;
import java.util.List;

/** Verifies immutable release sources across GitHub's commit-ID-rewriting rebase merge. */
final class ReleaseHistoryVerifier {
    private ReleaseHistoryVerifier() {
    }

    /** Verify the entire two-commit handoff, including both trees and the unchanged base. */
    static String verifyHandoff(Path root, String releaseRef, String nextRef,
            String baseRef, String mainRef) {
        String release = commit(root, releaseRef);
        String next = commit(root, nextRef);
        String base = commit(root, baseRef);
        String main = commit(root, mainRef);
        if (!parent(root, next).equals(release) || !parent(root, release).equals(base)) {
            throw new IllegalArgumentException(
                    "Staging must contain exactly two single-parent commits: release and snapshot");
        }
        String mappedRelease = parent(root, main);
        if (!parent(root, mappedRelease).equals(base)) {
            throw new IllegalArgumentException(
                    "Main must contain exactly two single-parent commits above the expected base");
        }
        requireSameTree(root, release, mappedRelease, "release tree");
        requireSameTree(root, next, main, "snapshot tree");
        return mappedRelease;
    }

    /** Accept legacy ancestry or the exact release tree rebased directly onto its original parent. */
    static String verifyRelease(Path root, String releaseRef, String mainRef) {
        String release = commit(root, releaseRef);
        String main = commit(root, mainRef);
        if (isAncestor(root, release, main)) {
            return release;
        }
        String base = parent(root, release);
        if (!isAncestor(root, base, main)) {
            throw new IllegalArgumentException("Release base is not an ancestor of main");
        }
        List<String> commits = GitSupport.require(root, "rev-list", "--first-parent",
                "--reverse", base + ".." + main).lines().toList();
        if (commits.isEmpty()) {
            throw new IllegalArgumentException("Main does not contain the release tree");
        }
        String mappedRelease = commits.getFirst();
        if (!parent(root, mappedRelease).equals(base)) {
            throw new IllegalArgumentException("Rebased release must retain its exact original base");
        }
        requireSameTree(root, release, mappedRelease, "release tree");
        return mappedRelease;
    }

    private static String commit(Path root, String ref) {
        return GitSupport.require(root, "rev-parse", "--verify", "--end-of-options",
                ref + "^{commit}").strip();
    }

    private static String parent(Path root, String commit) {
        String[] lineage = GitSupport.require(root, "rev-list", "--parents", "-n", "1",
                commit).strip().split("\\s+");
        if (lineage.length != 2) {
            throw new IllegalArgumentException(
                    "Release handoff requires two single-parent commits; invalid parent count for "
                            + commit);
        }
        return lineage[1];
    }

    private static void requireSameTree(Path root, String expected, String actual, String label) {
        String expectedTree = GitSupport.require(root, "rev-parse", expected + "^{tree}").strip();
        String actualTree = GitSupport.require(root, "rev-parse", actual + "^{tree}").strip();
        if (!expectedTree.equals(actualTree)) {
            throw new IllegalArgumentException("Rebased " + label + " differs from the verified source");
        }
    }

    private static boolean isAncestor(Path root, String ancestor, String descendant) {
        GitSupport.Result result = GitSupport.run(root, "merge-base", "--is-ancestor",
                ancestor, descendant);
        if (result.exitCode() == 0) {
            return true;
        }
        if (result.exitCode() == 1) {
            return false;
        }
        throw new IllegalArgumentException("Cannot verify release ancestry: " + result.stderr().strip());
    }
}
