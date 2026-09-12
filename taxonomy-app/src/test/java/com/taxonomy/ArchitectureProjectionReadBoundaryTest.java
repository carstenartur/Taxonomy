package com.taxonomy;

import com.taxonomy.relations.service.RelationBranchProjectionReadinessService;
import com.taxonomy.relations.service.RelationBranchProjectionRebuildService;
import com.taxonomy.workspace.service.WorkspaceDslReadPort;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureProjectionReadBoundaryTest {

    @Test
    void projectionBuildAndReadinessDependOnWorkspaceReadsNotStorage() {
        var classes = new ClassFileImporter().importClasses(
                RelationBranchProjectionRebuildService.class,
                RelationBranchProjectionReadinessService.class);
        noClasses().should().dependOnClassesThat()
                .resideInAnyPackage("com.taxonomy.dsl.storage..", "org.eclipse.jgit..")
                .because("knowledge owns projection semantics, workspace owns exact Git reads")
                .check(classes);
    }

    @Test
    void readContractDoesNotExposeFrameworksStorageOrKnowledge() {
        var classes = new ClassFileImporter().importClasses(
                WorkspaceDslReadPort.class, WorkspaceDslReadPort.RepositoryRead.class);
        noClasses().should().dependOnClassesThat()
                .resideInAnyPackage("com.taxonomy.dsl.storage..", "org.eclipse.jgit..",
                        "org.springframework..", "com.taxonomy.relations..", "com.taxonomy.catalog..")
                .check(classes);
    }

    @Test
    void readSessionCannotPublishCommitsOrCloseFactoryOwnedRepositories() {
        assertThat(Arrays.stream(WorkspaceDslReadPort.RepositoryRead.class.getDeclaredMethods())
                .map(Method::getName)).containsExactlyInAnyOrder(
                        "currentHead", "dslAtCommit", "verifyExpectedHead");
        assertThat(AutoCloseable.class.isAssignableFrom(WorkspaceDslReadPort.RepositoryRead.class))
                .isFalse();
    }
}
