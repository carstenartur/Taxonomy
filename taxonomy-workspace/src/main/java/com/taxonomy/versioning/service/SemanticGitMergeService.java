package com.taxonomy.versioning.service;

import com.taxonomy.dsl.merge.TaxDslMergeResult;
import com.taxonomy.dsl.merge.TaxDslSemanticMerger;
import com.taxonomy.workspace.storage.DslGitRepository;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        Repository git = repository.getGitRepository();
        ObjectId targetHeadBefore = branchHead(git, intoBranch);
        String ordinaryCommit = repository.merge(fromBranch, intoBranch);
        if (ordinaryCommit != null) {
            String effectiveCommit = applyRequestedMergeMessage(
                    git,
                    intoBranch,
                    targetHeadBefore,
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

        MergeCommitResult committed = createMergeCommit(
                git,
                inputs.baseCommit(),
                inputs.oursCommit(),
                inputs.theirsCommit(),
                intoBranch,
                fromBranch,
                merge.mergedText(),
                author,
                requestedMessage);
        if (!committed.conflicts().isEmpty()) {
            return new MergeOutcome(false, null, true, committed.conflicts(), merge);
        }
        return new MergeOutcome(true, committed.commitId(), true, List.of(), merge);
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
            return new SemanticInputs(baseDsl, oursDsl, theirsDsl, base, ours, theirs, null);
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
     * When a caller explicitly requested a message, replace only a two-parent
     * merge commit created by this invocation. Fast-forward and already-merged
     * results keep their existing commit identity and message.
     */
    private static String applyRequestedMergeMessage(Repository repository,
                                                     String intoBranch,
                                                     ObjectId targetHeadBefore,
                                                     String commitId,
                                                     String authorName,
                                                     String requestedMessage) throws IOException {
        if (requestedMessage == null) {
            return commitId;
        }

        ObjectId originalId = ObjectId.fromString(commitId);
        if (originalId.equals(targetHeadBefore)) {
            return commitId;
        }
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

    private static MergeCommitResult createMergeCommit(Repository repository,
                                            RevCommit base,
                                            RevCommit ours,
                                            RevCommit theirs,
                                            String intoBranch,
                                            String fromBranch,
                                            String mergedDsl,
                                            String authorName,
                                            String requestedMessage) throws IOException {
        PersonIdent actor = actor(authorName);

        try (ObjectInserter inserter = repository.newObjectInserter()) {
            ObjectId dslBlob = inserter.insert(Constants.OBJ_BLOB,
                    mergedDsl.getBytes(StandardCharsets.UTF_8));
            TreeMerge tree = mergeTrees(repository, inserter, base, ours, theirs, dslBlob);
            if (!tree.conflicts().isEmpty()) {
                return new MergeCommitResult(null, tree.conflicts());
            }

            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(tree.treeId());
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
            return new MergeCommitResult(commitId.name(), List.of());
        }
    }

    private static TreeMerge mergeTrees(Repository repository,
                                        ObjectInserter inserter,
                                        RevCommit base,
                                        RevCommit ours,
                                        RevCommit theirs,
                                        ObjectId mergedDsl) throws IOException {
        Map<String, TreeEntry> baseFiles = readTree(repository, base);
        Map<String, TreeEntry> ourFiles = readTree(repository, ours);
        Map<String, TreeEntry> theirFiles = readTree(repository, theirs);
        Set<String> paths = new HashSet<>();
        paths.addAll(baseFiles.keySet());
        paths.addAll(ourFiles.keySet());
        paths.addAll(theirFiles.keySet());

        Map<String, TreeEntry> mergedFiles = new HashMap<>();
        List<String> conflicts = new ArrayList<>();
        for (String path : paths) {
            if (DSL_FILE.equals(path)) continue;
            TreeEntry baseEntry = baseFiles.get(path);
            TreeEntry ourEntry = ourFiles.get(path);
            TreeEntry theirEntry = theirFiles.get(path);
            TreeEntry mergedEntry;
            if (same(ourEntry, theirEntry)) {
                mergedEntry = ourEntry;
            } else if (same(baseEntry, ourEntry)) {
                mergedEntry = theirEntry;
            } else if (same(baseEntry, theirEntry)) {
                mergedEntry = ourEntry;
            } else {
                conflicts.add("file " + path);
                continue;
            }
            if (mergedEntry != null) mergedFiles.put(path, mergedEntry);
        }
        if (!conflicts.isEmpty()) return new TreeMerge(null, List.copyOf(conflicts));

        mergedFiles.put(DSL_FILE, new TreeEntry(FileMode.REGULAR_FILE, mergedDsl));
        List<String> orderedPaths = mergedFiles.keySet().stream().sorted().toList();
        for (int i = 0; i < orderedPaths.size(); i++) {
            String path = orderedPaths.get(i);
            for (int j = i + 1; j < orderedPaths.size(); j++) {
                if (orderedPaths.get(j).startsWith(path + "/")) {
                    conflicts.add("file/directory conflict at " + path);
                    break;
                }
                if (!orderedPaths.get(j).startsWith(path)) break;
            }
        }
        if (!conflicts.isEmpty()) return new TreeMerge(null, List.copyOf(conflicts));

        DirCache cache = DirCache.newInCore();
        var builder = cache.builder();
        List<DirCacheEntry> entries = mergedFiles.entrySet().stream()
                .map(entry -> {
                    DirCacheEntry cacheEntry = new DirCacheEntry(entry.getKey());
                    cacheEntry.setFileMode(entry.getValue().mode());
                    cacheEntry.setObjectId(entry.getValue().objectId());
                    return cacheEntry;
                })
                .sorted((left, right) -> Arrays.compareUnsigned(left.getRawPath(), right.getRawPath()))
                .toList();
        entries.forEach(builder::add);
        builder.finish();
        return new TreeMerge(cache.writeTree(inserter), List.of());
    }

    private static Map<String, TreeEntry> readTree(Repository repository, RevCommit commit) throws IOException {
        if (commit == null) return Map.of();
        Map<String, TreeEntry> entries = new HashMap<>();
        try (ObjectReader reader = repository.newObjectReader();
             TreeWalk tree = new TreeWalk(reader)) {
            tree.addTree(commit.getTree());
            tree.setRecursive(true);
            while (tree.next()) {
                entries.put(tree.getPathString(), new TreeEntry(tree.getFileMode(0), tree.getObjectId(0)));
            }
        }
        return entries;
    }

    private static boolean same(TreeEntry left, TreeEntry right) {
        if (left == right) return true;
        return left != null && right != null
                && left.mode().getBits() == right.mode().getBits()
                && left.objectId().equals(right.objectId());
    }

    private static ObjectId branchHead(Repository repository,
                                       String branch) throws IOException {
        Ref ref = repository.getRefDatabase().exactRef(Constants.R_HEADS + branch);
        return ref != null ? ref.getObjectId() : null;
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
            RevCommit baseCommit,
            RevCommit oursCommit,
            RevCommit theirsCommit,
            String error) {
        static SemanticInputs error(String error) {
            return new SemanticInputs(null, null, null, null, null, null, error);
        }
    }

    private record TreeEntry(FileMode mode, ObjectId objectId) { }

    private record TreeMerge(ObjectId treeId, List<String> conflicts) { }

    private record MergeCommitResult(String commitId, List<String> conflicts) { }
}
