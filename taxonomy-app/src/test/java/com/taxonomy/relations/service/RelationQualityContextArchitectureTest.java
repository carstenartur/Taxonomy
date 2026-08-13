package com.taxonomy.relations.service;

import com.taxonomy.relations.controller.QualityApiController;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RelationQualityContextArchitectureTest {

    private static final Set<String> QUALITY_OPERATIONS = Set.of(
            "calculateMetrics",
            "metricsByRelationType",
            "metricsByProvenance",
            "topRejected",
            "acceptanceHistoryWeight");

    @Test
    void everyPublicQualityOperationRequiresRepositoryContext() {
        Arrays.stream(RelationQualityService.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> QUALITY_OPERATIONS.contains(method.getName()))
                .forEach(method -> assertThat(method.getParameterTypes())
                        .as(method.toGenericString())
                        .contains(RepositoryContext.class));

        assertThat(Arrays.stream(RelationQualityService.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .filter(QUALITY_OPERATIONS::contains)
                .toList())
                .containsExactlyInAnyOrderElementsOf(QUALITY_OPERATIONS);
    }

    @Test
    void validationCannotComputeFeedbackWithoutRepositoryContext() {
        Method[] validationMethods = Arrays.stream(
                        RelationValidationService.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("validate"))
                .toArray(Method[]::new);

        assertThat(validationMethods).hasSize(1);
        assertThat(validationMethods[0].getParameterTypes())
                .contains(RepositoryContext.class);
    }

    @Test
    void qualityHttpBoundaryMustReceiveAWorkspaceResolver() {
        assertThat(QualityApiController.class.getConstructors())
                .singleElement()
                .satisfies(constructor -> assertThat(
                        constructor.getParameterTypes())
                        .contains(WorkspaceResolver.class));
    }
}
