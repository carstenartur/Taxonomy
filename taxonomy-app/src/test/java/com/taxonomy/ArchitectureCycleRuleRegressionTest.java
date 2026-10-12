package com.taxonomy;

import com.taxonomy.architecturefixture.alpha.AlphaCycleFixture;
import com.taxonomy.architecturefixture.beta.BetaCycleFixture;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import static org.assertj.core.api.Assertions.assertThat;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchitectureCycleRuleRegressionTest {

    @Test
    void publicSdkAndItsImplementationCannotHideACycleUnderTheSameNamespace() {
        var classes = new ClassFileImporter().importClasses(
                com.taxonomy.reporting.api.fixture.ApiCycleFixture.class,
                com.taxonomy.reporting.render.fixture.RenderCycleFixture.class);
        assertThatThrownBy(() -> ArchitectureCycleBoundaryTest
                .coreDomainSlicesShouldBeFreeOfUndocumentedCycles.check(classes))
                .isInstanceOf(AssertionError.class).hasMessageContaining("Cycle detected");
    }

    @Test
    void pluginSdkAndPrivateLoaderCannotHideABackDependency() {
        var classes = new ClassFileImporter().importClasses(
                com.taxonomy.extension.api.fixture.ApiCycleFixture.class,
                com.taxonomy.extension.runtime.fixture.RuntimeCycleFixture.class);
        assertThatThrownBy(() -> ArchitectureCycleBoundaryTest.coreDomainSlicesShouldBeFreeOfUndocumentedCycles.check(classes))
                .isInstanceOf(AssertionError.class).hasMessageContaining("Cycle detected");
    }

    @Test
    void deliberatelyIntroducedUndocumentedCycleFails() {
        var classes = new ClassFileImporter().importClasses(
                AlphaCycleFixture.class, BetaCycleFixture.class);
        var rule = slices()
                .matching("com.taxonomy.architecturefixture.(*)..")
                .should().beFreeOfCycles();

        assertThatThrownBy(() -> rule.check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Cycle detected");
    }

    @Test
    void libraryToBackupApiAndApplicationToLibraryAreAcyclic(@TempDir Path checkout) throws Exception {
        source(checkout, "taxonomy-domain", com.taxonomy.backup.fixture.BackupApiFixture.class);
        source(checkout, "taxonomy-analysis", com.taxonomy.analysis.fixture.BackupFeatureFixture.class);
        source(checkout, "taxonomy-app", com.taxonomy.backup.runtime.fixture.BackupApplicationFixture.class);
        var classes = new ClassFileImporter().importClasses(com.taxonomy.backup.fixture.BackupApiFixture.class,
                com.taxonomy.analysis.fixture.BackupFeatureFixture.class,
                com.taxonomy.backup.runtime.fixture.BackupApplicationFixture.class);
        slices().assignedFrom(ArchitectureCycleBoundaryTest.sliceAssignment(checkout)).should().beFreeOfCycles().check(classes);
        ArchitectureSourceOwnership.domainShouldNotDependOnApplication(checkout).check(classes);
    }

    @Test
    void directDomainToApplicationDependencyFailsEvenWithoutACycle(@TempDir Path checkout) throws Exception {
        source(checkout, "taxonomy-domain", com.taxonomy.backup.fixture.InvalidBackupApiFixture.class);
        source(checkout, "taxonomy-app", com.taxonomy.backup.runtime.fixture.BackupApplicationFixture.class);
        var classes = new ClassFileImporter().importClasses(com.taxonomy.backup.fixture.InvalidBackupApiFixture.class,
                com.taxonomy.backup.runtime.fixture.BackupApplicationFixture.class);
        assertThat(classes.get(com.taxonomy.backup.fixture.InvalidBackupApiFixture.class).getDirectDependenciesFromSelf())
                .anyMatch(edge -> edge.getTargetClass().getName().equals(com.taxonomy.backup.runtime.fixture.BackupApplicationFixture.class.getName()));
        assertThatThrownBy(() -> ArchitectureSourceOwnership.domainShouldNotDependOnApplication(checkout).check(classes))
                .isInstanceOf(AssertionError.class).hasMessageContaining("InvalidBackupApiFixture")
                .hasMessageContaining("BackupApplicationFixture");
    }

    @Test
    void unknownBackupOwnerCannotHideAnActualCycle(@TempDir Path checkout) {
        var classes = new ClassFileImporter().importClasses(com.taxonomy.backup.fixture.UnknownBackupFixture.class,
                com.taxonomy.analysis.fixture.UnknownBackupFeatureFixture.class);
        assertThatThrownBy(() -> slices().assignedFrom(ArchitectureCycleBoundaryTest.sliceAssignment(checkout))
                .should().beFreeOfCycles().check(classes)).isInstanceOf(AssertionError.class).hasMessageContaining("Cycle detected");
    }

    private static void source(Path checkout, String module, Class<?> type) throws IOException {
        // Copy independent fixture source; the graph is imported from its real compiled field/method dependencies.
        Path relative = Path.of(type.getName().replace('.', '/') + ".java");
        Path original = ArchitectureSourceOwnership.repository().resolve("taxonomy-app/src/test/java").resolve(relative);
        Path target = checkout.resolve(module + "/src/main/java").resolve(relative);
        Files.createDirectories(target.getParent());
        Files.copy(original, target);
    }
}
