package com.taxonomy.workspace.backup;

import com.taxonomy.backup.BackupCheckpoint;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.util.RawParseUtils;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static com.taxonomy.workspace.backup.GitTreeCapture.failure;

/** Reads native reflogs incrementally; JGit's file reader otherwise materializes the entire log. */
final class GitHistoryReflogs {
    static final int MAX_RECORD_BYTES = 65_536;
    static final long MAX_TOTAL_BYTES = 64L << 20;
    private GitHistoryReflogs() { }

    static List<GitHistoryCapture.Reflog> file(Path path, String name, Budget budget, BackupCheckpoint check) throws IOException {
        check.check();
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return List.of();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw failure("Retained Git reflog path is not a regular file");
        if (Files.size(path) > MAX_TOTAL_BYTES - budget.bytes) throw failure("Git reflog byte limit exceeded");
        var result = new ArrayList<GitHistoryCapture.Reflog>();
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            var line = new ByteArrayOutputStream(); var buffer = new byte[8_192];
            for (;;) {
                check.check(); int count = input.read(buffer); if (count < 0) break;
                if (count == 0) { int value = input.read(); if (value < 0) break; buffer[0] = (byte) value; count = 1; }
                for (int index = 0; index < count; index++) {
                    int value = buffer[index] & 0xff;
                    if (value == '\n') {
                        var entry = parse(name, line.toByteArray()); budget.add(entry); result.add(entry); line.reset();
                    } else {
                        if (line.size() >= MAX_RECORD_BYTES) throw failure("Git reflog record byte limit exceeded");
                        line.write(value);
                    }
                }
            }
            if (line.size() != 0) throw failure("Incomplete retained Git reflog record");
            check.check(); return List.copyOf(result);
        }
    }

    private static GitHistoryCapture.Reflog parse(String name, byte[] bytes) throws IOException {
        String line = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        int tab = line.indexOf('\t', 82);
        if (line.length() < 83 || line.charAt(40) != ' ' || line.charAt(81) != ' ' || tab < 82
                || !ObjectId.isId(line.substring(0, 40)) || !ObjectId.isId(line.substring(41, 81))
                || line.indexOf('\0') >= 0 || line.indexOf('\r') >= 0) throw failure("Invalid retained Git reflog record");
        String identity = line.substring(82, tab);
        var who = RawParseUtils.parsePersonIdent(identity);
        if (who == null || !identity.equals(who.toExternalString()))
            throw failure("Invalid retained Git reflog author");
        return new GitHistoryCapture.Reflog(name, line.substring(0, 40), line.substring(41, 81), who.getName(), who.getEmailAddress(),
                who.getWhenAsInstant().getEpochSecond(), who.getTimeZoneOffset(), line.substring(tab + 1));
    }

    static final class Budget {
        private final int maximum;
        private int entries;
        private long bytes;
        Budget(int maximum) { this.maximum = maximum; }
        void add(GitHistoryCapture.Reflog entry) throws IOException {
            if (++entries > maximum) throw failure("Git reflog entry limit exceeded");
            long characters = (long) entry.name().length() + entry.email().length() + entry.message().length() + 128;
            if (characters > MAX_RECORD_BYTES || entry.message().indexOf('\n') >= 0 || entry.message().indexOf('\r') >= 0
                    || entry.message().indexOf('\0') >= 0) throw failure("Git reflog record byte limit exceeded");
            String content = entry.name() + " " + entry.email() + " " + entry.message();
            int size = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(content)).remaining() + 128;
            if (size > MAX_RECORD_BYTES || size > MAX_TOTAL_BYTES - bytes) throw failure("Git reflog byte limit exceeded");
            bytes += size;
        }
    }
}
