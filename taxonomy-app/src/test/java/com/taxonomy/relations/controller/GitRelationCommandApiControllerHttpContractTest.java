package com.taxonomy.relations.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/** Source-level HTTP contract for the public Git-authoritative relation API. */
class GitRelationCommandApiControllerHttpContractTest {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    @Test
    void exposesTheDocumentedArchitectureRelationRoute() {
        RequestMapping mapping = GitRelationCommandApiController.class
                .getAnnotation(RequestMapping.class);

        assertThat(mapping).isNotNull();
        assertThat(mapping.value())
                .containsExactly("/api/architecture/relations");
    }

    @Test
    void requiresAnIdempotencyKeyForEveryMutationEndpoint() {
        assertRequiredIdempotencyKey(method("upsert"));
        assertRequiredIdempotencyKey(method("remove"));
    }

    private static Method method(String name) {
        return Arrays.stream(GitRelationCommandApiController.class
                        .getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static void assertRequiredIdempotencyKey(Method method) {
        RequestHeader header = Arrays.stream(method.getParameters())
                .map(parameter -> parameter.getAnnotation(RequestHeader.class))
                .filter(Objects::nonNull)
                .filter(annotation -> IDEMPOTENCY_KEY.equals(annotation.value())
                        || IDEMPOTENCY_KEY.equals(annotation.name()))
                .findFirst()
                .orElseThrow();

        assertThat(header.required())
                .as("%s must reject a missing Idempotency-Key before invocation",
                        method.getName())
                .isTrue();
    }
}
