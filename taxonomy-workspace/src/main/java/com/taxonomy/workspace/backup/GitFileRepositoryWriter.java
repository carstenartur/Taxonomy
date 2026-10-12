package com.taxonomy.workspace.backup;

import com.taxonomy.backup.*;
import org.eclipse.jgit.internal.storage.pack.PackWriter;
import org.eclipse.jgit.lib.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

import static com.taxonomy.workspace.backup.GitTreeCapture.failure;

/** Streams ordinary independent Git repositories; no source config, hooks or external gitdir. */
final class GitFileRepositoryWriter {
    private GitFileRepositoryWriter() { }
    static void write(GitHistoryCapture capture, String archiveId, GitRepresentation representation,
                      GitStandBackupSource.Stand workingCopy, ComponentSink sink, BackupCheckpoint check) throws IOException {
        boolean working = representation == GitRepresentation.WORKTREE;
        String root = "repositories/" + archiveId + "/", git = root + (working ? ".git/" : "");
        var tree = workingCopy != null ? workingCopy.sourceTree() : working ? capture.headTree(check).orElse(null) : null;
        String head = workingCopy != null ? "ref: " + workingCopy.state().symbolicHead()
                : capture.state().symbolicHead() != null ? "ref: " + capture.state().symbolicHead()
                : capture.state().refs().get(Constants.HEAD);
        if (head == null) throw failure("Captured repository has no declared HEAD");
        var logs = new TreeMap<String, List<GitHistoryCapture.Reflog>>();
        for (var log : capture.reflogs()) logs.computeIfAbsent(log.ref(), ignored -> new ArrayList<>()).add(log);
        try { PortableGitPaths.requireTree(logs.keySet().stream().map(ref -> "logs/" + ref).toList()); }
        catch (IllegalArgumentException problem) { throw failure("Reflog paths are not portable; select a Git bundle"); }
        try { PortableGitPaths.requireTree(capture.symbolicRefs().keySet()); }
        catch (IllegalArgumentException problem) { throw failure("Symbolic ref paths are not portable; select a Git bundle"); }
        check.check();
        // Native Git requires refs/ even when every ref is packed or HEAD is unborn.
        text(git + "refs/", "", sink);
        text(git + "config", "[core]\n\trepositoryformatversion = 0\n\tbare = " + !working
                + "\n\tfilemode = false\n\tlogallrefupdates = true\n\tautocrlf = false\n", sink);
        text(git + "HEAD", head + "\n", sink);
        GitTreeCapture.writeGenerated(git + "packed-refs", sink, output -> {
            output.write("# pack-refs with: sorted\n".getBytes(StandardCharsets.US_ASCII));
            var sorted = new TreeMap<String, String>((left, right) -> Arrays.compareUnsigned(
                    left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8)));
            sorted.putAll(capture.exportedRefs());
            for (var ref : sorted.entrySet()) {
                check.check(); if (ref.getKey().equals(Constants.HEAD) || capture.symbolicRefs().containsKey(ref.getKey())) continue;
                output.write((ref.getValue() + " " + ref.getKey() + "\n").getBytes(StandardCharsets.UTF_8));
            }
        });
        for (var ref : capture.symbolicRefs().entrySet()) {
            check.check(); text(git + ref.getKey(), "ref: " + ref.getValue() + "\n", sink);
        }
        try (var reader = capture.reader(check); var writer = new PackWriter(GitHistoryCapture.packConfig(), reader)) {
            var roots = new HashSet<ObjectId>(); capture.exportedRefs().values().forEach(id -> roots.add(ObjectId.fromString(id)));
            var monitor = GitHistoryCapture.monitor(check);
            writer.setThin(false); writer.setUseCachedPacks(false); writer.setUseBitmaps(false);
            writer.preparePack(monitor, roots, Set.of()); check.check();
            if (writer.getObjectCount() != capture.objectCount()) throw failure("Git pack differs from the captured object closure");
            String pack = git + "objects/pack/pack-" + writer.computeName().name();
            GitTreeCapture.writeGenerated(pack + ".pack", sink, output -> { writer.writePack(monitor, monitor, output); check.check(); });
            GitTreeCapture.writeGenerated(pack + ".idx", sink, output -> { writer.writeIndex(output); check.check(); });
        }
        for (var log : logs.entrySet()) {
            check.check();
            GitTreeCapture.writeGenerated(git + "logs/" + log.getKey(), sink, output -> {
                for (var entry : log.getValue()) {
                    check.check();
                    var who = new PersonIdent(entry.name(), entry.email(), Instant.ofEpochSecond(entry.epochSeconds()),
                            ZoneOffset.ofTotalSeconds(Math.multiplyExact(entry.offsetMinutes(), 60)));
                    if (entry.message().contains("\n") || entry.message().contains("\r") || entry.message().contains("\0"))
                        throw failure("Git reflog message cannot be represented as one record");
                    String line = entry.oldId() + " " + entry.newId() + " " + who.toExternalString() + "\t" + entry.message() + "\n";
                    output.write(line.getBytes(StandardCharsets.UTF_8));
                }
            });
        }
        if (working) {
            var files = tree == null ? List.<GitTreeCapture.File>of() : tree.files();
            GitTreeCapture.writeGenerated(git + "index", sink, output -> PortableGitIndex.write(files, output, check));
            if (workingCopy == null) for (var file : files) { check.check(); tree.writeFile(file, root + file.path(), sink); }
            else for (var file : workingCopy.files()) { check.check(); workingCopy.writeFile(file, root + file.path(), sink); }
        }
        check.check();
    }
    private static void text(String path, String text, ComponentSink sink) throws IOException {
        GitTreeCapture.writeGenerated(path, sink, output -> output.write(text.getBytes(StandardCharsets.UTF_8)));
    }
}
