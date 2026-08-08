#!/usr/bin/env python3
"""Wait for real application readiness before browser authentication tests."""

from pathlib import Path

container_path = Path("taxonomy-app/src/test/java/com/taxonomy/ContainerTestUtils.java")
container = container_path.read_text(encoding="utf-8")
old_probe = '.waitingFor(Wait.forHttp("/actuator/health")\n'
new_probe = '.waitingFor(Wait.forHttp("/actuator/health/readiness")\n'
if container.count(old_probe) != 2:
    raise SystemExit(
        f"Expected two generic health wait strategies, found {container.count(old_probe)}"
    )
container_path.write_text(container.replace(old_probe, new_probe), encoding="utf-8")

portfolio_path = Path(
    "taxonomy-app/src/test/java/com/taxonomy/PortfolioUiAcceptanceIT.java"
)
portfolio = portfolio_path.read_text(encoding="utf-8")
old_login = '''    private static void login() {
        driver.get(ContainerTestUtils.APP_ORIGIN + "/login");
        wait.until(ExpectedConditions.presenceOfElementLocated(By.name("username")))
                .sendKeys("admin");
        driver.findElement(By.name("password")).sendKeys(ADMIN_PASSWORD);
        driver.findElement(By.cssSelector("form")).submit();
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("mainNavTabs")));
        dismissOnboardingWhenShown();
    }
'''
new_login = '''    private static void login() {
        driver.get(ContainerTestUtils.APP_ORIGIN + "/login");
        wait.until(ExpectedConditions.presenceOfElementLocated(By.name("username")))
                .sendKeys("admin");
        driver.findElement(By.name("password")).sendKeys(ADMIN_PASSWORD);
        driver.findElement(By.cssSelector("form")).submit();

        wait.until(currentDriver -> {
            String currentUrl = currentDriver.getCurrentUrl();
            if (currentUrl.contains("/login?error")) {
                throw new AssertionError(
                        "Configured test administrator was rejected after readiness: "
                                + currentUrl);
            }
            return !currentUrl.endsWith("/login");
        });
        assertThat(driver.getCurrentUrl())
                .as("post-login URL (title: %s)", driver.getTitle())
                .doesNotContain("/login")
                .doesNotContain("/change-password");

        // Authentication may return to a saved request. The navigation contract
        // belongs to the application workbench, so open it explicitly.
        driver.get(ContainerTestUtils.APP_ORIGIN + "/");
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("mainNavTabs")));
        dismissOnboardingWhenShown();
    }
'''
if portfolio.count(old_login) != 1:
    raise SystemExit(f"Expected one portfolio login helper, found {portfolio.count(old_login)}")
portfolio_path.write_text(portfolio.replace(old_login, new_login), encoding="utf-8")

contract_path = Path(
    "taxonomy-app/src/test/java/com/taxonomy/ui/TaxonomyResponsiveNavigationContractTest.java"
)
contract = contract_path.read_text(encoding="utf-8")
marker = '''    private static String between(String source, String startMarker, String endMarker) {
'''
test = '''    @Test
    void browserAuthenticationStartsOnlyAfterApplicationReadiness() throws Exception {
        String containers = repositoryFile(
                "taxonomy-app/src/test/java/com/taxonomy/ContainerTestUtils.java");
        String portfolio = repositoryFile(
                "taxonomy-app/src/test/java/com/taxonomy/PortfolioUiAcceptanceIT.java");

        assertThat(occurrences(
                containers,
                "Wait.forHttp(\\\"/actuator/health/readiness\\\")"))
                .isEqualTo(2);
        assertThat(containers)
                .doesNotContain("Wait.forHttp(\\\"/actuator/health\\\")");
        assertThat(portfolio)
                .contains("Configured test administrator was rejected after readiness")
                .contains("driver.get(ContainerTestUtils.APP_ORIGIN + \\\"/\\\");")
                .contains("doesNotContain(\\\"/change-password\\\")");
    }

    private static int occurrences(String source, String value) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(value, index)) >= 0) {
            count++;
            index += value.length();
        }
        return count;
    }

'''
if contract.count(marker) != 1:
    raise SystemExit(f"Expected one contract insertion marker, found {contract.count(marker)}")
contract_path.write_text(contract.replace(marker, test + marker), encoding="utf-8")

print("Installed readiness-backed browser authentication and regression contract.")
