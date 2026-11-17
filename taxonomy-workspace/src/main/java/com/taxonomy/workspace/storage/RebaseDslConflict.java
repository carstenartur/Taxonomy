package com.taxonomy.workspace.storage;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** The three text blobs of a model conflict at one specific replay step. */
public record RebaseDslConflict(Version base, Version target, Version source) {
    static final int MAX_TEXT_BYTES = 1024 * 1024;

    public record Version(String blobId, String text) {}

    /** The source commit pins its parent and source blobs. Earlier decisions determine the target blob. */
    public record Resolution(String targetBlobId, String text) {
        public Resolution {
            if (targetBlobId == null || !ObjectId.isId(targetBlobId) || text == null || text.indexOf('\0') >= 0
                    || text.length() > MAX_TEXT_BYTES || text.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
                throw new IllegalArgumentException("A reviewed target and a bounded text resolution are required");
            }
        }
    }

    static RebaseDslConflict read(Repository repository, RevCommit parent, RevCommit target, RevCommit source)
            throws IOException {
        var before = readVersion(repository, parent);
        var onto = readVersion(repository, target);
        var incoming = readVersion(repository, source);
        return before == null || onto == null || incoming == null ? null : new RebaseDslConflict(before, onto, incoming);
    }

    private static Version readVersion(Repository repository, RevCommit commit) throws IOException {
        try (var tree = TreeWalk.forPath(repository, DslGitRepository.DSL_FILENAME, commit.getTree())) {
            if (tree == null || !FileMode.REGULAR_FILE.equals(tree.getFileMode(0))) return null;
            var id = tree.getObjectId(0);
            var blob = repository.open(id, Constants.OBJ_BLOB);
            if (blob.getSize() > MAX_TEXT_BYTES) return null;
            var bytes = blob.getBytes(MAX_TEXT_BYTES);
            var text = new String(bytes, StandardCharsets.UTF_8);
            // Refuse lossy conversion when a historical blob is not UTF-8 text.
            return java.util.Arrays.equals(bytes, text.getBytes(StandardCharsets.UTF_8)) ? new Version(id.name(), text) : null;
        }
    }
}
