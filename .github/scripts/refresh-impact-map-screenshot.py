#!/usr/bin/env python3
"""Update ScreenshotGeneratorIT so screenshot 20 documents the complete impact map."""

from pathlib import Path

path = Path("taxonomy-app/src/test/java/com/taxonomy/ScreenshotGeneratorIT.java")
content = path.read_text(encoding="utf-8")

legacy = '''        // Switch to swimlane view for a more readable static screenshot
        // (the D3 force graph requires interaction to be fully legible)
        js("var btn = document.querySelector('.impact-view-btn[data-mode=\\"swimlane\\"]');" +
           "if (btn) btn.click();");

        // Use full-page screenshot instead of element-only to capture the complete
        // multi-layer architecture result (swimlane groups + summary) — the element
        // screenshot was too narrow and only showed one or two swimlane groups.
        js("document.getElementById('architectureViewPanel').scrollIntoView({behavior:'instant', block:'start'});");
        saveScreenshot("20-architecture-view.png");
'''

first_workbench_capture = '''        // The force-directed graph was replaced by a deterministic architecture-layer
        // workbench. Wait for its orientation KPIs and readable element cards instead of
        // switching back to the legacy swimlane alternative.
        wait(30).until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector(".impact-map-workbench")));
        wait(30).until(d -> {
            List<WebElement> impactNodes = d.findElements(
                    By.cssSelector(".impact-map-workbench .impact-map-node"));
            List<WebElement> impactKpis = d.findElements(
                    By.cssSelector(".impact-map-workbench .impact-map-kpi"));
            return impactNodes.stream().anyMatch(WebElement::isDisplayed)
                    && impactKpis.size() >= 4;
        });

        WebElement impactMap = driver.findElement(By.cssSelector(".impact-map-workbench"));
        js("window.dispatchEvent(new Event('resize'));" +
           "arguments[0].scrollIntoView({behavior:'instant', block:'start'});", impactMap);
        wait(5).until(d -> impactMap.getSize().getWidth() > 0
                && impactMap.getSize().getHeight() > 0);
        saveElementScreenshot(impactMap, "20-architecture-view.png");
'''

complete_workbench_capture_v1 = '''        // The force-directed graph was replaced by a deterministic architecture-layer
        // workbench. Wait for its orientation KPIs and readable element cards instead of
        // switching back to the legacy swimlane alternative.
        wait(30).until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector(".impact-map-workbench")));
        wait(30).until(d -> {
            List<WebElement> impactNodes = d.findElements(
                    By.cssSelector(".impact-map-workbench .impact-map-node"));
            List<WebElement> impactKpis = d.findElements(
                    By.cssSelector(".impact-map-workbench .impact-map-kpi"));
            return impactNodes.stream().anyMatch(WebElement::isDisplayed)
                    && impactKpis.size() >= 4;
        });

        // The product intentionally opens large results at a readable focal point. For the
        // documentation image, temporarily use the complete browser width and activate Fit so
        // every architecture layer is visible while the cards remain legible.
        WebElement impactMap = driver.findElement(By.cssSelector(".impact-map-workbench"));
        js("arguments[0].style.position='fixed';" +
           "arguments[0].style.inset='0';" +
           "arguments[0].style.zIndex='2000';" +
           "arguments[0].style.width='100vw';" +
           "arguments[0].style.height='100vh';" +
           "arguments[0].style.borderRadius='0';" +
           "arguments[0].style.setProperty('--impact-map-canvas-height','600px');" +
           "document.body.style.overflow='hidden';" +
           "window.dispatchEvent(new Event('resize'));", impactMap);
        wait(5).until(d -> impactMap.getSize().getWidth() >= 1200
                && impactMap.getSize().getHeight() >= 800);

        js("var root=arguments[0];" +
           "var label=(window.TaxonomyI18n && window.TaxonomyI18n.t)" +
           "  ? window.TaxonomyI18n.t('impactmap.fit') : 'Fit';" +
           "var btn=Array.from(root.querySelectorAll('.impact-map-button')).find(function(candidate) {" +
           "  return candidate.getAttribute('aria-label')===label || candidate.title===label;" +
           "});" +
           "if (!btn) throw new Error('Impact-map Fit button not found');" +
           "window.__impactMapFitReady=false;" +
           "btn.click();" +
           "window.setTimeout(function(){window.__impactMapFitReady=true;},450);", impactMap);
        wait(5).until(d -> Boolean.TRUE.equals(
                ((JavascriptExecutor) d).executeScript(
                        "return window.__impactMapFitReady === true;")));
        wait(5).until(d -> Boolean.TRUE.equals(
                ((JavascriptExecutor) d).executeScript(
                        "var root=arguments[0], canvas=root.querySelector('.impact-map-canvas');" +
                        "var headers=Array.from(root.querySelectorAll('.impact-map-layer-header'));" +
                        "if (!canvas || headers.length < 8) return false;" +
                        "var c=canvas.getBoundingClientRect();" +
                        "return headers.every(function(header) {" +
                        "  var r=header.getBoundingClientRect();" +
                        "  return r.left >= c.left - 2 && r.right <= c.right + 2 &&" +
                        "         r.top >= c.top - 2 && r.bottom <= c.bottom + 2;" +
                        "});", impactMap)));

        saveElementScreenshot(impactMap, "20-architecture-view.png");
        js("arguments[0].removeAttribute('style');" +
           "document.body.style.overflow='';" +
           "window.dispatchEvent(new Event('resize'));", impactMap);
'''

