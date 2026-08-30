package com.taxonomy.portfolio.controller;

import com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus;
import com.taxonomy.portfolio.service.PortfolioException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PortfolioExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new MalformedRequestProbeController())
                .setControllerAdvice(new PortfolioExceptionHandler())
                .build();
    }

    @Test
    void validProjectStatusStillDeserializes() throws Exception {
        mockMvc.perform(post("/portfolio-handler-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PLANNING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PLANNING"));
    }

    @Test
    void malformedProjectStatusReturnsBoundedProblemDetail() throws Exception {
        mockMvc.perform(post("/portfolio-handler-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DRAFT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid portfolio request body"))
                .andExpect(jsonPath("$.detail").value(
                        "The portfolio request body is malformed or contains an unsupported value."))
                .andExpect(jsonPath("$.type").value(
                        "urn:taxonomy:portfolio:malformed-request"))
                .andExpect(jsonPath("$.detail", not(containsString("PortfolioTypes"))))
                .andExpect(jsonPath("$.detail", not(containsString("Cannot deserialize"))))
                .andExpect(jsonPath("$.detail", not(containsString("com.taxonomy"))));
    }

    @Test
    void everyTypedPortfolioFailureHasItsOwnStableProblemContract() {
        PortfolioExceptionHandler handler = new PortfolioExceptionHandler();
        Map<PortfolioException.Kind, ExpectedProblem> contracts = Map.of(
                PortfolioException.Kind.NOT_FOUND,
                new ExpectedProblem(HttpStatus.NOT_FOUND, "Portfolio resource not found"),
                PortfolioException.Kind.CONFLICT,
                new ExpectedProblem(HttpStatus.CONFLICT, "Portfolio state conflict"),
                PortfolioException.Kind.VALIDATION,
                new ExpectedProblem(HttpStatus.BAD_REQUEST, "Invalid portfolio request"),
                PortfolioException.Kind.PAYLOAD_TOO_LARGE,
                new ExpectedProblem(HttpStatus.PAYLOAD_TOO_LARGE, "AI prompt budget exceeded"),
                PortfolioException.Kind.ANALYSIS_FAILED,
                new ExpectedProblem(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "Portfolio analysis payload failure"),
                PortfolioException.Kind.UNAVAILABLE,
                new ExpectedProblem(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Portfolio analysis capacity unavailable"));

        contracts.forEach((kind, expected) -> {
            String detail = "bounded detail for " + kind.name();
            ResponseEntity<ProblemDetail> response = handler.handlePortfolioException(
                    new PortfolioException(kind, detail));

            assertThat(response.getStatusCode()).isEqualTo(expected.status());
            assertThat(response.getBody()).isNotNull();
            ProblemDetail problem = response.getBody();
            assertThat(problem.getStatus()).isEqualTo(expected.status().value());
            assertThat(problem.getTitle()).isEqualTo(expected.title());
            assertThat(problem.getDetail()).isEqualTo(detail);
            assertThat(problem.getType()).hasToString(
                    "urn:taxonomy:portfolio:" + kind.name().toLowerCase());
        });
    }

    @Test
    void databaseConstraintFailureDoesNotExposePersistenceDiagnostics() {
        PortfolioExceptionHandler handler = new PortfolioExceptionHandler();
        ResponseEntity<ProblemDetail> response = handler.handleConstraintViolation(
                new DataIntegrityViolationException("secret table and SQL details"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        ProblemDetail problem = response.getBody();
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getTitle()).isEqualTo("Portfolio constraint conflict");
        assertThat(problem.getDetail()).isEqualTo(
                "The requested portfolio change violates a uniqueness or reference constraint.");
        assertThat(problem.getDetail()).doesNotContain("secret", "SQL");
        assertThat(problem.getType()).hasToString(
                "urn:taxonomy:portfolio:constraint-conflict");
    }

    private record ExpectedProblem(HttpStatus status, String title) {
    }

    @RestController
    static class MalformedRequestProbeController {

        record ProbeRequest(ProjectStatus status) {
        }

        @PostMapping("/portfolio-handler-test")
        ProbeRequest parse(@RequestBody ProbeRequest request) {
            return request;
        }
    }
}
