package com.taxonomy.relations.controller;

import com.taxonomy.testsupport.BrowserSession;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("browser")
@Tag("ci-app-shard-browser")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "taxonomy.security.require-password-change=false")
class ProposalInboxStartupUiIT {
    private static final String PASSWORD = "Proposal-" + UUID.randomUUID();
    @LocalServerPort int port;
    @Autowired ObjectMapper json;

    @DynamicPropertySource
    static void credentials(DynamicPropertyRegistry properties) {
        properties.add("taxonomy.admin-password", () -> PASSWORD);
    }

    @Test
    void firstLoginReadsPendingProposalsFromTheDisplayedSource(@TempDir Path downloads) throws Exception {
        try (var session = BrowserSession.openWithNetworkLogging(port, downloads)) {
            var driver = session.driver();
            driver.get(session.origin() + "/login");
            driver.findElement(By.name("username")).sendKeys("admin");
            driver.findElement(By.name("password")).sendKeys(PASSWORD);
            driver.findElement(By.cssSelector("form")).submit();
            JsonNode response = new WebDriverWait(driver, Duration.ofSeconds(45)).until(browser -> {
                for (var entry : browser.manage().logs().get(LogType.PERFORMANCE)) {
                    var event = json.readTree(entry.getMessage()).path("message");
                    if (!event.path("method").asText().equals("Network.responseReceived")) continue;
                    var received = event.path("params").path("response");
                    String requestUrl = received.path("url").asText();
                    if (requestUrl.split("\\?", 2)[0].equals(session.origin() + "/api/proposals/pending")) {
                        return received;
                    }
                }
                return null;
            });
            assertThat(response.path("status").asInt()).isEqualTo(200);
            var parameters = UriComponentsBuilder.fromUriString(response.path("url").asText()).build().getQueryParams();
            var source = (Map<?, ?>) ((JavascriptExecutor) driver).executeScript("return window.TaxonomyArchitectureWorkflow.getScope()");
            assertThat(parameters.getFirst("repositoryId")).isNotBlank().isEqualTo(source.get("repositoryId"));
            assertThat(parameters.getFirst("branch")).isNotBlank().isEqualTo(source.get("branch"));
            assertThat(parameters.getFirst("workspaceId")).isNotNull().isEqualTo(source.get("workspaceId"));
        }
    }
}
