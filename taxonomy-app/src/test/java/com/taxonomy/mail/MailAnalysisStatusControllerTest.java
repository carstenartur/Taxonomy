package com.taxonomy.mail;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

class MailAnalysisStatusControllerTest {
    private static final String TOKEN = "1c4e1f62-57b6-4102-91e4-a9832d4684bd";
    private static final String PATH = "/mail-analysis/jobs/";
    private final MailAnalysisQueue queue = mock(MailAnalysisQueue.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new MailAnalysisStatusController(queue)).build();

    @Test
    void rendersAnUncachedJobStatusAndReturnsOnlyOperationalData() throws Exception {
        var now = Instant.now();
        when(queue.status(TOKEN)).thenReturn(Optional.of(new MailAnalysisQueue.Status(
                MailAnalysisQueue.State.QUEUED, 2, now, now, 4)));

        mvc.perform(get(PATH + TOKEN))
                .andExpect(status().isOk()).andExpect(view().name("mail-analysis-status"))
                .andExpect(model().attribute("jobToken", TOKEN))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
        mvc.perform(get(PATH + TOKEN + "/status"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.state").value("QUEUED")).andExpect(jsonPath("$.position").value(2))
                .andExpect(jsonPath("$.queuedJobs").value(4)).andExpect(jsonPath("$.finished").value(false))
                .andExpect(jsonPath("$.subject").doesNotHaveJsonPath()).andExpect(jsonPath("$.sender").doesNotHaveJsonPath());
    }

    @Test
    void rejectsInvalidTokensBeforeLookingUpAJob() throws Exception {
        mvc.perform(get(PATH + "invalid/status")).andExpect(status().isNotFound());
        verifyNoInteractions(queue);
    }
}
