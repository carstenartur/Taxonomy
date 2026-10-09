package com.taxonomy;

import com.tngtech.archunit.core.domain.JavaClass;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Namespace overlap does not transfer a public SDK contract into the application module. */
final class ArchitectureSourceOwnership {
    private ArchitectureSourceOwnership() { }
    static Path repository() {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(".github/architecture-contexts.json"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Architecture checkout is missing");
        return root;
    }
    static boolean belongsTo(Path root, JavaClass type, String... modules) {
        String file = type.getSource().flatMap(com.tngtech.archunit.core.domain.Source::getFileName)
                .orElse(type.getSimpleName().split("\\$", 2)[0] + ".java");
        String relative = type.getPackageName().replace('.', '/') + "/" + file;
        return Arrays.stream(modules).anyMatch(module -> Files.isRegularFile(root.resolve(module + "/src/main/java/" + relative)));
    }
}
