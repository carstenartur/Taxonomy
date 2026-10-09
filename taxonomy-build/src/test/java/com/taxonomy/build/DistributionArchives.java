package com.taxonomy.build;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import java.util.zip.ZipInputStream;
import static org.assertj.core.api.Assertions.*;

/** Inspect every nested library and delivered external JAR, including misplaced duplicate classes. */
final class DistributionArchives {
    private DistributionArchives() { }
    interface EntryVisitor { void visit(String archive, String path, InputStream bytes) throws IOException; }
    static List<String> inspect(Path root, EntryVisitor visitor) throws IOException {
        var archives = new ArrayList<String>();
        try (var host = new JarFile(PackagedPluginSupport.application(root).toFile())) {
            assertThat(host.getManifest().getMainAttributes().getValue("Main-Class"))
                    .isEqualTo("org.springframework.boot.loader.launch.PropertiesLauncher");
            var entries = host.entries();
            boolean migrations = false;
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement(); String path = entry.getName();
                if (entry.isDirectory()) continue;
                if (path.startsWith("BOOT-INF/classes/")) {
                    String relative = path.substring("BOOT-INF/classes/".length());
                    migrations |= relative.startsWith("db/migration/");
                    try (var bytes = host.getInputStream(entry)) { visitor.visit("taxonomy-app", relative, bytes); }
                } else if (path.startsWith("BOOT-INF/lib/") && path.endsWith(".jar")) {
                    archives.add(path);
                    visitJar(path, host.getInputStream(entry), visitor);
                }
            }
            assertThat(migrations).as("The shared migration history remains in the host").isTrue();
        }
        for (String directory : List.of("features", "plugins")) {
            try (var files = Files.list(root.resolve("taxonomy-app/target/" + directory))) {
                for (Path file : files.sorted().toList()) {
                    assertThat(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)).isTrue();
                    if (file.getFileName().toString().endsWith(".jar.sha256")) {
                        String name = file.getFileName().toString();
                        Path jar = file.resolveSibling(name.substring(0, name.length() - ".sha256".length()));
                        assertThat(Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS))
                                .as("checksum must have a regular JAR owner: %s", file).isTrue();
                        assertThat(Files.readString(file).strip())
                                .as("checksum must identify the exact delivered JAR: %s", file)
                                .isEqualTo(PackagedPluginSupport.sha256(jar) + " *" + jar.getFileName());
                        continue;
                    }
                    assertThat(file.toString()).endsWith(".jar");
                    String name = directory + "/" + file.getFileName(); archives.add(name);
                    visitJar(name, Files.newInputStream(file), visitor);
                }
            }
        }
        return List.copyOf(archives);
    }
    private static void visitJar(String archive, InputStream input, EntryVisitor visitor) throws IOException {
        try (var zip = new ZipInputStream(input)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
                if (!entry.isDirectory()) visitor.visit(archive, entry.getName(), zip);
        }
    }
    static void assertOwner(String archive, String module, boolean optional) {
        assertThat(archive).matches((optional ? "features/" : "BOOT-INF/lib/")
                + java.util.regex.Pattern.quote(module) + "-[0-9][^/]*\\.jar");
    }
}
