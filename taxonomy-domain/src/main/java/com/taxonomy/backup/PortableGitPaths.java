package com.taxonomy.backup;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;

/** Conservative file names for Git payloads that must survive Linux and Windows checkout. */
public final class PortableGitPaths {
    private PortableGitPaths() { }

    public static String requireFile(String path) {
        BackupChecks.text(path, "Git path");
        if (!Normalizer.isNormalized(path, Normalizer.Form.NFC)
                || path.codePoints().anyMatch(point -> Character.getType(point) == Character.FORMAT
                || Character.getType(point) == Character.SURROGATE
                || point == 0x2028 || point == 0x2029)) {
            throw new IllegalArgumentException("Non-portable Git path");
        }
        String[] segments = path.split("/", -1);
        if (segments.length > 32) throw new IllegalArgumentException("Git path is too deep");
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || segment.endsWith(".") || segment.endsWith(" ")
                    || segment.getBytes(StandardCharsets.UTF_8).length > 255
                    || segment.chars().anyMatch(c -> "<>:\"\\|?*".indexOf(c) >= 0)) {
                throw new IllegalArgumentException("Non-portable Git path segment");
            }
            String upper = segment.toUpperCase(Locale.ROOT);
            String stem = upper.split("\\.", 2)[0].stripTrailing();
            if (upper.equals(".GIT") || upper.matches("GIT~[1-9][0-9]*")
                    || stem.matches("CON|PRN|AUX|NUL|CLOCK\\$|COM[1-9¹²³]|LPT[1-9¹²³]")) {
                throw new IllegalArgumentException("Reserved Git path segment");
            }
        }
        return path;
    }

    /** Includes directory prefixes so differently cased directories cannot be merged at checkout. */
    public static void requireTree(Collection<String> files) {
        BackupChecks.count(files.size());
        var paths = new HashMap<String, PathKind>();
        for (String file : files) {
            requireFile(file);
            String[] segments = file.split("/");
            String prefix = "";
            for (int i = 0; i < segments.length; i++) {
                prefix = prefix.isEmpty() ? segments[i] : prefix + "/" + segments[i];
                boolean directory = i < segments.length - 1;
                String key = Normalizer.normalize(prefix.toUpperCase(Locale.ROOT), Normalizer.Form.NFC);
                var previous = paths.putIfAbsent(key, new PathKind(prefix, directory));
                if (previous != null && (!previous.path().equals(prefix) || !previous.directory() || !directory)) {
                    throw new IllegalArgumentException("Colliding Git paths");
                }
            }
        }
    }

    private record PathKind(String path, boolean directory) { }
}
