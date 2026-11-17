package com.taxonomy.workspace.storage;

import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.workspace.service.BranchHeadConflictException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.ReflogReader;
import org.eclipse.jgit.lib.Repository;

import java.io.IOException;
import java.util.Objects;

/** A source tracking ref inside the destination repository, separate from all user branches. */
public record GitUpstreamRef(String repositoryId, String branch) {
    public GitUpstreamRef {
        if (repositoryId == null || repositoryId.isBlank() || repositoryId.indexOf(0) >= 0
                || branch == null || !Repository.isValidRefName(Constants.R_HEADS + branch)) {
            throw new IllegalArgumentException("A source repository and valid branch are required");
        }
    }

    public String name() {
        return Constants.R_REMOTES + "taxonomy/" + StableIdentityHash.sha256(repositoryId + "\u0000" + branch);
    }

    public String head(DslGitRepository destination) throws IOException {
        Ref ref = destination.readFreshRef(name());
        if (ref == null) return null;
        if (ref.isSymbolic() || ref.getObjectId() == null) {
            throw new IOException("Invalid Git upstream tracking ref");
        }
        return ref.getObjectId().name();
    }

    /** Only publish a previously copied and verified commit closure. */
    public void advance(DslGitRepository destination, String expected, String incoming, String actor) throws IOException {
        advance(destination, expected, incoming, actor, null, null);
    }

    /** Bind a journalled transfer to the native ref update, including its expected old commit. */
    public void advance(DslGitRepository destination, String expected, String incoming, String actor,
                        String publicationIdentity, String observedSourceHead) throws IOException {
        var repository = destination.getGitRepository();
        synchronized (repository) {
            String actual = head(destination);
            if (!Objects.equals(actual, expected)
                    || (expected != null && !destination.isAncestor(expected, incoming))) {
                throw new BranchHeadConflictException(name(), expected, actual, "Upstream tracking ref has changed");
            }
            // Detaching prevents an unexpected symbolic ref from moving a working branch.
            RefUpdate update = repository.updateRef(name(), true);
            update.setExpectedOldObjectId(expected == null ? ObjectId.zeroId() : ObjectId.fromString(expected));
            update.setNewObjectId(ObjectId.fromString(incoming));
            update.setForceUpdate(false);
            update.setRefLogIdent(new PersonIdent(actor, "taxonomy@localhost"));
            if (publicationIdentity == null) {
                update.setRefLogMessage("fetch: record verified upstream commit", true);
            } else {
                update.setForceRefLog(true);
                update.setRefLogMessage(publicationPrefix(publicationIdentity) + ObjectId.fromString(observedSourceHead).name(), false);
            }
            switch (update.update()) {
                case NEW, FAST_FORWARD, NO_CHANGE -> { }
                case LOCK_FAILURE, REJECTED, REJECTED_CURRENT_BRANCH ->
                        throw new BranchHeadConflictException(name(), expected, head(destination), "Upstream tracking ref has changed");
                default -> throw new IOException("Could not publish Git upstream tracking ref");
            }
        }
    }

    /** Read the source head verified when this receipt's exact tracking update was published. */
    public String publishedSourceHead(DslGitRepository destination, String expected, String incoming,
                                      String publicationIdentity) throws IOException {
        ReflogReader reader;
        try {
            reader = destination.getGitRepository().getReflogReader(name());
        } catch (UnsupportedOperationException unavailable) {
            return null;
        }
        if (reader == null) return null;
        ObjectId previous = expected == null ? ObjectId.zeroId() : ObjectId.fromString(expected);
        ObjectId candidate = ObjectId.fromString(incoming);
        String prefix = publicationPrefix(publicationIdentity);
        for (var entry : reader.getReverseEntries(64)) {
            String comment = entry.getComment();
            if (previous.equals(entry.getOldId()) && candidate.equals(entry.getNewId())
                    && comment != null && comment.startsWith(prefix)) {
                String observed = comment.substring(prefix.length());
                if (ObjectId.isId(observed)) return ObjectId.fromString(observed).name();
            }
        }
        return null;
    }

    private static String publicationPrefix(String publicationIdentity) {
        return "fetch: publish verified upstream operation " + StableIdentityHash.sha256(publicationIdentity) + " source ";
    }
}
