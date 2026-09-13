package com.taxonomy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureWorkspaceStorageOwnershipTest {
    @Test
    void jgitStorageBelongsToWorkspaceAuthority() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.taxonomy");
        for (String simpleName : List.of(
                "DslBranch", "DslCommit", "DslGitRepository", "DslGitRepositoryFactory",
                "DslStorageConfig", "DslWorkspacePublicationAdapter", "DslWorkspaceReadAdapter",
                "DslWorkspaceVersionAdapter", "ExpectedHeadDslCommitter",
                "JgitStorageHibernateSchemaFilterProvider", "JgitStorageSchemaMigrationConfig")) {
            assertThat(classes.contain("com.taxonomy.workspace.storage." + simpleName))
                    .as("workspace storage owner %s", simpleName).isTrue();
        }
        assertThat(classes.stream()
                .filter(type -> type.getPackageName().equals("com.taxonomy.dsl.storage")
                        || type.getPackageName().startsWith("com.taxonomy.dsl.storage."))
                .map(type -> type.getName()).toList()).isEmpty();
    }
}
