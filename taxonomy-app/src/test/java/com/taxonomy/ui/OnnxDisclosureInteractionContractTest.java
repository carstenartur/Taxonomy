package com.taxonomy.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OnnxDisclosureInteractionContractTest {

    @Test
    void disclosureHelperWaitsForAnUnobscuredNativeClick() throws Exception {
        String source = Files.readString(locateOnnxSeleniumSource());
        String helper = between(source,
                "private void openDetails(By locator) {",
                "private void clickWhenUnobscured(By locator) {");

        assertThat(helper)
                .contains("ignoring(ElementClickInterceptedException.class)")
                .contains("document.elementFromPoint(x,y)")
                .contains("target.contains(top)")
                .contains("summary.click();")
                .doesNotContain("details.open = true")
                .doesNotContain("setAttribute(\"open\"");
        assertThat(helper.indexOf("document.elementFromPoint(x,y)"))
                .isLessThan(helper.indexOf("summary.click();"));
    }

    @Test
    void searchHelperRechecksViewportAndRetriesAnInterceptedNativeClick() throws Exception {
        String source = Files.readString(locateOnnxSeleniumSource());
        String helper = between(source,
                "private void clickWhenUnobscured(By locator) {",
                "private void executeUiSearch(String mode, String query) {");

        assertThat(helper)
                .contains("ignoring(StaleElementReferenceException.class)")
                .contains("ignoring(ElementClickInterceptedException.class)")
                .contains("rect.width<=0||rect.height<=0")
                .contains("x>=innerWidth||y>=innerHeight")
                .contains("document.elementFromPoint(x,y)")
                .contains("element.click();")
                .doesNotContain("arguments[0].click()")
                .doesNotContain("target.click()");
        assertThat(helper.indexOf("document.elementFromPoint(x,y)"))
                .isLessThan(helper.indexOf("element.click();"));
    }

    private static Path locateOnnxSeleniumSource() {
        Path current = Path.of(System.getProperty(
                        "maven.multiModuleProjectDirectory", "."))
                .toAbsolutePath()
                .normalize();
        while (current != null) {
            for (String relative : new String[] {
                    "taxonomy-app/src/test/java/com/taxonomy/OnnxSeleniumIT.java",
                    "src/test/java/com/taxonomy/OnnxSeleniumIT.java"}) {
                Path candidate = current.resolve(relative).normalize();
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
            current = current.getParent();
        }
        throw new IllegalStateException(
                "Cannot locate taxonomy-app OnnxSeleniumIT.java from Maven or IDE working directory");
    }

    private static String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex + start.length());
        assertThat(startIndex).as("start marker").isGreaterThanOrEqualTo(0);
        assertThat(endIndex).as("end marker").isGreaterThan(startIndex);
        return source.substring(startIndex, endIndex);
    }
}
