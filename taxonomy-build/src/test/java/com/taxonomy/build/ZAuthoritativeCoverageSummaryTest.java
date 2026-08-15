package com.taxonomy.build;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Publishes one reactor-wide JaCoCo summary containing every counter dimension
 * used for release decisions. The aggregate report is produced by the preceding
 * {@code taxonomy-coverage} reactor module, so focused module-only test runs may
 * legitimately skip this post-reactor contract.
 */
class ZAuthoritativeCoverageSummaryTest {

    private static final List<String> REQUIRED_COUNTERS = List.of(
            "INSTRUCTION", "LINE", "BRANCH", "METHOD", "CLASS");

    @Test
    void publishesAllAuthoritativeJacocoCountersFromTheSameAggregateReport()
            throws Exception {
        Path root = findRepositoryRoot();
        Path aggregateReport = root.resolve(
                "taxonomy-coverage/target/site/jacoco-aggregate/jacoco.xml");

        Assumptions.assumeTrue(
                Files.isRegularFile(aggregateReport),
                () -> "Aggregate JaCoCo report is unavailable in this focused "
                        + "module-only run: " + aggregateReport);

        Map<String, Counter> counters = readReportCounters(aggregateReport);
        assertThat(counters.keySet())
                .as("top-level JaCoCo counter dimensions")
                .containsAll(REQUIRED_COUNTERS);
        REQUIRED_COUNTERS.forEach(type -> {
            Counter counter = counters.get(type);
            assertThat(counter.total())
                    .as("%s total", type.toLowerCase(Locale.ROOT))
                    .isPositive();
        });
        assertThat(counters.get("BRANCH").covered())
                .as("covered branches")
                .isPositive();

        Path outputDirectory = root.resolve("target");
        Files.createDirectories(outputDirectory);
        Path jsonSummary = outputDirectory.resolve("coverage-summary.json");
        Files.writeString(
                jsonSummary,
                toJson(counters),
                StandardCharsets.UTF_8);

        String textSummary = toText(counters);
        Files.writeString(
                outputDirectory.resolve("coverage-gate.txt"),
                System.lineSeparator() + textSummary,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        System.out.print(textSummary);

        assertThat(Files.readString(jsonSummary, StandardCharsets.UTF_8))
                .contains("\"branch\"")
                .contains("\"method\"")
                .contains("\"class\"");
    }

    @Test
    void acceptsJacocoDoctypeAndAdditionalCountersWithoutLoadingTheDtd(
            @TempDir Path temporaryDirectory) throws Exception {
        Path report = temporaryDirectory.resolve("jacoco.xml");
        Files.writeString(
                report,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "missing-report.dtd">
                <report name="synthetic">
                  <package name="nested">
                    <counter type="BRANCH" missed="999" covered="1"/>
                  </package>
                  <counter type="INSTRUCTION" missed="1" covered="9"/>
                  <counter type="BRANCH" missed="6" covered="14"/>
                  <counter type="LINE" missed="2" covered="8"/>
                  <counter type="COMPLEXITY" missed="3" covered="7"/>
                  <counter type="METHOD" missed="4" covered="6"/>
                  <counter type="CLASS" missed="5" covered="5"/>
                </report>
                """,
                StandardCharsets.UTF_8);

        Map<String, Counter> counters = readReportCounters(report);

        assertThat(counters.keySet())
                .containsAll(REQUIRED_COUNTERS)
                .contains("COMPLEXITY");
        assertThat(counters.get("BRANCH"))
                .isEqualTo(new Counter(6, 14));
        assertThat(toJson(counters))
                .contains("\"branch\": {\"missed\": 6, \"covered\": 14, "
                        + "\"total\": 20")
                .doesNotContain("complexity");
    }

    private static Map<String, Counter> readReportCounters(Path report)
            throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl", false);
        factory.setFeature(
                "http://apache.org/xml/features/nonvalidating/load-external-dtd",
                false);
        factory.setFeature(
                "http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature(
                "http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

        Document document = factory.newDocumentBuilder().parse(report.toFile());
        Element root = document.getDocumentElement();
        assertThat(root.getTagName()).isEqualTo("report");

        Map<String, Counter> counters = new LinkedHashMap<>();
        NodeList children = root.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (!(child instanceof Element element)
                    || !"counter".equals(element.getTagName())) {
                continue;
            }
            String type = element.getAttribute("type");
            long missed = parseNonNegativeLong(element, "missed");
            long covered = parseNonNegativeLong(element, "covered");
            Counter previous = counters.put(type, new Counter(missed, covered));
            assertThat(previous)
                    .as("duplicate top-level JaCoCo counter %s", type)
                    .isNull();
        }
        return counters;
    }

    private static long parseNonNegativeLong(Element element, String attribute) {
        String value = element.getAttribute(attribute);
        long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    "Invalid JaCoCo " + attribute + " value '" + value
                            + "' for " + element.getAttribute("type"),
                    error);
        }
        if (parsed < 0) {
            throw new IllegalArgumentException(
                    "Negative JaCoCo " + attribute + " value " + parsed);
        }
        return parsed;
    }

    private static String toJson(Map<String, Counter> counters) {
        StringBuilder json = new StringBuilder();
        json.append("{\n")
                .append("  \"schemaVersion\": 1,\n")
                .append("  \"source\": \"taxonomy-coverage/target/site/"
                        + "jacoco-aggregate/jacoco.xml\",\n")
                .append("  \"counters\": {\n");
        for (int index = 0; index < REQUIRED_COUNTERS.size(); index++) {
            String type = REQUIRED_COUNTERS.get(index);
            Counter counter = counters.get(type);
            json.append("    \"")
                    .append(type.toLowerCase(Locale.ROOT))
                    .append("\": {\"missed\": ")
                    .append(counter.missed())
                    .append(", \"covered\": ")
                    .append(counter.covered())
                    .append(", \"total\": ")
                    .append(counter.total())
                    .append(", \"ratio\": ")
                    .append(String.format(Locale.ROOT, "%.6f", counter.ratio()))
                    .append('}');
            if (index + 1 < REQUIRED_COUNTERS.size()) {
                json.append(',');
            }
            json.append('\n');
        }
        return json.append("  }\n}\n").toString();
    }

    private static String toText(Map<String, Counter> counters) {
        StringBuilder text = new StringBuilder(
                "Authoritative aggregate JaCoCo coverage summary\n");
        REQUIRED_COUNTERS.forEach(type -> {
            Counter counter = counters.get(type);
            text.append(String.format(
                    Locale.ROOT,
                    "%-11s %7d / %7d covered (%6.2f%%)%n",
                    type.toLowerCase(Locale.ROOT),
                    counter.covered(),
                    counter.total(),
                    counter.ratio() * 100.0));
        });
        return text.toString();
    }

    private static Path findRepositoryRoot() throws IOException {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("taxonomy-build"))
                    && Files.isDirectory(current.resolve("taxonomy-coverage"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IOException("Unable to locate the Taxonomy repository root");
    }

    private record Counter(long missed, long covered) {
        long total() {
            return Math.addExact(missed, covered);
        }

        double ratio() {
            return total() == 0 ? 0.0 : (double) covered / (double) total();
        }
    }
}
