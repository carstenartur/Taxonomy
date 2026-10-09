package com.taxonomy.openapi;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Set;
import java.util.stream.Collectors;

/** Mapped non-public methods are supported by MVC and must not evade the documentation inventory. */
public final class RestApiDocumentationDiscoveryCases {
    private RestApiDocumentationDiscoveryCases() { }

    public static void verify() {
        Set<String> names = RestApiDocumentationCases.mappedMethods(NonPublicController.class).stream()
                .map(java.lang.reflect.Method::getName).collect(Collectors.toSet());
        Set<String> expected = Set.of("publicEndpoint", "protectedEndpoint", "privateEndpoint");
        if (!names.equals(expected)) {
            throw new AssertionError("Mapped-method inventory omitted endpoints: expected " + expected + ", got " + names);
        }
    }

    public static void verifyIsolation() {
        var factory = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        var environment = new org.springframework.core.env.StandardEnvironment();
        environment.setActiveProfiles("production-fixture-isolation-check");
        var reader = new org.springframework.context.annotation.AnnotatedBeanDefinitionReader(factory, environment);
        int before = factory.getBeanDefinitionCount();
        reader.register(NonPublicController.class, OpenApiContractCases.ApiFixture.class);
        if (factory.getBeanDefinitionCount() != before) {
            throw new AssertionError("OpenAPI test fixtures must not register in other application contexts");
        }
        environment.setActiveProfiles("openapi-contract-fixture", "openapi-discovery-fixture");
        reader.register(NonPublicController.class, OpenApiContractCases.ApiFixture.class);
        if (factory.getBeanDefinitionCount() != before + 2) {
            throw new AssertionError("Explicit fixture profiles must register both fixtures");
        }
    }

    @org.springframework.context.annotation.Profile("openapi-discovery-fixture")
    @RestController
    static class NonPublicController {
        @GetMapping("/test/public")
        @Operation(summary = "Public fixture", description = "Public handler inventory fixture")
        public String publicEndpoint() { return ""; }

        @GetMapping("/test/protected")
        @Operation(summary = "Protected fixture", description = "Protected handler inventory fixture")
        protected String protectedEndpoint() { return ""; }

        @GetMapping("/test/private")
        @Operation(summary = "Private fixture", description = "Private handler inventory fixture")
        private String privateEndpoint() { return ""; }

        public String helper() { return ""; }
    }

    public static void main(String[] args) { verify(); verifyIsolation(); }
}
