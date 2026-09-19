package com.taxonomy.relations.controller;

import com.taxonomy.relations.service.RelationReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/** Source-level contract for the Git-authoritative proposal review boundary. */
class GitProposalReviewApiControllerHttpContractTest {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    @Test
    void exposesASeparateArchitectureProposalReviewRoute() {
        RequestMapping mapping = GitProposalReviewApiController.class
                .getAnnotation(RequestMapping.class);

        assertThat(mapping).isNotNull();
        assertThat(mapping.value())
                .containsExactly("/api/architecture/proposals");
    }

    @Test
    void requiresAnIdempotencyKeyForEveryReviewMutation() {
        assertRequiredIdempotencyKey(method("accept"));
        assertRequiredIdempotencyKey(method("reject"));
        assertRequiredIdempotencyKey(method("revert"));
    }

    @Test
    void newBoundaryCannotCallTheLegacyDbFirstReviewService() {
        assertThat(Arrays.stream(GitProposalReviewApiController.class
                        .getDeclaredFields())
                .map(Field::getType))
                .doesNotContain(RelationReviewService.class);
    }

    private static Method method(String name) {
        return Arrays.stream(GitProposalReviewApiController.class
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
