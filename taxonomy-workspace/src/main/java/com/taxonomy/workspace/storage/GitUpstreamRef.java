package com.taxonomy.workspace.storage;

import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.workspace.service.BranchHeadConflictException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
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
            update.setRefLogMessage("fetch: record verified upstream commit", true);
            switch (update.update()) {
                case NEW, FAST_FORWARD, NO_CHANGE -> { }
                case LOCK_FAILURE, REJECTED, REJECTED_CURRENT_BRANCH ->
                        throw new BranchHeadConflictException(name(), expected, head(destination), "Upstream tracking ref has changed");
                default -> throw new IOException("Could not publish Git upstream tracking ref");
            }
        }
    }
}
