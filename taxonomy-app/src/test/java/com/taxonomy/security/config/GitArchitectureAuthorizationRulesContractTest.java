package com.taxonomy.security.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GitArchitectureAuthorizationRulesContractTest {

    private static final Path AUTHORIZATION_RULES = Path.of(
            "src/main/java/com/taxonomy/security/config/"
                    + "AuthorizationRulesConfigurer.java");

    @Test
    void GitDecisionMatchersPrecedeTheFailClosedApiFallback() throws Exception {
        String source = Files.readString(AUTHORIZATION_RULES);

        int relationMatcher = source.indexOf(
                "\"/api/architecture/relations/**\"");
        int proposalMatcher = source.indexOf(
                "\"/api/architecture/proposals/**\"");
        int roleGate = source.indexOf(
                ".hasAnyRole(\"ARCHITECT\", \"ADMIN\")",
                Math.min(relationMatcher, proposalMatcher));
        int denyAll = source.indexOf(
                "auth.requestMatchers(\"/api/**\").denyAll();");

        assertThat(relationMatcher).isGreaterThanOrEqualTo(0);
        assertThat(proposalMatcher).isGreaterThanOrEqualTo(0);
        assertThat(roleGate).isGreaterThan(Math.min(
                relationMatcher, proposalMatcher));
        assertThat(denyAll).isGreaterThan(roleGate);
    }

    @Test
    void GitDecisionPathsHaveEveryStateChangingMethodGate() throws Exception {
        String source = Files.readString(AUTHORIZATION_RULES);
        String decisionRules = between(
                source,
                "        // Git-authoritative architecture decisions",
                "        auth.requestMatchers(HttpMethod.POST, \"/api/dsl/parse\"");

        assertThat(decisionRules)
                .contains("HttpMethod.POST")
                .contains("HttpMethod.PUT")
                .contains("HttpMethod.DELETE")
                .contains("/api/architecture/relations/**")
                .contains("/api/architecture/proposals/**")
                .containsOnlyOnce("// Git-authoritative architecture decisions");
        assertThat(countOccurrences(
                decisionRules,
                ".hasAnyRole(\"ARCHITECT\", \"ADMIN\")"))
                .isEqualTo(3);
    }

    private static String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex + start.length());
        assertThat(startIndex).isGreaterThanOrEqualTo(0);
        assertThat(endIndex).isGreaterThan(startIndex);
        return source.substring(startIndex, endIndex);
    }

    private static int countOccurrences(String source, String needle) {
        int count = 0;
        int fromIndex = 0;
        while (true) {
            int index = source.indexOf(needle, fromIndex);
            if (index < 0) {
                return count;
            }
            count++;
            fromIndex = index + needle.length();
        }
    }
}
