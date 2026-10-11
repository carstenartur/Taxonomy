package com.taxonomy.workspace.backup;

import com.taxonomy.backup.BackupCheckpoint;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.util.sha1.SHA1;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Streams the standard Git index v2 so exported worktrees need no local plaintext staging.
 * JGit's in-core DirCache has no public streaming writer. This narrow format has no extensions;
 * native Git and JGit both validate the generated index in interoperability tests.
 * Format: https://git-scm.com/docs/gitformat-index
 */
final class PortableGitIndex {
    private PortableGitIndex() { }
    static void write(List<GitTreeCapture.File> files, OutputStream output, BackupCheckpoint check) throws IOException {
        var ordered = files.stream().map(file -> new Entry(file, file.path().getBytes(StandardCharsets.UTF_8)))
                .sorted((a, b) -> Arrays.compareUnsigned(a.path(), b.path())).toList();
        var sha = SHA1.newInstance().setDetectCollision(true);
        var data = new DataOutputStream(new FilterOutputStream(output) {
            @Override public void write(int value) throws IOException {
                out.write(value); sha.update(new byte[] { (byte) value });
            }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                out.write(bytes, offset, length); sha.update(bytes, offset, length);
            }
        });
        data.writeInt(0x44495243); data.writeInt(2); data.writeInt(ordered.size());
        for (var entry : ordered) {
            check.check();
            for (int i = 0; i < 6; i++) data.writeInt(0); // ctime, mtime, device and inode are target-specific.
            data.writeInt(entry.file().mode()); data.writeInt(0); data.writeInt(0); data.writeInt((int) entry.file().length());
            ObjectId.fromString(entry.file().objectId()).copyRawTo(data);
            data.writeShort(Math.min(entry.path().length, 0x0fff)); data.write(entry.path());
            data.write(new byte[8 - (62 + entry.path().length) % 8]);
        }
        data.flush(); output.write(sha.digest()); check.check();
    }
    private record Entry(GitTreeCapture.File file, byte[] path) { }
}
