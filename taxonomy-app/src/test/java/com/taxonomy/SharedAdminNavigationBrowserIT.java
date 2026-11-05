package com.taxonomy;

import com.taxonomy.security.controller.UserManagementForm;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.openqa.selenium.By;
import org.openqa.selenium.chromium.HasCdp;
import org.openqa.selenium.remote.Augmenter;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The real templates keep their administrative controls inside the shared application shell. */
@Tag("browser")
@Tag("ci-app-shard-browser")
class SharedAdminNavigationBrowserIT {
    private static PortfolioClientTestPage page;
    private static WebDriverWait wait;

    record View(String template, String path, String retained, String help, Map<String, Object> model) { }

    static Stream<Object[]> views() {
        var snapshot = Map.of("timestamp", "2026-11-05T00:00:00Z", "userCount", 0, "sessionCount", 0,
                "unidentifiedSessionCount", 0, "truncated", false, "users", List.of());
        return Stream.of(
                new View("backup-jobs", "/backups", "backupRefresh", "OPERATIONS_GUIDE/portable-exports", Map.of()),
                new View("browser-sessions", "/admin/sessions", "sessionsRefresh", "SECURITY/browser-sessions", Map.of("snapshot", snapshot)),
                new View("user-management", "/admin/users", "newUserLink", "SECURITY/user-management", Map.of("users", List.of())))
                .flatMap(view -> Stream.of(new Object[]{view, "en", 1440}, new Object[]{view, "de", 390}));
    }

    @BeforeAll static void openBrowser(@TempDir Path downloads) throws Exception {
        page = new PortfolioClientTestPage(downloads);
        wait = new WebDriverWait(page.driver(), Duration.ofSeconds(20));
    }

    @AfterAll static void closeBrowser() { if (page != null) page.close(); }

    @ParameterizedTest
    @MethodSource("views")
    void administrationSharesNavigationWithoutLosingItsControls(View view, String language, int width) {
        page.clearRoutes();
        var cdp = (HasCdp) new Augmenter().augment(page.driver());
        cdp.executeCdpCommand("Emulation.setDeviceMetricsOverride", Map.of(
                "width", width, "height", 1000, "deviceScaleFactor", 1, "mobile", false));
        assertThat(page.driver().executeScript("return innerWidth")).isEqualTo((long) width);
        page.route("GET", "/taxonomy/api/backups/jobs", request -> PortfolioClientTestPage.json(200, List.of()));
        page.openAt(view.template(), "/taxonomy", "/taxonomy" + view.path(), language, "", view.model());
        wait.until(driver -> Boolean.TRUE.equals(page.driver().executeScript("return Boolean(window.TaxonomyAppChrome)")));

        assertThat(page.driver().findElements(By.id("applicationNavigation"))).hasSize(1);
        assertThat(page.driver().findElements(By.cssSelector("#applicationNavigation [data-app-route='architecture']"))).hasSize(1);
        assertThat(page.driver().findElements(By.id(view.retained()))).hasSize(1);
        assertThat(page.driver().findElement(By.cssSelector("#applicationNavigation .context-help")).getDomAttribute("href"))
                .contains("/taxonomy/?lang=" + language + "#help/" + view.help());
        assertThat(page.driver().findElement(By.cssSelector("#applicationNavigation [data-app-locale='" + language + "']"))
                .getDomAttribute("aria-current")).isEqualTo("true");
    }

    @Test
    void realUserFormRegistersDepartureGuardAlongsideTheSharedNavigation() {
        var form = new UserManagementForm("alice", "Alice", "alice@example.test", List.of("ROLE_USER"));
        var model = Map.<String, Object>ofEntries(
                Map.entry("form", form),
                Map.entry(BindingResult.MODEL_KEY_PREFIX + "form", new BeanPropertyBindingResult(form, "form")),
                Map.entry("creating", false), Map.entry("account", Map.of("id", 1)),
                Map.entry("availableRoles", List.of("ROLE_USER", "ROLE_ARCHITECT", "ROLE_ADMIN")),
                Map.entry("minimumPasswordLength", 12), Map.entry("temporaryPasswords", false));
        page.clearRoutes();
        page.openAt("user-management-form", "/taxonomy", "/taxonomy/admin/users/1", "de", "", model);
        assertThat(page.driver().findElements(By.cssSelector("#applicationNavigation [data-app-route='architecture']"))).hasSize(1);
        var displayName = page.driver().findElement(By.id("displayName"));
        assertThat(departureBlocked()).isFalse();
        displayName.clear();
        displayName.sendKeys("Bob");
        assertThat(departureBlocked()).isTrue();
        assertThat(page.driver().findElement(By.id("displayName")).getDomProperty("value")).isEqualTo("Bob");
        displayName.clear();
        displayName.sendKeys("Alice");
        assertThat(departureBlocked()).isFalse();
    }

    private static boolean departureBlocked() {
        return Boolean.TRUE.equals(page.driver().executeScript("""
                const event = new Event('beforeunload', {cancelable: true});
                window.dispatchEvent(event);
                return event.defaultPrevented;
                """));
    }
}
