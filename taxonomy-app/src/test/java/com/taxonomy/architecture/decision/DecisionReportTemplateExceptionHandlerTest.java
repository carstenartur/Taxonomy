package com.taxonomy.architecture.decision;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.taxonomy.shared.config.GlobalExceptionHandler;
import com.taxonomy.templates.DecisionReportAvailabilityContract;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DecisionReportTemplateExceptionHandlerTest {

    @Test
    void specificHandlerKeepsTemplateFailureAtSanitized503AheadOfGenericCatchAll()
            throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(
                DecisionReportTemplateExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
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
                            DecisionReportAvailabilityContract.PROBLEM_CODE))
                    .andExpect(jsonPath("$.message").value(
                            DecisionReportAvailabilityContract.SAFE_UNAVAILABLE_SUMMARY))
                    .andExpect(jsonPath("$.remediation").value(
                            DecisionReportAvailabilityContract.REMEDIATION))
                    .andExpect(jsonPath("$.capability").value(
                            DecisionReportAvailabilityContract.CAPABILITY))
                    .andExpect(jsonPath("$.path").value(
                            "/api/decision-report/fixture"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            assertThat(body)
                    .doesNotContain("jdbc:postgresql://database.internal")
                    .doesNotContain("private-template-path")
                    .doesNotContain("private validation detail");

            List<ILoggingEvent> handlerEvents = appender.list.stream()
                    .filter(event -> event.getFormattedMessage().startsWith(
                            "Decision report template unavailable on "))
                    .toList();
            assertThat(handlerEvents).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage()).isEqualTo(
                        "Decision report template unavailable on "
                                + "/api/decision-report/fixture");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
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
