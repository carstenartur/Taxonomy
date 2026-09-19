package com.taxonomy.portfolio.controller;

import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.shared.config.GlobalExceptionHandler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Application-level regression: specific and generic advice must work in either discovery order. */
class PortfolioAdviceOrderingTest {
    static Stream<Arguments> typedFailures() {
        return Arrays.stream(PortfolioException.Kind.values()).flatMap(kind ->
                Stream.of(Arguments.of(kind, false), Arguments.of(kind, true)));
    }

    @ParameterizedTest
    @MethodSource("typedFailures")
    void typedProblemsPrecedeTheGlobalCatchAll(PortfolioException.Kind kind, boolean domainFirst) throws Exception {
        int expected = switch (kind) {
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
            case VALIDATION -> 400;
            case PAYLOAD_TOO_LARGE -> 413;
            case ANALYSIS_FAILED -> 422;
            case UNAVAILABLE -> 503;
        };
        var controller = new FailureController(new PortfolioException(kind, "bounded-code", "Public detail", null));
        mvc(controller, domainFirst).perform(get("/advice-test/failure"))
                .andExpect(status().is(expected))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(expected))
                .andExpect(jsonPath("$.type").value("urn:taxonomy:portfolio:" + kind.name().toLowerCase(Locale.ROOT)))
                .andExpect(jsonPath("$.detail").value("Public detail"))
                .andExpect(jsonPath("$.code").value("bounded-code"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void malformedBodiesKeepTheirPortfolioProblemType(boolean domainFirst) throws Exception {
        mvc(new FailureController(new IllegalStateException("should not execute")), domainFirst)
                .perform(post("/advice-test/body").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:taxonomy:portfolio:malformed-request"))
                .andExpect(jsonPath("$.detail").value(
                        "The portfolio request body is malformed or contains an unsupported value."));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void databaseFailuresRemainSanitizedConflicts(boolean domainFirst) throws Exception {
        var result = mvc(new FailureController(new DataIntegrityViolationException("private SQL / secret")), domainFirst)
                .perform(get("/advice-test/failure"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:taxonomy:portfolio:constraint-conflict"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("private SQL", "secret");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unknownFailuresStillUseTheSanitizedGlobalFallback(boolean domainFirst) throws Exception {
        var result = mvc(new FailureController(new IllegalStateException("private implementation / secret")), domainFirst)
                .perform(get("/advice-test/failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value(
                        "An internal error occurred. Please try again or check the server logs."))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("private implementation", "secret");
    }

    private static MockMvc mvc(FailureController controller, boolean domainFirst) {
        var global = new GlobalExceptionHandler(new StaticMessageSource());
        var domain = new PortfolioExceptionHandler();
        Object[] advices = domainFirst ? new Object[] {domain, global} : new Object[] {global, domain};
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(advices).build();
    }

    @RestController
    public static class FailureController {
        private final RuntimeException failure;

        FailureController(RuntimeException failure) {
            this.failure = failure;
        }

        @GetMapping("/advice-test/failure")
        public void fail() {
            throw failure;
        }

        @PostMapping("/advice-test/body")
        public String body(@RequestBody java.util.Map<String, Object> body) {
            return "parsed";
        }
    }
}
