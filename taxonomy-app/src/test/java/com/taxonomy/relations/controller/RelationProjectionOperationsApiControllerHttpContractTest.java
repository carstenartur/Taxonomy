package com.taxonomy.relations.controller;

import com.taxonomy.relations.repository.RelationDecisionProjectionRepository;
import com.taxonomy.relations.repository.RelationProjectionRecoveryRepository;
import com.taxonomy.relations.service.RelationProjectionOperationsService;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/** Source-level contract for the authorized projection operations boundary. */
class RelationProjectionOperationsApiControllerHttpContractTest {

    @Test
    void exposesTheArchitectureProjectionOperationsRoute() {
        RequestMapping mapping = RelationProjectionOperationsApiController.class
                .getAnnotation(RequestMapping.class);

        assertThat(mapping).isNotNull();
        assertThat(mapping.value())
                .containsExactly("/api/architecture/relations/projection");
    }

    @Test
    void rebuildUsesHttpPreconditionsAtThePublicBoundary() {
        Method rebuild = Arrays.stream(
                        RelationProjectionOperationsApiController.class
                                .getDeclaredMethods())
                .filter(method -> method.getName().equals("rebuild"))
                .findFirst()
                .orElseThrow();

        assertThat(rebuild.getAnnotation(PostMapping.class).value())
                .containsExactly("/rebuild");
        assertThat(Arrays.stream(rebuild.getParameters())
                .map(parameter -> parameter.getAnnotation(RequestHeader.class))
                .filter(Objects::nonNull)
                .map(RequestHeader::value))
                .contains("If-Match", "If-None-Match");
    }

    @Test
    void controllerDelegatesWithoutWritingProjectionTablesDirectly() {
        assertThat(Arrays.stream(
                        RelationProjectionOperationsApiController.class
                                .getDeclaredFields())
                .map(Field::getType))
                .contains(RelationProjectionOperationsService.class)
                .doesNotContain(
                        RelationDecisionProjectionRepository.class,
                        RelationProjectionRecoveryRepository.class);
    }
}
