package com.taxonomy.analysis.dispatch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/** Normal dispatch is pushed after commit; there is no fixed-rate pending-work scanner (#1161 P03). */
class AnalysisDispatchNoPollingTest {

    private static final JavaClasses DISPATCH = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.taxonomy.analysis.dispatch", "com.taxonomy.analysis.dag");

    @Test
    void dispatchHasNoSchedulerTimerOrBackgroundThread() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage("org.springframework.scheduling..")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.util.Timer")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.util.concurrent.ScheduledExecutorService")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.util.concurrent.Executors")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.Thread")
                .check(DISPATCH);
        noMethods().should().beAnnotatedWith("org.springframework.scheduling.annotation.Scheduled")
                .allowEmptyShould(true)
                .check(DISPATCH);
    }

    @Test
    void dispatchDoesNotDependOnBrokerApis() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage(
                        "jakarta.jms..", "javax.jms..", "org.apache.activemq..", "org.springframework.jms..")
                .check(DISPATCH);
    }
}
