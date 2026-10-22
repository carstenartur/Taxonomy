package com.taxonomy.versioning.service;

import com.taxonomy.dsl.merge.TaxDslMergeResult;
import com.taxonomy.dsl.merge.TaxDslSemanticMerger;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.CommitProvenance;
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
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.ResolveMerger;
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
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

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
        String ordinaryCommit = repository.merge(fromBranch, intoBranch,
                requestedMessage == null ? null : author, requestedMessage);
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

        MergeCommitResult committed = createMergeCommit(
                repository,
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
        if (!merge.isSuccessful()) {
            return new MergeOutcome(false, null, true, merge.conflictIdentifiers(), merge);
        }
        // Like the ordinary in-core Git preview, this may create unreferenced
        // objects, but never changes a branch, index, or working tree.
        try (ObjectInserter inserter = repository.getGitRepository().newObjectInserter()) {
            ObjectId dslBlob = inserter.insert(Constants.OBJ_BLOB,
                    merge.mergedText().getBytes(StandardCharsets.UTF_8));
            TreeMerge tree = mergeTrees(repository.getGitRepository(), inserter,
                    inputs.baseCommit(), inputs.oursCommit(), inputs.theirsCommit(), dslBlob);
            return new MergeOutcome(tree.conflicts().isEmpty(), null, true, tree.conflicts(), merge);
        }
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

    private static MergeCommitResult createMergeCommit(DslGitRepository source,
                                            RevCommit base,
                                            RevCommit ours,
                                            RevCommit theirs,
                                            String intoBranch,
                                            String fromBranch,
                                            String mergedDsl,
                                            String authorName,
                                            String requestedMessage) throws IOException {
        Repository repository = source.getGitRepository();
        source.verifyIncoming(theirs.name(), ours.name());
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
            source.signCommit(commit, intoBranch);
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
        // Merge ordinary auxiliary files independently of the semantic DSL.
        // Transfer evidence belongs to each original signed parent, not to the
        // new merge object, so it must not participate in textual file merging.
        ObjectId baseTree = auxiliaryTree(repository, inserter, base);
        ObjectId ourTree = auxiliaryTree(repository, inserter, ours);
        ObjectId theirTree = auxiliaryTree(repository, inserter, theirs);
        inserter.flush();
        ResolveMerger merger = (ResolveMerger) MergeStrategy.RESOLVE.newMerger(repository, true);
        merger.setBase(baseTree);
        if (!merger.merge(ourTree, theirTree)) {
            var paths = new TreeSet<>(merger.getUnmergedPaths());
            if (merger.getFailingPaths() != null) paths.addAll(merger.getFailingPaths().keySet());
            List<String> conflicts = paths.stream().map(path -> "file " + path).toList();
            return new TreeMerge(null, conflicts.isEmpty()
                    ? List.of("Auxiliary files could not be merged") : conflicts);
        }

        Map<String, TreeEntry> mergedFiles = readTree(repository, merger.getResultTreeId());
        List<String> conflicts = new ArrayList<>();
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

        return new TreeMerge(writeTree(inserter, mergedFiles), List.of());
    }

    private static ObjectId auxiliaryTree(Repository repository,
                                          ObjectInserter inserter,
                                          RevCommit commit) throws IOException {
        Map<String, TreeEntry> files = readTree(repository, commit == null ? null : commit.getTree());
        files.remove(DSL_FILE);
        files.remove(CommitProvenance.FILENAME);
        return writeTree(inserter, files);
    }

    private static ObjectId writeTree(ObjectInserter inserter,
                                      Map<String, TreeEntry> files) throws IOException {
        DirCache cache = DirCache.newInCore();
        var builder = cache.builder();
        List<DirCacheEntry> entries = files.entrySet().stream()
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
        return cache.writeTree(inserter);
    }

    private static Map<String, TreeEntry> readTree(Repository repository, ObjectId treeId) throws IOException {
        Map<String, TreeEntry> entries = new HashMap<>();
        if (treeId == null) return entries;
        try (ObjectReader reader = repository.newObjectReader();
             TreeWalk tree = new TreeWalk(reader)) {
            tree.addTree(treeId);
            tree.setRecursive(true);
            while (tree.next()) {
                entries.put(tree.getPathString(), new TreeEntry(tree.getFileMode(0), tree.getObjectId(0)));
            }
        }
        return entries;
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