complete_workbench_capture = '''        // The force-directed graph was replaced by a deterministic architecture-layer
        // workbench. Wait for its orientation KPIs and readable element cards instead of
        // switching back to the legacy swimlane alternative.
        wait(30).until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector(".impact-map-workbench")));
        wait(30).until(d -> {
            List<WebElement> impactNodes = d.findElements(
                    By.cssSelector(".impact-map-workbench .impact-map-node"));
            List<WebElement> impactKpis = d.findElements(
                    By.cssSelector(".impact-map-workbench .impact-map-kpi"));
            return impactNodes.stream().anyMatch(WebElement::isDisplayed)
                    && impactKpis.size() >= 4;
        });

        // The product intentionally opens large results at a readable focal point. For the
        // documentation image, temporarily use a wider viewport and activate Fit so every
        // architecture layer is visible while the cards remain legible.
        driver.manage().window().setSize(new org.openqa.selenium.Dimension(1800, 1100));
        WebElement impactMap = driver.findElement(By.cssSelector(".impact-map-workbench"));
        js("arguments[0].style.position='fixed';" +
           "arguments[0].style.inset='0';" +
           "arguments[0].style.zIndex='2000';" +
           "arguments[0].style.width='100vw';" +
           "arguments[0].style.height='100vh';" +
           "arguments[0].style.borderRadius='0';" +
           "arguments[0].style.setProperty('--impact-map-canvas-height','650px');" +
           "document.body.style.overflow='hidden';" +
           "window.dispatchEvent(new Event('resize'));", impactMap);
        wait(5).until(d -> impactMap.getSize().getWidth() >= 1700
                && impactMap.getSize().getHeight() >= 900);

        js("var root=arguments[0];" +
           "var label=(window.TaxonomyI18n && window.TaxonomyI18n.t)" +
           "  ? window.TaxonomyI18n.t('impactmap.fit') : 'Fit';" +
           "var btn=Array.from(root.querySelectorAll('.impact-map-button')).find(function(candidate) {" +
           "  return candidate.getAttribute('aria-label')===label || candidate.title===label;" +
           "});" +
           "if (!btn) throw new Error('Impact-map Fit button not found');" +
           "window.__impactMapFitReady=false;" +
           "btn.click();" +
           "window.setTimeout(function(){window.__impactMapFitReady=true;},450);", impactMap);
        wait(5).until(d -> Boolean.TRUE.equals(
                ((JavascriptExecutor) d).executeScript(
                        "return window.__impactMapFitReady === true;")));
        wait(5).until(d -> Boolean.TRUE.equals(
                ((JavascriptExecutor) d).executeScript(
                        "var root=arguments[0], canvas=root.querySelector('.impact-map-canvas');" +
                        "var headers=Array.from(root.querySelectorAll('.impact-map-layer-header'));" +
                        "if (!canvas || headers.length < 8) return false;" +
                        "var c=canvas.getBoundingClientRect();" +
                        "return headers.every(function(header) {" +
                        "  var r=header.getBoundingClientRect();" +
                        "  return r.left >= c.left - 2 && r.right <= c.right + 2 &&" +
                        "         r.top >= c.top - 2 && r.bottom <= c.bottom + 2;" +
                        "});", impactMap)));

        saveElementScreenshot(impactMap, "20-architecture-view.png");
        js("arguments[0].removeAttribute('style');" +
           "document.body.style.overflow='';" +
           "window.dispatchEvent(new Event('resize'));", impactMap);
        driver.manage().window().setSize(new org.openqa.selenium.Dimension(1400, 900));
'''

for candidate in (complete_workbench_capture_v1, first_workbench_capture, legacy):
    if candidate in content:
        path.write_text(content.replace(candidate, complete_workbench_capture, 1), encoding="utf-8")
        break
else:
    if complete_workbench_capture in content:
        print("Complete impact-map capture is already installed.")
    else:
        raise SystemExit("Expected one known architecture screenshot block")
