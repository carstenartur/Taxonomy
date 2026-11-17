package com.taxonomy.workspace.storage;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.TagBuilder;
import org.eclipse.jgit.revwalk.RevWalk;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Objects;

/** A retained tag keeps the exact replay reachable across the Git/SQL commit boundary. */
public record PersonalRebaseReceipt(String repository, String workspace, String branch, String sourceBranch,
        String forkPoint, String owner, String ownerPrincipal, String tracked, String portfolioDigest,
        String previousCurrent, String previousIntegrated, DslGitRepository.RebaseReplay replay) {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static String ref(String scope) { return "refs/taxonomy/personal-rebase/" + scope; }

    public static PersonalRebaseReceipt read(DslGitRepository target, String scope) throws IOException {
        var stored = target.readFreshRef(ref(scope));
        if (stored == null) return null;
        try (var walk = new RevWalk(target.getGitRepository())) {
            if (stored.isSymbolic()) throw new IOException("Invalid personal rebase receipt");
            var tag = walk.parseTag(stored.getObjectId());
            var value = JSON.readValue(tag.getFullMessage(), PersonalRebaseReceipt.class);
            if (!scope.equals(tag.getTagName()) || !value.replay.completed()
                    || !value.replay.newHead().equals(tag.getObject().name()))
                throw new IOException("Invalid personal rebase receipt");
            return value;
        }
    }

    /** Both persisted workspace pointers cover this result, even if later commits followed it. */
    public boolean acknowledgedBy(DslGitRepository target, String current, String integrated) throws IOException {
        return contains(target, replay.newHead(), current) && contains(target, tracked, integrated);
    }

    private static boolean contains(DslGitRepository target, String ancestor, String commit) throws IOException {
        return commit != null && (ancestor.equals(commit) || target.isAncestor(ancestor, commit));
    }

    public void retain(DslGitRepository target, String scope) throws IOException {
        var repo = target.getGitRepository();
        try (var insert = repo.newObjectInserter()) {
            var tag = new TagBuilder();
            tag.setTag(scope);
            tag.setTagger(new PersonIdent(owner, owner + "@taxonomy.local"));
            tag.setObjectId(ObjectId.fromString(replay.newHead()), Constants.OBJ_COMMIT);
            tag.setMessage(JSON.writeValueAsString(this));
            var id = insert.insert(tag);
            insert.flush();
            var update = repo.updateRef(ref(scope));
            update.setExpectedOldObjectId(ObjectId.zeroId());
            update.setNewObjectId(id);
            if (update.update() != RefUpdate.Result.NEW) throw new IOException("Another personal rebase is pending");
        }
    }

    public void publish(DslGitRepository target) throws IOException {
        ExpectedHeadDslCommitter.replaceBranchHead(target.getGitRepository(), branch, ObjectId.fromString(replay.originalHead()),
                ObjectId.fromString(replay.newHead()), new PersonIdent(owner, owner + "@taxonomy.local"),
                "rebase: " + replay.originalHead() + " onto " + tracked);
    }

    public void release(DslGitRepository target, String scope) throws IOException {
        var stored = target.readFreshRef(ref(scope));
        if (stored == null || !Objects.equals(this, read(target, scope))) return;
        var update = target.getGitRepository().updateRef(ref(scope));
        update.setExpectedOldObjectId(stored.getObjectId());
        update.setForceUpdate(true);
        var result = update.delete();
        if (result != RefUpdate.Result.FORCED && result != RefUpdate.Result.NO_CHANGE)
            throw new IOException("Could not release the personal rebase receipt");
    }
}
