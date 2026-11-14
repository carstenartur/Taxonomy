package com.taxonomy.relations.controller;

import com.taxonomy.dsl.export.TaxDslExportService;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandMetadata;
import com.taxonomy.relations.service.GitAuthoritativeProposalReviewService;
import com.taxonomy.relations.service.RelationProjectionOperationsService;
import com.taxonomy.relations.service.RelationProposalService;
import com.taxonomy.testsupport.BrowserSession;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.service.ArchitectureRepositoryProvisioningService;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryWorkspaceService;
import com.taxonomy.workspace.service.WorkspaceManager;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.support.ui.ExpectedConditions;
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
    @Autowired ArchitectureRepositoryProvisioningService provisioning;
    @Autowired DslGitRepositoryFactory repositories;
    @Autowired RepositoryWorkspaceService workingCopies;
    @Autowired WorkspaceManager workspaces;
    @Autowired TaxDslExportService catalogue;
    @Autowired RelationProjectionOperationsService projections;
    @Autowired RelationProposalService proposals;
    @Autowired GitAuthoritativeProposalReviewService reviews;

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

    @Test
    void acceptedOnMainCanBeInspectedThenAdoptedOnTheSelectedBranch(@TempDir Path downloads) throws Exception {
        var repository = provisioning.createRepository("Proposal review", "adopt-" + UUID.randomUUID(),
                "Branch adoption", RepositoryVisibility.PRIVATE, "admin", "main");
        String repositoryId = repository.getRepositoryId();
        var git = repositories.getCentralRepository(repositoryId);
        String baseline = git.commitDsl("main", catalogue.exportAll("default"), "admin", "Catalogue");
        // Create the working copy before acceptance so the draft has an independently observable old Git head.
        var workspace = workingCopies.createWorkingCopy("admin", repositoryId, "main", "Entwurf", "");
        String workspaceId = workspace.getWorkspaceId();
        String draft = workspace.getCurrentBranch();
        var draftGit = repositories.getWorkspaceRepository(workspaceId);
        assertThat(draftGit.getHeadCommit(draft)).isEqualTo(baseline);
        workspaces.switchWorkspace("admin", workspaceId);
        var source = RepositoryContext.centralWrite(repositoryId, "main", "admin");
        var target = RepositoryContext.workspace(repositoryId, workspaceId, draft, "admin");
        projections.rebuild(source, baseline);
        projections.rebuild(target, baseline);
        var proposal = proposals.createFromHypothesisInContext("CP-1023", "CR-1047", RelationType.REALIZES,
                0.73, "Branch adoption", source);
        var accepted = reviews.accept(proposal.getId(), source, baseline, new CommandMetadata(UUID.randomUUID().toString()));
        String sourceCommit = accepted.mutation().authority().authoritativeCommitId();
        String detail = "#proposal-adoption-" + proposal.getId();
        String targetUrl = "/?architectureSource=working&repositoryId=" + repositoryId
                + "&workspaceId=" + workspaceId + "&workspaceScopeKey=" + workspaceId
                + "&branch=" + draft + "&lang=de#architecture";
        try (var session = BrowserSession.open(port, downloads)) {
            var driver = session.driver();
            var wait = new WebDriverWait(driver, Duration.ofSeconds(45));
            driver.get(session.origin() + "/login");
            driver.findElement(By.name("username")).sendKeys("admin");
            driver.findElement(By.name("password")).sendKeys(PASSWORD);
            driver.findElement(By.cssSelector("form")).submit();
            wait.until(ExpectedConditions.urlMatches("^(?!.*?/login(?:[?#].*)?$).*$"));
            driver.get(session.origin() + targetUrl);
            wait.until(browser -> Boolean.TRUE.equals(((JavascriptExecutor) browser).executeScript("""
                    const scope = window.TaxonomyArchitectureWorkflow?.getScope();
                    const navigation = window.TaxonomyContextBar?.getCurrentContext();
                    return scope?.repositoryId === arguments[0] && scope.workspaceId === arguments[1]
                            && scope.branch === arguments[2] && navigation?.mode === 'EDITABLE';
                    """, repositoryId, workspaceId, draft)));
            openAcceptedProposals(wait, driver, detail);
            var available = wait.until(ExpectedConditions.visibilityOfElementLocated(
                    By.cssSelector(detail + "[data-state='AVAILABLE']")));
            assertThat(available.getText()).contains("Auf main angenommen", draft,
                    sourceCommit.substring(0, 8), baseline.substring(0, 8));
            assertThat(draftGit.getHeadCommit(draft)).as("Inspection is read-only").isEqualTo(baseline);
            available.findElement(By.cssSelector(".proposal-adoption-apply")).click();
            String adopted = wait.until(browser -> {
                try {
                    String head = draftGit.getHeadCommit(draft);
                    return baseline.equals(head) ? null : head;
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            assertThat(git.getHeadCommit("main")).isEqualTo(sourceCommit);
            assertThat(draftGit.getDslAtCommit(adopted)).contains("proposal-adoption", sourceCommit);
            // Reopen the inbox after a full reload to read the persisted Git outcome.
            driver.navigate().refresh();
            openAcceptedProposals(wait, driver, detail);
            var present = wait.until(ExpectedConditions.visibilityOfElementLocated(
                    By.cssSelector(detail + "[data-state='ALREADY_PRESENT']")));
            assertThat(present.findElements(By.cssSelector(".proposal-adoption-apply"))).isEmpty();
            assertThat(draftGit.getHeadCommit(draft)).isEqualTo(adopted);
        }
    }

    private static void openAcceptedProposals(WebDriverWait wait, WebDriver driver, String detail) {
        wait.until(ExpectedConditions.elementToBeClickable(By.id("applicationArchitectureLink"))).click();
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("architectureWorkingSource")));
        wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector("#workingProposalInbox #filterAccepted"))).click();
        var rowControl = wait.until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector("button[aria-controls='" + detail.substring(1) + "']")));
        new Actions(driver).scrollToElement(rowControl).perform();
        wait.withMessage(() -> driver.findElement(By.id("proposalsTableContainer")).getText());
    }
}
