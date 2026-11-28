package com.taxonomy.mail;

import com.taxonomy.shared.config.I18nConfig;
import org.jsoup.Jsoup;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
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
        var summary = new MailAnalysisQueue.Status(MailAnalysisQueue.State.QUEUED, 2, 4, 16, 1, 2, false);
        var now = Instant.parse("2026-11-28T10:00:00Z");
        var job = new MailAnalysisQueue.JobView("TX-7", true, MailAnalysisQueue.State.QUEUED,
                2, now, null, null, 120L);
        when(queue.status(TOKEN)).thenReturn(Optional.of(summary));
        when(queue.snapshot(TOKEN)).thenReturn(Optional.of(new MailAnalysisQueue.Snapshot(summary,
                now, true, 1, MailAnalysisQueue.WorkerState.RUNNING, 5, 60L, job, List.of(job))));

        mvc.perform(get(PATH + TOKEN))
                .andExpect(status().isOk()).andExpect(view().name("mail-analysis-status"))
                .andExpect(model().attribute("jobToken", TOKEN))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
        mvc.perform(get(PATH + TOKEN + "/status"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.state").value("QUEUED")).andExpect(jsonPath("$.position").value(2))
                .andExpect(jsonPath("$.capacity").value(16)).andExpect(jsonPath("$.runningJobs").value(1))
                .andExpect(jsonPath("$.deliveringJobs").value(2)).andExpect(jsonPath("$.queuedJobs").value(4)).andExpect(jsonPath("$.finished").value(false))
                .andExpect(jsonPath("$.workerState").value("RUNNING"))
                .andExpect(jsonPath("$.job.displayId").value("TX-7")).andExpect(jsonPath("$.jobs[0].own").value(true))
                .andExpect(jsonPath("$.job.estimatedWaitSeconds").value(120))
                .andExpect(jsonPath("$.job.token").doesNotHaveJsonPath())
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
                "data-url=\"/taxonomy" + PATH + TOKEN + "/status\"", "src=\"/taxonomy/js/mail-analysis-status.js\"",
                "data-readiness-url=\"/taxonomy/actuator/health/readiness\"",
                "data-liveness-url=\"/taxonomy/actuator/health/liveness\"",
                "href=\"/taxonomy/css/mail-analysis-status.css\"")
                .contains("data-authenticated-surfaces=\"false\"", "src=\"/taxonomy/js/api/taxonomy-api-client.js\"")
                .doesNotContain("??mail.analysis.");
        var document = Jsoup.parse(html);
        assertThat(document.select("[data-server-fallback]:not([hidden])").text()).contains("Teilergebnis zugestellt");
        assertThat(document.select("[data-enhanced-dashboard][hidden] #jobs")).hasSize(1);
        assertThat(document.select("link[href]").eachAttr("href")).allMatch(url -> url.startsWith("/taxonomy/"));
        assertThat(document.select("[src]").eachAttr("src")).allMatch(url -> url.startsWith("/taxonomy/"));
        assertThat(document.select("html").attr("lang")).isEqualTo("de");
        assertThat(engine.process(view, new WebContext(web, Locale.ENGLISH, model.asMap())))
                .contains("Partial results delivered", "Queue places in use", "Results awaiting delivery");
    }

    @Test
    void sameTemplateOpensAsAStandalonePortalWithoutAReceiptOrAuthenticatedAssets() throws IOException {
        try (var input = getClass().getResourceAsStream("/templates/mail-analysis-status.html")) {
            assertThat(input).isNotNull();
            var html = Jsoup.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            assertThat(html.select("html[data-url], html[data-readiness-url], html[data-liveness-url]")).isEmpty();
            assertThat(html.select("[data-server-fallback][hidden]")).hasSize(1);
            assertThat(html.select("script[data-authenticated-surfaces=false]").attr("src"))
                    .isEqualTo("./js/api/taxonomy-api-client.js");
            for (var element : html.select("script[src], link[rel=stylesheet], img[src]")) {
                String relative = element.attr(element.hasAttr("src") ? "src" : "href");
                assertThat(relative).startsWith("./");
                assertThat(getClass().getResource("/static/" + relative.substring(2))).as(relative).isNotNull();
            }
        }
    }

    @Test
    void expiredAndMalformedReceiptPagesGiveTheSameUncachedExplanation() throws Exception {
        for (var token : new String[]{TOKEN, "bad-token"}) {
            mvc.perform(get(PATH + token))
                    .andExpect(status().isNotFound()).andExpect(view().name("mail-analysis-link-unavailable"))
                    .andExpect(model().attributeDoesNotExist("status", "jobToken"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"))
                    .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"));
        }
    }

    @Test
    void rejectsInvalidTokensBeforeLookingUpAJob() throws Exception {
        mvc.perform(get(PATH + "invalid/status")).andExpect(status().isNotFound());
        verifyNoInteractions(queue);
    }
}
