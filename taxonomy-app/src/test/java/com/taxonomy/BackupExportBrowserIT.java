package com.taxonomy;

import com.taxonomy.backup.*;
import com.taxonomy.backup.runtime.BackupManifestCodec;
import com.taxonomy.backup.jobs.BackupExportOptions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openqa.selenium.By;
import org.openqa.selenium.ElementClickInterceptedException;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.assertThat;

/** Actual Thymeleaf, localized scripts, native validation and streaming browser download.
 * Persistence/archive acceptance is independently covered by ProductionCurrentBackupIT. */
@Tag("browser")
@Tag("ui-acceptance")
class BackupExportBrowserIT {
    @TempDir static Path downloads;
    private static PortfolioClientTestPage page;
    private static WebDriverWait wait;
    private final List<BackupRequest> submissions = new CopyOnWriteArrayList<>();

    @BeforeAll static void browser() throws Exception {
        page = new PortfolioClientTestPage(downloads);
        wait = new WebDriverWait(page.driver(), Duration.ofSeconds(20));
        var capabilities = page.driver().getCapabilities();
        var chrome = capabilities.getCapability("chrome");
        Object driverVersion = chrome instanceof Map<?, ?> values ? values.get("chromedriverVersion") : "unreported";
        System.out.printf("Portable backup browser acceptance: browser=%s version=%s driver=%s%n",
                capabilities.getBrowserName(), capabilities.getBrowserVersion(), driverVersion);
    }
    @AfterAll static void close() throws Exception { if (page != null) page.close(); }

