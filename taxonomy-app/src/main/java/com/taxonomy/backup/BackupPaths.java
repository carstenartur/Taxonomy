package com.taxonomy.backup;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/** Portable entry names, including case/Unicode collisions on supported filesystems. */
public final class BackupPaths {
    private static final Set<String> ROOTS = Set.of("data", "files", "identities", "configuration", "protected", "repositories", "verification");
    private BackupPaths() { }

    public static String requireEntry(String path) {
        new BackupEntry(path, 0, "0".repeat(64));
        if (!Normalizer.isNormalized(path, Normalizer.Form.NFC)) throw new IllegalArgumentException("Noncanonical archive path");
        String[] parts = path.split("/");
        if (parts.length < 2 || parts.length > 32 || !ROOTS.contains(parts[0])) throw new IllegalArgumentException("Unsupported archive entry path");
        for (String part : parts) {
            String base = part.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
            if (part.endsWith(".") || part.endsWith(" ") || part.matches(".*[<>\"|?*].*")
                    || part.codePoints().anyMatch(Character::isISOControl)
                    || part.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 255
                    || base.matches("CON|PRN|AUX|NUL|CLOCK\\$|COM[1-9¹²³]|LPT[1-9¹²³]"))
                throw new IllegalArgumentException("Nonportable archive path");
        }
        return path;
    }

    public static String collisionKey(String path) {
        return Normalizer.normalize(requireEntry(path).toUpperCase(Locale.ROOT), Normalizer.Form.NFC);
    }
}
