package com.taxonomy;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.junit.jupiter.api.Assertions.assertAll;

class ArchitectureCommitHistoryOwnershipTest {

    @Test
    void gitCommitHistoryProjectionTypesBelongToWorkspace() {
        var imported = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.taxonomy.architecture", "com.taxonomy.versioning");
        var owners = Map.of(
                "ArchitectureCommitIndex", "com.taxonomy.versioning.model",
                "ArchitectureCommitIndexRepository", "com.taxonomy.versioning.repository",
                "CommitIndexService", "com.taxonomy.versioning.service",
                "CommitIndexSearchLifecycle", "com.taxonomy.versioning.service",
                "CommitIndexSearchRebuilder", "com.taxonomy.versioning.service");

        assertAll(owners.entrySet().stream().map(owner -> () ->
                classes().that().haveSimpleName(owner.getKey())
                        .should().resideInAPackage(owner.getValue())
                        .because("Git commit-history projections belong to workspace versioning")
                        .allowEmptyShould(false)
                        .check(imported)));
    }
}
