package com.taxonomy.architecture.decision;

import com.taxonomy.shared.config.GlobalExceptionHandler;
import com.taxonomy.templates.DecisionReportAvailabilityContract;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DecisionReportAvailabilityMvcTest.ThrowingController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({
    DecisionReportAvailabilityExceptionHandler.class,
    GlobalExceptionHandler.class
})
class DecisionReportAvailabilityMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void typedTemplateAdviceWinsBeforeGlobalExceptionCatchAll() throws Exception {
        mockMvc.perform(get("/test/decision-report-unavailable")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value(
                        DecisionReportAvailabilityContract.PROBLEM_CODE))
                .andExpect(jsonPath("$.message").value(
                        DecisionReportAvailabilityContract.RESPONSE_MESSAGE))
                .andExpect(jsonPath("$.message").value(not(containsString("/srv/private"))))
                .andExpect(jsonPath("$.remediation").value(
                        DecisionReportAvailabilityContract.REMEDIATION));
    }

    @RestController
    static final class ThrowingController {

        @GetMapping("/test/decision-report-unavailable")
        void unavailable() {
            throw new DecisionReportTemplateUnavailableException(
                    "word/document.xml failed at /srv/private/template.dotx",
                    new IllegalArgumentException("private template payload"));
        }
    }
}
