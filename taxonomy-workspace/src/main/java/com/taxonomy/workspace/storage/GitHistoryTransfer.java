package com.taxonomy.workspace.storage;

import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.ObjectWalk;
import org.eclipse.jgit.revwalk.RevObject;

import java.io.IOException;
import java.util.List;

/** Copies a captured commit closure using public JGit APIs, without creating any acceptance ref. */
public final class GitHistoryTransfer {
    public static final int MAX_OBJECT_BYTES = 64 * 1024 * 1024;
    private static final int MAX_OBJECTS = 1_000_000;
    private static final long MAX_BYTES = 1024L * 1024 * 1024;
    private static final Limits DEFAULT_LIMITS = new Limits(MAX_OBJECTS, MAX_OBJECT_BYTES, MAX_BYTES);
    /** Callers may impose smaller budgets while the public transfer always uses the production limits. */
    record Limits(int maxObjects, long maxObjectBytes, long maxBytes) {
        Limits {
            if (maxObjects < 1 || maxObjects > MAX_OBJECTS || maxObjectBytes < 1 || maxObjectBytes > MAX_OBJECT_BYTES
                    || maxBytes < 1 || maxBytes > MAX_BYTES) {
                throw new IllegalArgumentException("Transfer limits must fit within the production budget");
            }
        }
    }

    private GitHistoryTransfer() {}

    /** Accepted heads must be captured from the destination before import; staging refs are never trust boundaries. */
    public static long copyCommitClosure(DslGitRepository source, DslGitRepository destination,
                                         String commit, List<String> acceptedHeads) throws IOException {
        return copyCommitClosure(source, destination, commit, acceptedHeads, DEFAULT_LIMITS);
    }

    static long copyCommitClosure(DslGitRepository source, DslGitRepository destination, String commit,
                                  List<String> acceptedHeads, Limits limits) throws IOException {
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
                while ((object = walk.next()) != null) copy(reader, writer, to, object, budget, limits);
                while ((object = walk.nextObject()) != null) copy(reader, writer, to, object, budget, limits);
                writer.flush();
            }
            try (ObjectWalk verification = new ObjectWalk(to)) {
                verification.markStart(verification.parseCommit(selected));
                for (String head : accepted) {
                    verification.markUninteresting(verification.parseCommit(ObjectId.fromString(head)));
                }
                verification.checkConnectivity();
            }
        }
        // Check every newly introduced ancestor, including all sides of merges, under destination policy.
        destination.verifyIncomingFromAcceptedHeads(commit, accepted);
        return budget[1];
    }

    private static void copy(ObjectReader reader, ObjectInserter writer, Repository destination, RevObject object,
                             long[] budget, Limits limits) throws IOException {
        if (++budget[0] > limits.maxObjects()) throw new IOException("History transfer exceeds the object budget");
        if (destination.getObjectDatabase().has(object)) return;
        ObjectLoader loader = reader.open(object);
        if (loader.getSize() > limits.maxObjectBytes() || loader.getSize() > limits.maxBytes() - budget[1])
            throw new IOException("History transfer exceeds the byte budget");
        try (ObjectStream stream = loader.openStream()) {
            ObjectId copied = writer.insert(loader.getType(), loader.getSize(), stream);
            if (!copied.equals(object)) throw new IOException("Transferred Git object identity mismatch");
        }
        budget[1] += loader.getSize();
    }
}
