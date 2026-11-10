package com.taxonomy.tooling;

import java.nio.file.*;
import java.util.jar.*;

/**
 * Packages only the classes Maven just compiled; fixtures execute the production JAR entry point.
 */
final class ToolingJarFixture {
  static Path create(Path destination) throws Exception {
    var manifest = new Manifest();
    manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
    manifest.getMainAttributes().putValue("Main-Class", "com.taxonomy.tooling.TaxonomyTooling");
    Path classes =
        Path.of(TaxonomyTooling.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    Files.createDirectories(destination.toAbsolutePath().getParent());
    try (var jar = new JarOutputStream(Files.newOutputStream(destination), manifest);
        var files = Files.walk(classes)) {
      for (Path file :
          files
              .filter(Files::isRegularFile)
              .filter(p -> p.toString().endsWith(".class"))
              .toList()) {
        jar.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
        Files.copy(file, jar);
        jar.closeEntry();
      }
    }
    return destination;
  }
}
