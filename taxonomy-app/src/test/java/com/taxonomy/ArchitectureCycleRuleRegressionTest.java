package com.taxonomy;

import com.taxonomy.architecturefixture.alpha.AlphaCycleFixture;
import com.taxonomy.architecturefixture.beta.BetaCycleFixture;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

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
}
