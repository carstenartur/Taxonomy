package com.taxonomy.architecture.decision;

import com.taxonomy.shared.config.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DecisionReportTemplateExceptionHandlerTest {

    @Test
    void specificHandlerKeepsTemplateFailureAt503AheadOfGenericCatchAll()
            throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new FailingReportController())
                .setControllerAdvice(
                        new GlobalExceptionHandler(new StaticMessageSource()),
                        new DecisionReportTemplateExceptionHandler())
                .build();

        String body = mockMvc.perform(get("/api/decision-report/fixture")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value(
                        DecisionReportTemplateExceptionHandler.PROBLEM_CODE))
                .andExpect(jsonPath("$.path").value(
                        "/api/decision-report/fixture"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .contains(DecisionReportTemplateExceptionHandler.CLIENT_MESSAGE)
                .doesNotContain("jdbc:postgresql://database.internal")
                .doesNotContain("private-template-path");
    }

    @RestController
    private static final class FailingReportController {

        @GetMapping("/api/decision-report/fixture")
        void render() {
            throw new DecisionReportTemplateUnavailableException(
                    "private-template-path contained jdbc:postgresql://database.internal",
                    new IllegalStateException("private validation detail"));
        }
    }
}
