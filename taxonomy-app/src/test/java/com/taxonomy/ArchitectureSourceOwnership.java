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
    /** Shared contracts and application orchestration keep their existing physical owners. */
    static String backupSlice(Path root, JavaClass type) {
        String name = type.getPackageName();
        if (!name.equals("com.taxonomy.backup") && !name.startsWith("com.taxonomy.backup.")) return null;
        boolean domain = belongsTo(root, type, "taxonomy-domain");
        boolean app = belongsTo(root, type, "taxonomy-app");
        if (domain && !app) return "backup-api";
        if (app && !domain) return "composition";
        // An unknown or conflicting owner remains visible; physical duplicate-owner gates still apply.
        return "backup";
    }

    static com.tngtech.archunit.lang.ArchRule domainShouldNotDependOnApplication(Path root) {
        var domain = new com.tngtech.archunit.base.DescribedPredicate<JavaClass>("physically owned by taxonomy-domain") {
            @Override public boolean test(JavaClass type) { return belongsTo(root, type, "taxonomy-domain"); }
        };
        var app = new com.tngtech.archunit.base.DescribedPredicate<JavaClass>("physically owned by taxonomy-app") {
            @Override public boolean test(JavaClass type) { return belongsTo(root, type, "taxonomy-app"); }
        };
        return com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses().that(domain)
                .should().dependOnClassesThat(app).allowEmptyShould(false)
                .because("shared domain contracts must not depend on their application implementations");
    }
}
