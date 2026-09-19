package com.taxonomy.portfolio.model;

import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the Hibernate contract for tenant-bound composite associations. */
class AnalysisArtifactCompositeAssociationContractTest {

    private static final List<Class<?>> TENANT_BOUND_TYPES = List.of(
            ProjectRequirement.class,
            RequirementAnalysisJob.class,
            RequirementAnalysisJobItem.class,
            RequirementAnalysisSnapshot.class,
            RequirementElementMapping.class,
            RequirementRelationMapping.class);

    @Test
    void scalarTenantKeysAreTheOnlyWriteAuthorityForCompositeAssociations() {
        int associationCount = 0;
        for (Class<?> entityType : TENANT_BOUND_TYPES) {
            for (Field field : entityType.getDeclaredFields()) {
                JoinColumns joinColumns = field.getAnnotation(JoinColumns.class);
                if (joinColumns == null) {
                    continue;
                }
                associationCount++;
                assertThat(joinColumns.value())
                        .as("%s.%s must be wholly read-only because its scalar keys own the columns",
                                entityType.getSimpleName(), field.getName())
                        .allSatisfy(AnalysisArtifactCompositeAssociationContractTest::assertReadOnly);
            }
        }
        assertThat(associationCount)
                .as("the contract must inspect the portfolio composite associations")
                .isPositive();
    }

    private static void assertReadOnly(JoinColumn joinColumn) {
        assertThat(joinColumn.insertable())
                .as("%s must not be insertable through the association", joinColumn.name())
                .isFalse();
        assertThat(joinColumn.updatable())
                .as("%s must not be updatable through the association", joinColumn.name())
                .isFalse();
    }
}
