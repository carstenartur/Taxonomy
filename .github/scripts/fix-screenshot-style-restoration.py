#!/usr/bin/env python3
"""Preserve and restore the UI state around the full-screen impact-map capture."""

from pathlib import Path

path = Path("taxonomy-app/src/test/java/com/taxonomy/ScreenshotGeneratorIT.java")
content = path.read_text(encoding="utf-8")
old = r'''        driver.manage().window().setSize(new org.openqa.selenium.Dimension(1800, 1100));
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
new = r'''        WebElement impactMap = driver.findElement(By.cssSelector(".impact-map-workbench"));
        Boolean impactMapHadStyle = (Boolean) ((JavascriptExecutor) driver).executeScript(
                "return arguments[0].hasAttribute('style');", impactMap);
        String previousImpactMapStyle = (String) ((JavascriptExecutor) driver).executeScript(
                "return arguments[0].getAttribute('style');", impactMap);
        String previousBodyOverflow = (String) ((JavascriptExecutor) driver).executeScript(
                "return document.body.style.overflow;" );
        org.openqa.selenium.Dimension previousWindowSize = driver.manage().window().getSize();

        try {
            driver.manage().window().setSize(new org.openqa.selenium.Dimension(1800, 1100));
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
        } finally {
            js("if (arguments[1]) {" +
               "  arguments[0].setAttribute('style', arguments[2] === null ? '' : arguments[2]);" +
               "} else {" +
               "  arguments[0].removeAttribute('style');" +
               "}" +
               "document.body.style.overflow=arguments[3] || '';" +
               "window.dispatchEvent(new Event('resize'));",
                    impactMap, impactMapHadStyle, previousImpactMapStyle, previousBodyOverflow);
            driver.manage().window().setSize(previousWindowSize);
        }
'''
count = content.count(old)
if count != 1:
    raise SystemExit(f"Expected exactly one screenshot style block, found {count}")
path.write_text(content.replace(old, new, 1), encoding="utf-8")