    @ParameterizedTest @ValueSource(strings = {"en", "de"})
    void createsScopedJobAndDownloadsWithoutAnArchiveSizedJavascriptBlob(String locale) throws Exception {
        page.clearRoutes(); page.clearRequests();
        String prefix = "/teams/blue/taxonomy";
        String job = UUID.randomUUID().toString(), filename = "backup-" + job + ".taxbackup";
        var ready = Map.of("id", job, "profile", "CURRENT_STATE", "state", "READY", "failure", "NONE",
                "progressBytes", 23, "expiresAt", "2026-10-12T00:00:00Z", "downloadAvailable", true,
                "cancellationRequested", false, "encrypted", false);
        page.route("GET", prefix + "/api/backups/jobs", r -> PortfolioClientTestPage.json(200,
                submissions.isEmpty() ? List.of() : List.of(ready)));
        page.route("POST", prefix + "/api/backups/jobs", r -> {
            submissions.add(new BackupManifestCodec().readRequest(r.body().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return PortfolioClientTestPage.json(202, Map.of("id", job));
        });
        page.route("GET", prefix + "/api/backups/jobs/" + job, r -> PortfolioClientTestPage.json(200, ready));
        page.route("GET", prefix + "/api/backups/jobs/" + job + "/download", r ->
                new PortfolioClientTestPage.Response(200, "application/octet-stream", "native-download-fixture")
                        .withHeader("Content-Disposition", "attachment; filename=\"" + filename + "\""));
        var options = new BackupExportOptions(List.of(new BackupExportOptions.Selection("owned", "fixture-repo",
                "fixture-private", false, "Private fixture workspace",false)));
        page.openAt("backup-jobs", prefix, prefix + "/backup-jobs", locale, "", Map.of("backupExportOptions", options));
        var driver = page.driver();
        wait.until(d -> d.findElement(By.id("backupExportHeading")).getText().contains(
                locale.equals("de") ? "Portablen Stand exportieren" : "Create a portable export"));
        assertThat(driver.findElement(By.id("backupExportCommit")).isEnabled()).isFalse();
        clickVisible(By.id("backupExportSubmit"), locale, "create-export");
        wait.until(d -> submissions.size() == 1 && !d.findElements(By.cssSelector("#backupJobList a[href$='/download']")).isEmpty());
        assertThat(submissions).containsExactly(new BackupRequest(BackupProfile.CURRENT_STATE,
                new BackupScope.Workspace("fixture-repo", "fixture-private"), new BackupTime.Current(), GitRepresentation.NONE, SecretsSelection.EXCLUDE));
        var link = driver.findElement(By.cssSelector("#backupJobList a[href$='/download']"));
        assertThat(link.getDomAttribute("href")).isEqualTo(prefix + "/api/backups/jobs/" + job + "/download");
        clickVisible(By.cssSelector("#backupJobList a[href$='/download']"), locale, "download");
        wait.until(d -> page.browser().downloadedFiles().contains(filename));
        assertThat(Files.readString(page.browser().download(filename))).isEqualTo("native-download-fixture");
        new Select(driver.findElement(By.id("backupExportProfile"))).selectByValue("SELECTED_VERSION");
        assertThat(driver.findElement(By.id("backupExportCommit")).isEnabled()).isTrue();
        driver.findElement(By.id("backupExportCommit")).sendKeys("deadbeef");
        clickVisible(By.id("backupExportSubmit"), locale, "invalid-commit");
        assertThat((Boolean) driver.executeScript("return document.getElementById('backupExportCommit').validity.valid")).isFalse();
        assertThat(submissions).hasSize(1);
        assertThat(page.requests()).anyMatch(r -> r.method().equals("GET") && r.path().endsWith("/download"));
    }

    @ParameterizedTest @ValueSource(strings = {"en", "de"})
    void historyFormatsFollowTheSelectedWorkspaceAndResetForCurrentAndInstallation(String locale) throws Exception {
        page.clearRoutes();page.clearRequests();
        String prefix="/teams/blue/taxonomy",job=UUID.randomUUID().toString();
        page.route("GET",prefix+"/api/backups/jobs",r->PortfolioClientTestPage.json(200,List.of()));
        page.route("POST",prefix+"/api/backups/jobs",r->{
            submissions.add(new BackupManifestCodec().readRequest(r.body().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return PortfolioClientTestPage.json(202,Map.of("id",job));
        });
        var options=new BackupExportOptions(List.of(
                new BackupExportOptions.Selection("current","fixture-repo","current-private",false,"Current-only workspace",false),
                new BackupExportOptions.Selection("history","fixture-repo","history-private",false,"History-enabled workspace",true),
                new BackupExportOptions.Selection("installation",null,null,true,"",false)));
        page.openAt("backup-jobs",prefix,prefix+"/backup-jobs",locale,"",Map.of("backupExportOptions",options));
        var driver=page.driver();
        wait.until(d->d.findElement(By.id("backupExportHeading")).getText().contains(
                locale.equals("de")?"Portablen Stand exportieren":"Create a portable export"));
        var scope=new Select(driver.findElement(By.id("backupExportScope")));
        var profile=new Select(driver.findElement(By.id("backupExportProfile")));
        var representation=new Select(driver.findElement(By.id("backupExportGit")));
        assertThat(driver.findElement(By.id("backupExportHistoryOption")).isEnabled()).isFalse();
        scope.selectByValue("history");
        assertThat(driver.findElement(By.id("backupExportHistoryOption")).isEnabled()).isTrue();
        profile.selectByValue("REPOSITORY_HISTORY");
        assertThat(representation.getFirstSelectedOption().getDomProperty("value")).isEqualTo("BUNDLE");
        assertThat(representation.getOptions().stream().filter(option->option.isEnabled()).map(option->option.getDomProperty("value"))).containsExactly("BUNDLE","BARE","WORKTREE");
        assertThat(driver.findElement(By.id("backupExportCommit")).isEnabled()).isFalse();
        assertThat(driver.findElement(By.id("backupExportCurrentHelp")).isDisplayed()).isFalse();
        assertThat(driver.findElement(By.id("backupExportHistoryHelp")).getText()).contains(
                locale.equals("de")?"ursprüngliche Git-Commits":"original Git commits");
        for(var format:List.of(GitRepresentation.BUNDLE,GitRepresentation.BARE,GitRepresentation.WORKTREE)) {
            representation.selectByValue(format.name());int expected=submissions.size()+1;
            clickVisible(By.id("backupExportSubmit"),locale,"history-"+format.name());
            wait.until(d->submissions.size()==expected);
            assertThat(submissions.getLast()).isEqualTo(new BackupRequest(BackupProfile.REPOSITORY_HISTORY,
                    new BackupScope.Workspace("fixture-repo","history-private"),new BackupTime.History(),format,SecretsSelection.EXCLUDE));
        }
        scope.selectByValue("current");
        assertThat(profile.getFirstSelectedOption().getDomProperty("value")).isEqualTo("CURRENT_STATE");
        assertThat(representation.getFirstSelectedOption().getDomProperty("value")).isEqualTo("NONE");
        assertThat(driver.findElement(By.id("backupExportHistoryOption")).isEnabled()).isFalse();
        assertThat(driver.findElement(By.id("backupExportHistoryHelp")).isDisplayed()).isFalse();
        profile.selectByValue("SELECTED_VERSION");
        assertThat(driver.findElement(By.id("backupExportCommit")).isEnabled()).isTrue();
        scope.selectByValue("installation");
        assertThat(profile.getFirstSelectedOption().getDomProperty("value")).isEqualTo("CURRENT_STATE");
        assertThat(driver.findElement(By.id("backupExportProfile")).isEnabled()).isFalse();
        clickVisible(By.id("backupExportSubmit"),locale,"installation-after-history");
        wait.until(d->submissions.size()==4);
        assertThat(submissions.getLast()).isEqualTo(new BackupRequest(BackupProfile.INSTALLATION_CURRENT,
                new BackupScope.Installation(),new BackupTime.Current(),GitRepresentation.NONE,SecretsSelection.EXCLUDE));
    }

    private void clickVisible(By locator, String locale, String phase) {
        var driver = page.driver();
        try {
            new WebDriverWait(driver, Duration.ofSeconds(20))
                    .ignoring(ElementClickInterceptedException.class)
                    .ignoring(StaleElementReferenceException.class)
                    .until(ignored -> {
                        var control = driver.findElement(locator);
                        if (!control.isDisplayed() || !control.isEnabled()) return false;
                        driver.executeScript("arguments[0].scrollIntoView({behavior:'instant',block:'center',inline:'nearest'});", control);
                        boolean reachable = Boolean.TRUE.equals(driver.executeScript("""
                                const element=arguments[0], r=element.getBoundingClientRect();
                                const x=r.left+r.width/2, y=r.top+r.height/2;
                                if (r.width<=0 || r.height<=0 || x<0 || y<0 || x>=innerWidth || y>=innerHeight) return false;
                                const hit=document.elementFromPoint(x,y);
                                return hit===element || element.contains(hit);
                                """, control));
                        if (!reachable) return false;
                        control.click();
                        return true;
                    });
        } catch (RuntimeException failure) {
            try {
                Path directory = Path.of("target", "failsafe-reports"); Files.createDirectories(directory);
                String name = "BackupExportBrowserIT-" + locale + "-" + phase;
                Files.write(directory.resolve(name + ".png"), driver.getScreenshotAs(OutputType.BYTES));
                // Structural evidence only: no form values, cookies, credentials, or page text.
                Object diagnostic = driver.executeScript("""
                        const element=arguments[0], r=element.getBoundingClientRect();
                        const hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);
                        function shape(e) { if(!e) return null; const b=e.getBoundingClientRect(),s=getComputedStyle(e);
                          return {tag:e.tagName,id:e.id,classes:e.className,x:b.x,y:b.y,width:b.width,height:b.height,
                            position:s.position,pointerEvents:s.pointerEvents,visibility:s.visibility,zIndex:s.zIndex}; }
                        return JSON.stringify({viewport:{width:innerWidth,height:innerHeight},scroll:{x:scrollX,y:scrollY},
                          target:shape(element),hit:shape(hit),ancestors:Array.from((function*(e){for(let n=e;n;n=n.parentElement)yield n;})(hit)).map(shape)});
                        """, driver.findElement(locator));
                Files.writeString(directory.resolve(name + ".json"), String.valueOf(diagnostic));
            } catch (Exception diagnosticFailure) { failure.addSuppressed(diagnosticFailure); }
            throw failure;
        }
    }
}
