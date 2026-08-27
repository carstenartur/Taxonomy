package com.taxonomy.portfolio.controller;

import com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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
