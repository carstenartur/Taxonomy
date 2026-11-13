package com.taxonomy.workspace.storage;

import com.taxonomy.workspace.service.BranchHeadConflictException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevTag;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Bounded transfer of one explicitly named lightweight or annotated tag. */
public final class GitSelectedTag {
    private static final int MAX_ANNOTATIONS = 8;
    private static final int MAX_ANNOTATION_BYTES = 256 * 1024;

    private record Annotation(ObjectId id, byte[] bytes) {}

    private final String name;
    private final String objectId;
    private final String commit;
    private final List<Annotation> annotations;

    private GitSelectedTag(String name, String objectId, String commit, List<Annotation> annotations) {
        this.name = name;
        this.objectId = objectId;
        this.commit = commit;
        this.annotations = annotations;
    }

    public String objectId() { return objectId; }
    public String commit() { return commit; }

    public static String objectAt(DslGitRepository repository, String name) throws IOException {
        if (name == null || !Repository.isValidRefName(Constants.R_TAGS + name)) {
            throw new IllegalArgumentException("A valid Git tag name is required");
        }
        var ref = repository.readFreshRef(Constants.R_TAGS + name);
        if (ref == null) return null;
        if (ref.isSymbolic() || ref.getObjectId() == null) throw new IOException("Invalid Git tag ref");
        return ref.getObjectId().name();
    }

    public static GitSelectedTag read(DslGitRepository source, String name) throws IOException {
        String tagId = objectAt(source, name);
        if (tagId == null) return null;
        var annotations = new ArrayList<Annotation>();
        ObjectId id = ObjectId.fromString(tagId);
        try (var reader = source.getGitRepository().newObjectReader()) {
            int type = ObjectReader.OBJ_ANY;
            while (true) {
                var object = reader.open(id, type);
                if (object.getType() == Constants.OBJ_COMMIT) {
                    return new GitSelectedTag(name, tagId, id.name(), annotations);
                }
                if (object.getType() != Constants.OBJ_TAG || annotations.size() == MAX_ANNOTATIONS
                        || object.getSize() > MAX_ANNOTATION_BYTES) {
                    throw new IllegalArgumentException("The selected tag is not a supported commit tag");
                }
                byte[] bytes = object.getBytes(MAX_ANNOTATION_BYTES);
                annotations.add(new Annotation(id, bytes));
                var next = RevTag.parse(bytes).getObject();
                id = next.copy();
                type = next.getType();
            }
        }
    }

    /** Confirm this exact ref without recreating a missing tag or overwriting another writer. */
    public void verifyRetained(DslGitRepository target) throws IOException {
        var repository = target.getGitRepository();
        synchronized (repository) {
            String current = objectAt(target, name);
            if (objectId.equals(current)) {
                var update = repository.updateRef(Constants.R_TAGS + name, true);
                update.setExpectedOldObjectId(ObjectId.fromString(objectId));
                update.setNewObjectId(ObjectId.fromString(objectId));
                update.disableRefLog();
                if (update.update() == RefUpdate.Result.NO_CHANGE) return;
                current = objectAt(target, name);
            }
            throw new BranchHeadConflictException(name, objectId, current, "The retained destination tag changed");
        }
    }

    /** The caller has verified that the commit belongs to the target's tracked history. */
    public void publish(DslGitRepository target, String actor) throws IOException {
        publish(target, actor, () -> { });
    }

    @FunctionalInterface
    public interface PublicationCheck { void verify() throws IOException; }

    public void publish(DslGitRepository target, String actor, PublicationCheck beforePublication) throws IOException {
        var repository = target.getGitRepository();
        synchronized (repository) {
            String current = objectAt(target, name);
            if (objectId.equals(current)) {
                beforePublication.verify();
                verifyRetained(target);
                return;
            }
            if (current != null) {
                throw new BranchHeadConflictException(name, null, current, "An independent destination tag already exists");
            }
            try (var writer = repository.newObjectInserter()) {
                for (Annotation entry : annotations) {
                    if (!writer.insert(Constants.OBJ_TAG, entry.bytes()).equals(entry.id())) {
                        throw new IOException("Transferred tag object identity mismatch");
                    }
                }
                writer.flush();
            }
            var update = repository.updateRef(Constants.R_TAGS + name, true);
            update.setExpectedOldObjectId(ObjectId.zeroId());
            update.setNewObjectId(ObjectId.fromString(objectId));
            update.setForceUpdate(false);
            update.setRefLogIdent(new PersonIdent(actor, "taxonomy@localhost"));
            update.setRefLogMessage("fetch: import selected upstream tag", true);
            beforePublication.verify();
            RefUpdate.Result result = update.update();
            if (result == RefUpdate.Result.NEW || result == RefUpdate.Result.NO_CHANGE) return;
            if (result == RefUpdate.Result.LOCK_FAILURE || result == RefUpdate.Result.REJECTED) {
                throw new BranchHeadConflictException(name, null, objectAt(target, name), "Destination tag changed during transfer");
            }
            throw new IOException("Could not publish the selected upstream tag");
        }
    }
}
