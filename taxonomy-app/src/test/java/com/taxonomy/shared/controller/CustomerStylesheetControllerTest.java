package com.taxonomy.shared.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockServletContext;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CustomerStylesheetControllerTest {
    @TempDir Path directory;

    @Test
    void absentConfigurationDoesNotEnableTheStylesheet() {
        var controller = new CustomerStylesheetController("");
        assertThat(controller.customerStylesheetEnabled()).isFalse();
        assertThat(controller.stylesheet().getBody()).isEmpty();
    }

    @Test
    void configuredUtf8FileIsServedWithoutCachingAndCanBeUpdated() throws Exception {
        Path file = directory.resolve("customer.css");
        Files.writeString(file, ".brand { color: #123456; } /* Büro */", StandardCharsets.UTF_8);
        var controller = new CustomerStylesheetController(file.toString());
        assertThat(controller.customerStylesheetEnabled()).isTrue();
        var first = controller.stylesheet();
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getHeaders().getContentType().toString()).contains("text/css", "UTF-8");
        assertThat(first.getHeaders().getCacheControl()).contains("no-store");
        assertThat(first.getBody()).contains("Büro", "#123456");
        Files.writeString(file, ".brand { color: #654321; }", StandardCharsets.UTF_8);
        assertThat(controller.stylesheet().getBody()).contains("#654321");
    }

    @Test
    void sameOriginCssRouteReturnsTheConfiguredFile() throws Exception {
        Path file = directory.resolve("customer.css");
        Files.writeString(file, ".brand { color: #123456; }", StandardCharsets.UTF_8);
        var mvc = MockMvcBuilders.standaloneSetup(new CustomerStylesheetController(file.toString())).build();
        mvc.perform(get("/css/customer-theme.css"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith("text/css"))
                .andExpect(content().string(".brand { color: #123456; }"));
    }

    @Test
    void stylesheetSettingIsAvailableToOtherPageControllers() throws Exception {
        var advice = new CustomerStylesheetController(directory.resolve("customer.css").toString());
        var mvc = MockMvcBuilders.standaloneSetup(new ExamplePageController())
                .setControllerAdvice(advice).build();
        mvc.perform(get("/example-page"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("customerStylesheetEnabled", true));
    }

    @Test
    void springDiscoversTheRouteAndGlobalModelSettingTogether() throws Exception {
        Path file = directory.resolve("customer.css");
        Files.writeString(file, ".brand { color: navy; }", StandardCharsets.UTF_8);
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                    Map.of("taxonomy.ui.custom-stylesheet", file.toString())));
            context.register(ExampleWebConfiguration.class);
            context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(get("/example-page"))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("customerStylesheetEnabled", true));
            mvc.perform(get("/css/customer-theme.css"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(".brand { color: navy; }"));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    static class ExampleWebConfiguration {
        @Bean CustomerStylesheetController stylesheet(Environment environment) {
            return new CustomerStylesheetController(environment.getProperty("taxonomy.ui.custom-stylesheet", ""));
        }
        @Bean ExamplePageController examplePageController() { return new ExamplePageController(); }
    }

    @Controller
    @TestComponent
    static class ExamplePageController {
        @GetMapping("/example-page")
        String examplePage() { return "example-template"; }
    }

    @Test
    void unreadableOversizedAndInvalidUtf8FilesFailWithoutReturningTheirContent() throws Exception {
        Path file = directory.resolve("customer.css");
        var controller = new CustomerStylesheetController(file.toString());
        assertThat(controller.stylesheet().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        Files.write(file, new byte[CustomerStylesheetController.MAX_STYLESHEET_BYTES + 1]);
        assertThat(controller.stylesheet().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        Files.write(file, new byte[] {(byte) 0xc3, 0x28});
        var invalid = controller.stylesheet();
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(invalid.getBody()).doesNotContain("#123456");
    }
}
