package com.taxonomy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureApplicationSchemaCompositionTest {

    private static final String APPLICATION_OWNER =
            "com.taxonomy.composition.persistence.TaxonomySchemaMigrationConfig";
    private static final String OLD_APPLICATION_OWNER =
            "com.taxonomy.dsl.storage.TaxonomySchemaMigrationConfig";
    private static final String CORE_OWNER =
            "com.taxonomy.dsl.storage.JgitStorageSchemaMigrationConfig";

    @Test
    void applicationAndCoreSchemaMigrationConfigurationHaveDistinctOwners() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.taxonomy");

        assertThat(classes.contain(APPLICATION_OWNER)).isTrue();
        assertThat(classes.contain(OLD_APPLICATION_OWNER)).isFalse();
        assertThat(classes.contain(CORE_OWNER)).isTrue();
        noClasses().that().haveFullyQualifiedName(APPLICATION_OWNER)
                .should().dependOnClassesThat().resideInAPackage("com.taxonomy.dsl.storage..")
                .allowEmptyShould(false)
                .check(classes);
    }
}
