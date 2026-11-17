package com.taxonomy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureWorkspaceStorageOwnershipTest {
    @Test
    void jgitStorageBelongsToWorkspaceAuthority() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.taxonomy.workspace.storage", "com.taxonomy.dsl.storage");
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
    @Test
    void rawModelStorageFactoriesRemainInsideTheirExistingInfrastructureOwners() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .withImportOption(location -> !location.contains("-test-fixtures.jar"))
                .importPackages("com.taxonomy");
        noClasses().that().resideOutsideOfPackages(
                        "com.taxonomy.workspace.storage..",
                        "com.taxonomy.templates..",
                        "com.taxonomy.preferences..",
                        "com.taxonomy.backup.restore..")
                .should().dependOnClassesThat().haveFullyQualifiedName(
                        "io.github.carstenartur.jgit.storage.hibernate.HibernateRepositoryFactory")
                .because("model features use secured workspace handles and the existing isolated restore boundary")
                .check(classes);
        noClasses().that().resideOutsideOfPackages(
                        "com.taxonomy.workspace.storage..",
                        "com.taxonomy.backup.restore..")
                .should().dependOnClassesThat().haveFullyQualifiedName(
                        "io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory")
                .because("raw model factories are privileged provisioning infrastructure")
                .check(classes);
    }

}
