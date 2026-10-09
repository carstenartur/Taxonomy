package com.taxonomy.extension.runtime;

import com.taxonomy.extension.api.plugin.*;
import org.pf4j.DefaultVersionManager;

import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;

/** Validates trusted operator artifacts; this is compatibility checking, not a Java sandbox. */
public final class PluginArtifactValidator {
    static final String SERVICE = "META-INF/services/" + TaxonomyPlugin.class.getName();

    public PluginDescriptor validate(Path artifact) {
        try (var jar = new JarFile(artifact.toFile())) {
            Manifest manifest = jar.getManifest();
            if (manifest == null) throw new IllegalArgumentException("Plugin manifest is missing");
            var attributes = manifest.getMainAttributes();
            String id = required(attributes, "Plugin-Id");
            String version = required(attributes, "Plugin-Version");
            String range = required(attributes, "Plugin-Requires");
            PluginMode mode = PluginMode.valueOf(required(attributes, "Taxonomy-Plugin-Mode"));
            var versions = new DefaultVersionManager();
            versions.compareVersions(version, version);
            if (!versions.checkVersionConstraint(PluginDescriptor.HOST_API_VERSION, range))
                throw new IllegalArgumentException("Host API range is incompatible: " + id);
            if (attributes.getValue("Plugin-Class") != null || attributes.getValue("Class-Path") != null)
                throw new IllegalArgumentException("Plugin-Class and manifest Class-Path are not supported");
            List<PluginRequirement> dependencies = new ArrayList<>();
            for (String dependency : tokens(attributes.getValue("Plugin-Dependencies"))) {
                int separator = dependency.indexOf('@');
                if (separator < 1 || separator == dependency.length() - 1)
                    throw new IllegalArgumentException("Plugin dependencies require id@version-range");
                String dependencyRange = dependency.substring(separator + 1).trim();
                versions.checkVersionConstraint(PluginDescriptor.HOST_API_VERSION, dependencyRange);
                dependencies.add(new PluginRequirement(dependency.substring(0, separator).trim(), dependencyRange));
            }
            if (jar.getJarEntry(SERVICE) == null)
                throw new IllegalArgumentException("TaxonomyPlugin service entry is missing");
            for (var entries = jar.entries(); entries.hasMoreElements();) {
                String name = entries.nextElement().getName();
                if (name.startsWith("/") || name.contains("\\") || Arrays.asList(name.split("/")).contains(".."))
                    throw new IllegalArgumentException("Invalid plugin entry path");
                name = name.replaceFirst("^META-INF/versions/[0-9]+/", "");
                if (name.endsWith(".class") && ((name.startsWith("com/taxonomy/")
                        && !name.startsWith("com/taxonomy/plugins/")) || name.startsWith("org/pf4j/")))
                    throw new IllegalArgumentException("Plugin must not contain host SDK or loader classes: " + name);
                if (name.startsWith("BOOT-INF/") || name.startsWith("WEB-INF/") || name.endsWith(".jar"))
                    throw new IllegalArgumentException("Plugin must be a plain JAR without nested artifacts");
            }
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(artifact); var stream = new DigestInputStream(input, digest)) {
                stream.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return new PluginDescriptor(new PluginIdentity(id, version, HexFormat.of().formatHex(digest.digest())),
                    range, dependencies, Set.copyOf(tokens(attributes.getValue("Taxonomy-Plugin-Capabilities"))), mode);
        } catch (IOException | NoSuchAlgorithmException invalid) {
            throw new IllegalArgumentException("Cannot validate plugin artifact", invalid);
        } catch (RuntimeException invalid) {
            if (invalid instanceof IllegalArgumentException) throw invalid;
            throw new IllegalArgumentException("Invalid plugin manifest or version range", invalid);
        }
    }

    private static String required(Attributes attributes, String name) {
        String value = attributes.getValue(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing plugin attribute: " + name);
        return value.trim();
    }

    private static List<String> tokens(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> values = Arrays.stream(text.split(",", -1)).map(String::trim).toList();
        if (values.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("Empty plugin manifest list entry");
        return values;
    }
}
