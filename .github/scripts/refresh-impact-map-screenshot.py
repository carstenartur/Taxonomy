#!/usr/bin/env python3
"""Update ScreenshotGeneratorIT so screenshot 20 documents the new impact-map workbench."""

from pathlib import Path

path = Path("taxonomy-app/src/test/java/com/taxonomy/ScreenshotGeneratorIT.java")
content = path.read_text(encoding="utf-8")
old = '''        // Switch to swimlane view for a more readable static screenshot
        // (the D3 force graph requires interaction to be fully legible)
        js("var btn = document.querySelector('.impact-view-btn[data-mode=\\"swimlane\\"]');" +
           "if (btn) btn.click();");

        // Use full-page screenshot instead of element-only to capture the complete
        // multi-layer architecture result (swimlane groups + summary) — the element
        // screenshot was too narrow and only showed one or two swimlane groups.
        js("document.getElementById('architectureViewPanel').scrollIntoView({behavior:'instant', block:'start'});");
        saveScreenshot("20-architecture-view.png");
'''
new = '''        // The force-directed graph was replaced by a deterministic architecture-layer
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
count = content.count(old)
if count != 1:
    raise SystemExit(f"Expected exactly one legacy screenshot block, found {count}")
path.write_text(content.replace(old, new, 1), encoding="utf-8")
