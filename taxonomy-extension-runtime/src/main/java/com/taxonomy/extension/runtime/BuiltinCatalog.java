package com.taxonomy.extension.runtime;

import com.taxonomy.extension.api.plugin.*;
import com.taxonomy.shared.extension.TaxonomyExtension;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/** Registers Spring-owned built-ins in the same catalog as external contributions. */
public final class BuiltinCatalog {
    private BuiltinCatalog() { }

    /** Also used by small constructor-based integrations without a Spring composition root. */
    public static PluginCatalog create(List<? extends TaxonomyExtension> extensions) {
        var catalog = new PluginCatalog();
        publishInto(catalog, extensions);
        return catalog;
    }

    public static void publishInto(PluginCatalog catalog, List<? extends TaxonomyExtension> extensions) {
        Map<String, List<TaxonomyExtension>> groups = new TreeMap<>();
        Map<String, URL> sources = new TreeMap<>();
        for (TaxonomyExtension extension : extensions) {
            Objects.requireNonNull(extension, "TaxonomyExtension must not be null");
            URL source = extension.getClass().getProtectionDomain().getCodeSource().getLocation();
            String module = moduleId(source);
            URL previous = sources.putIfAbsent(module, source);
            if (previous != null && !previous.equals(source))
                throw new IllegalStateException("Duplicate built-in module artifact: " + module);
            groups.computeIfAbsent(module, ignored -> new ArrayList<>()).add(extension);
        }
        List<PluginCatalog.Contribution> contributions = new ArrayList<>();
        groups.forEach((module, values) -> {
            String version = values.getFirst().getClass().getPackage().getImplementationVersion();
            if (version == null) version = PluginDescriptor.HOST_API_VERSION;
            var identity = new PluginIdentity("taxonomy.builtin." + module, version, fingerprint(sources.get(module)));
            contributions.add(new PluginCatalog.Contribution(new PluginDescriptor(identity,
                    ">=1.0.0 & <2.0.0", List.of(), Set.of(), PluginMode.STARTUP), values));
        });
        catalog.publishAll(contributions);
    }

    private static String moduleId(URL source) {
        try {
            if (source.getProtocol().equals("file")) {
                Path path = Path.of(source.toURI());
                if (Files.isDirectory(path)) {
                    if (path.getParent().getFileName().toString().equals("target"))
                        return path.getParent().getParent().getFileName().toString()
                                + (path.getFileName().toString().equals("test-classes") ? "-tests" : "");
                    return "classpath";
                }
            }
            if (source.toExternalForm().contains("BOOT-INF/classes")) return "taxonomy-app";
            var matcher = java.util.regex.Pattern.compile("(taxonomy-[a-z-]+)-[0-9][^/!]*\\.jar")
                    .matcher(source.toExternalForm());
            String module = null;
            // Boot's outer taxonomy-app JAR is not the owner of an inner feature JAR.
            while (matcher.find()) module = matcher.group(1);
            if (module != null) return module;
            throw new IllegalArgumentException("Unrecognized built-in artifact location");
        } catch (java.net.URISyntaxException failure) {
            throw new IllegalArgumentException("Invalid built-in artifact location", failure);
        }
    }

    /** Raw JAR bytes in distributions; a deterministic content digest for exploded dev classes. */
    static String fingerprint(URL source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (source.getProtocol().equals("file") && Files.isDirectory(Path.of(source.toURI()))) {
                Path root = Path.of(source.toURI());
                try (var files = Files.walk(root)) {
                    for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                        String relative = root.relativize(file).toString().replace('\\', '/');
                        digest.update(relative.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
                        digest.update(java.nio.ByteBuffer.allocate(8).putLong(Files.size(file)).array());
                        try (InputStream input = Files.newInputStream(file)) { update(digest, input); }
                    }
                }
            } else {
                String url = source.toExternalForm();
                if (url.contains("BOOT-INF/classes")) {
                    int boundary = url.indexOf("/!BOOT-INF/classes");
                    if (boundary < 0) boundary = url.indexOf("!/BOOT-INF/classes");
                    if (boundary < 0) throw new IllegalArgumentException("Unrecognized host artifact location");
                    url = url.substring(0, boundary).replaceFirst("^jar:nested:", "file:").replaceFirst("^jar:", "");
                } else if (url.startsWith("jar:")) {
                    url = url.substring(4);
                    if (url.endsWith("!/")) url = url.substring(0, url.length() - 2);
                }
                try (InputStream input = URI.create(url).toURL().openStream()) { update(digest, input); }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot fingerprint built-in plugin artifact", failure);
        }
    }

    private static void update(MessageDigest digest, InputStream input) throws java.io.IOException {
        byte[] buffer = new byte[65536]; int count;
        while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
    }
}
