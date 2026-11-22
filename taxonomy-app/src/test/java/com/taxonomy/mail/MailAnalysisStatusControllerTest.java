package com.taxonomy.mail;

import com.taxonomy.shared.config.I18nConfig;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

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
        when(queue.status(TOKEN)).thenReturn(Optional.of(new MailAnalysisQueue.Status(
                MailAnalysisQueue.State.QUEUED, 2, 4, 16, 1, 2, false)));

        mvc.perform(get(PATH + TOKEN))
                .andExpect(status().isOk()).andExpect(view().name("mail-analysis-status"))
                .andExpect(model().attribute("jobToken", TOKEN))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
        mvc.perform(get(PATH + TOKEN + "/status"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.state").value("QUEUED")).andExpect(jsonPath("$.position").value(2))
                .andExpect(jsonPath("$.capacity").value(16)).andExpect(jsonPath("$.runningJobs").value(1))
                .andExpect(jsonPath("$.deliveringJobs").value(2)).andExpect(jsonPath("$.queuedJobs").value(4)).andExpect(jsonPath("$.finished").value(false))
                .andExpect(jsonPath("$.subject").doesNotHaveJsonPath()).andExpect(jsonPath("$.sender").doesNotHaveJsonPath());
    }

    @Test
    void rendersTheLocalizedPartialStatusAndKeepsLiveRefreshInsideTheDeploymentPath() {
        when(queue.status(TOKEN)).thenReturn(Optional.of(new MailAnalysisQueue.Status(
                MailAnalysisQueue.State.PARTIAL, null, 2, 16, 1, 0, true)));
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setMessageSource(new I18nConfig().messageSource());
        var servlet = new MockServletContext();
        var request = new MockHttpServletRequest(servlet);
        request.setContextPath("/taxonomy");
        request.setRequestURI("/taxonomy" + PATH + TOKEN);
        var response = new MockHttpServletResponse();
        var web = JakartaServletWebApplication.buildApplication(servlet).buildExchange(request, response);
        var model = new ExtendedModelMap();
        String view = new MailAnalysisStatusController(queue).page(TOKEN, model, response);
        String html = engine.process(view, new WebContext(web, Locale.GERMAN, model.asMap()));
        assertThat(html).contains("Teilergebnis zugestellt", "Belegte Warteplätze", "Ergebnisse im Versand",
                "data-url=\"/taxonomy" + PATH + TOKEN + "/status\"", "src=\"/taxonomy/js/mail-analysis-status.js\"")
                .doesNotContain("??mail.analysis.");
    }

    @Test
    void rejectsInvalidTokensBeforeLookingUpAJob() throws Exception {
        mvc.perform(get(PATH + "invalid/status")).andExpect(status().isNotFound());
        verifyNoInteractions(queue);
    }
}
