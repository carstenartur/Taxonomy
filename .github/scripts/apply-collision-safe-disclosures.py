#!/usr/bin/env python3
"""Guard real-browser disclosure clicks against transient overlays."""

from pathlib import Path

path = Path("taxonomy-app/src/test/java/com/taxonomy/OnnxSeleniumIT.java")
text = path.read_text(encoding="utf-8")
import_marker = "import org.openqa.selenium.By;\n"
new_imports = (
    "import org.openqa.selenium.By;\n"
    "import org.openqa.selenium.ElementClickInterceptedException;\n"
)
if "import org.openqa.selenium.ElementClickInterceptedException;" not in text:
    if text.count(import_marker) != 1:
        raise SystemExit("Expected one Selenium By import")
    text = text.replace(import_marker, new_imports, 1)

old = '''    private void openDetails(By locator) {
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .ignoring(StaleElementReferenceException.class)
                .until(currentDriver -> {
                    WebElement details = currentDriver.findElement(locator);
                    if (details.getAttribute("open") != null) {
                        return true;
                    }
                    WebElement summary = details.findElement(By.xpath("./summary"));
                    ((JavascriptExecutor) currentDriver).executeScript(
                            "arguments[0].scrollIntoView({block:'center', inline:'nearest'});",
                            summary);
                    if (!summary.isDisplayed() || !summary.isEnabled()) {
                        return false;
                    }
                    summary.click();
                    return currentDriver.findElement(locator).getAttribute("open") != null;
                });
    }
'''
new = '''    private void openDetails(By locator) {
        new WebDriverWait(driver, Duration.ofSeconds(10))
                .ignoring(StaleElementReferenceException.class)
                .ignoring(ElementClickInterceptedException.class)
                .until(currentDriver -> {
                    WebElement details = currentDriver.findElement(locator);
                    if (details.getAttribute("open") != null) {
                        return true;
                    }
                    WebElement summary = details.findElement(By.xpath("./summary"));
                    JavascriptExecutor javascript = (JavascriptExecutor) currentDriver;
                    javascript.executeScript(
                            "arguments[0].scrollIntoView({block:'center', inline:'nearest'});",
                            summary);
                    if (!summary.isDisplayed() || !summary.isEnabled()) {
                        return false;
                    }
                    Boolean unobscured = (Boolean) javascript.executeScript(
                            "const target=arguments[0];"
                                    + "const rect=target.getBoundingClientRect();"
                                    + "const x=rect.left+rect.width/2;"
                                    + "const y=rect.top+rect.height/2;"
                                    + "if (x<0||y<0||x>=innerWidth||y>=innerHeight) return false;"
                                    + "const top=document.elementFromPoint(x,y);"
                                    + "return top===target || target.contains(top);",
                            summary);
                    if (!Boolean.TRUE.equals(unobscured)) {
                        return false;
                    }
                    summary.click();
                    return currentDriver.findElement(locator).getAttribute("open") != null;
                });
    }
'''
if text.count(old) != 1:
    raise SystemExit(f"Expected one direct disclosure helper, found {text.count(old)}")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
print("Installed collision-safe native disclosure clicks.")
