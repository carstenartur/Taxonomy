package com.taxonomy.workspace.storage;

import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.ObjectWalk;
import org.eclipse.jgit.revwalk.RevObject;

import java.io.IOException;
import java.util.List;

/** Copies a captured commit closure using public JGit APIs, without creating any acceptance ref. */
public final class GitHistoryTransfer {
    private static final int MAX_OBJECTS = 1_000_000;
    private static final long MAX_BYTES = 1024L * 1024 * 1024;
    private GitHistoryTransfer() {}

    /** Accepted heads must be captured from the destination before import; staging refs are never trust boundaries. */
    public static long copyCommitClosure(DslGitRepository source, DslGitRepository destination,
                                         String commit, List<String> acceptedHeads) throws IOException {
        ObjectId selected = ObjectId.fromString(commit);
        List<String> accepted = List.copyOf(acceptedHeads);
        Repository from = source.getGitRepository(), to = destination.getGitRepository();
        long[] budget = {0, 0};
        // The same public object APIs support Hibernate-backed and explicit in-memory repositories.
        synchronized (to) {
            try (ObjectWalk walk = new ObjectWalk(from); ObjectReader reader = from.newObjectReader(); ObjectInserter writer = to.newObjectInserter()) {
                walk.markStart(walk.parseCommit(selected));
                for (String head : accepted) {
                    ObjectId id = ObjectId.fromString(head);
                    if (!to.getObjectDatabase().has(id)) throw new IOException("Captured acceptance boundary is unavailable");
                    if (from.getObjectDatabase().has(id)) walk.markUninteresting(walk.parseCommit(id));
                }
                RevObject object;
                while ((object = walk.next()) != null) copy(reader, writer, to, object, budget);
                while ((object = walk.nextObject()) != null) copy(reader, writer, to, object, budget);
                writer.flush();
            }
        }
        // Check every newly introduced ancestor, including all sides of merges, under destination policy.
        destination.verifyIncomingFromAcceptedHeads(commit, accepted);
        return budget[1];
    }

    private static void copy(ObjectReader reader, ObjectInserter writer, Repository destination, RevObject object, long[] budget) throws IOException {
        if (++budget[0] > MAX_OBJECTS) throw new IOException("History transfer exceeds the object budget");
        if (destination.getObjectDatabase().has(object)) return;
        ObjectLoader loader = reader.open(object);
        if (loader.getSize() > 64L * 1024 * 1024 || budget[1] + loader.getSize() > MAX_BYTES)
            throw new IOException("History transfer exceeds the byte budget");
        try (ObjectStream stream = loader.openStream()) {
            ObjectId copied = writer.insert(loader.getType(), loader.getSize(), stream);
            if (!copied.equals(object)) throw new IOException("Transferred Git object identity mismatch");
        }
        budget[1] += loader.getSize();
    }
}
