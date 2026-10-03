package com.taxonomy.analysis.dag;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Analysis task contracts stay transport-neutral; broker adapters live outside them. */
class AnalysisDagBoundaryTest {

    private static final JavaClasses ANALYSIS = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.taxonomy.analysis");

    @Test
    void contractsAndInProcessAdapterDoNotDependOnBrokerApis() {
        noClasses().that().resideInAnyPackage("com.taxonomy.analysis.dag..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "jakarta.jms..", "javax.jms..", "org.apache.activemq..", "org.springframework.jms..")
                .check(ANALYSIS);
    }

    @Test
    void coreContractsDependOnlyOnTheJdk() {
        noClasses().that().resideInAPackage("com.taxonomy.analysis.dag")
                .should().dependOnClassesThat().resideOutsideOfPackages("java..", "com.taxonomy.analysis.dag")
                .check(ANALYSIS);
    }

    @Test
    void analysisDomainDoesNotUseBrokerApisDirectly() {
        noClasses().that().resideInAnyPackage("com.taxonomy.analysis..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "jakarta.jms..", "javax.jms..", "org.apache.activemq..", "org.springframework.jms..")
                .check(ANALYSIS);
    }
}
