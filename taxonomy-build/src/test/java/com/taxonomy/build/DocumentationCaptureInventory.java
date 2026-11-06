package com.taxonomy.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

/** Validates the output consumed by the current localized documentation. */
final class DocumentationCaptureInventory {
    record Capture(String file, int width, int height, String sha256) {}

    static List<Capture> verify(Path repository, Path output, Instant startedAt) throws IOException {
        var required = new TreeSet<String>();
        Pattern reference = Pattern.compile("(?:\\.\\./)+images/([\\w.-]+\\.png)");
        for (String language : List.of("de", "en")) {
            Path guides = repository.resolve("docs/" + language);
            if (!Files.isDirectory(guides)) continue;
            try (var files = Files.walk(guides)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".md")).toList()) {
                    var matches = reference.matcher(Files.readString(file));
                    while (matches.find()) required.add(matches.group(1));
                }
            }
        }
        if (required.isEmpty()) throw new AssertionError("No documentation screenshot references found");
        var problems = new ArrayList<String>();
        var captures = new ArrayList<Capture>();
        // Validate unreferenced output too: stale files must never enter the publishable artifact.
        var generated = new TreeSet<>(required);
        if (Files.isDirectory(output)) {
            try (var files = Files.list(output)) {
                files.filter(path -> path.toString().endsWith(".png"))
                        .forEach(path -> generated.add(path.getFileName().toString()));
            }
        }
        for (String filename : generated) {
            Path file = output.resolve(filename);
            if (!Files.isRegularFile(file)) {
                problems.add(filename + ": missing screenshot");
                continue;
            }
            if (Files.getLastModifiedTime(file).toInstant().isBefore(startedAt)) {
                problems.add(filename + ": not generated in the current run");
                continue;
            }
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length < 8 || bytes[0] != (byte) 0x89 || bytes[1] != 'P'
                    || bytes[2] != 'N' || bytes[3] != 'G') {
                problems.add(filename + ": invalid PNG");
                continue;
            }
            var image = ImageIO.read(file.toFile());
            if (image == null || image.getWidth() < 1 || image.getHeight() < 1) {
                problems.add(filename + ": unreadable PNG");
                continue;
            }
            captures.add(new Capture(filename, image.getWidth(), image.getHeight(), sha256(bytes)));
        }
        if (!problems.isEmpty()) throw new AssertionError("Documentation screenshots incomplete:\n"
                + String.join("\n", problems));
        return List.copyOf(captures);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
