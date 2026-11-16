package com.taxonomy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureApplicationSchemaCompositionTest {

    private static final String APPLICATION_OWNER =
            "com.taxonomy.composition.persistence.TaxonomySchemaMigrationConfig";
    private static final String OLD_APPLICATION_OWNER =
            "com.taxonomy.dsl.storage.TaxonomySchemaMigrationConfig";
    private static final String CORE_OWNER =
            "com.taxonomy.workspace.storage.JgitStorageSchemaMigrationConfig";

    @Test
    void applicationAndCoreSchemaMigrationConfigurationHaveDistinctOwners() throws IOException {
        var owners = new ArrayList<URL>();
        for (String owner : List.of(APPLICATION_OWNER, OLD_APPLICATION_OWNER, CORE_OWNER)) {
            String resource = owner.replace('.', '/') + ".class";
            owners.addAll(Collections.list(getClass().getClassLoader().getResources(resource)));
        }
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importUrls(owners);

        assertThat(classes.contain(APPLICATION_OWNER)).isTrue();
        assertThat(classes.contain(OLD_APPLICATION_OWNER)).isFalse();
        assertThat(classes.contain(CORE_OWNER)).isTrue();
        noClasses().that().haveFullyQualifiedName(APPLICATION_OWNER)
                .should().dependOnClassesThat().resideInAPackage("com.taxonomy.workspace.storage..")
                .allowEmptyShould(false)
                .check(classes);
    }
}
