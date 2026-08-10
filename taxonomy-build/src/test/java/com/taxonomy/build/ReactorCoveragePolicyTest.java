package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ReactorCoveragePolicyTest {

    private final ReactorCoveragePolicy policy = new ReactorCoveragePolicy();

    @Test
    void passesAndPublishesEveryCounterForEveryExpectedGroup(@TempDir Path root)
            throws Exception {
        Path xml = write(root.resolve("jacoco.xml"), reportXml(
                Map.of(
                        "taxonomy-domain", values(90, 10),
                        "taxonomy-dsl", values(85, 15)),
                values(175, 25)));

        ReactorCoveragePolicy.Evaluation evaluation = policy.evaluate(
                xml, policy(0.81, 0.80, 0.64, 0.80, 0.80));

        assertThat(evaluation.passed()).isTrue();
        assertThat(evaluation.text())
                .contains("taxonomy-domain")
                .contains("Aggregate coverage:")
                .contains("Result: PASS");
        ReactorCoveragePolicy.COUNTER_TYPES.forEach(counter ->
                assertThat(evaluation.text()).contains("- " + counter + ":"));
    }

    @Test
    void branchRatchetFailsWhileInstructionCoverageStillPasses(@TempDir Path root)
            throws Exception {
        Map<String, long[]> aggregate = values(90, 10);
        aggregate.put("BRANCH", new long[] {63, 37});
        Path xml = write(root.resolve("jacoco.xml"), reportXml(
                Map.of(
                        "taxonomy-domain", values(90, 10),
                        "taxonomy-dsl", values(90, 10)),
                aggregate));

        ReactorCoveragePolicy.Evaluation evaluation = policy.evaluate(
                xml, policy(0.81, 0.80, 0.64, 0.80, 0.80));

        assertThat(evaluation.passed()).isFalse();
        assertThat(evaluation.text())
                .contains("INSTRUCTION: 90.00%")
                .contains("BRANCH: 63.00%")
                .contains("Counters below minimum: BRANCH")
                .contains("Result: FAIL");
    }

    @Test
    void failsClosedWhenARequiredCounterIsMissing(@TempDir Path root)
            throws Exception {
        Map<String, long[]> incomplete = values(90, 10);
        incomplete.remove("BRANCH");
        Path xml = write(root.resolve("jacoco.xml"), reportXml(
                Map.of(
                        "taxonomy-domain", incomplete,
                        "taxonomy-dsl", values(90, 10)),
                values(90, 10)));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.evaluate(
                        xml, policy(0.81, 0.80, 0.64, 0.80, 0.80)))
                .withMessageContaining("Missing required counters BRANCH");
    }

    @Test
    void failsClosedWhenARequiredCounterHasNoMeasurableTotal(@TempDir Path root)
            throws Exception {
        Map<String, long[]> aggregate = values(90, 10);
        aggregate.put("CLASS", new long[] {0, 0});
        Path xml = write(root.resolve("jacoco.xml"), reportXml(
                Map.of(
                        "taxonomy-domain", values(90, 10),
                        "taxonomy-dsl", values(90, 10)),
                aggregate));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.evaluate(
                        xml, policy(0.81, 0.80, 0.64, 0.80, 0.80)))
                .withMessageContaining("no measurable total: CLASS");
    }

    @Test
    void failsWhenAnExpectedShippedModuleGroupIsMissing(@TempDir Path root)
            throws Exception {
        Path xml = write(root.resolve("jacoco.xml"), reportXml(
                Map.of("taxonomy-domain", values(90, 10)),
                values(90, 10)));

        ReactorCoveragePolicy.Evaluation evaluation = policy.evaluate(
                xml, policy(0.81, 0.80, 0.64, 0.80, 0.80));

        assertThat(evaluation.passed()).isFalse();
        assertThat(evaluation.text())
                .contains("Missing required module groups: Taxonomy DSL");
    }

    @Test
    void rejectsDuplicateGroupNames(@TempDir Path root) throws Exception {
        String counters = countersXml(values(90, 10));
        Path xml = write(root.resolve("jacoco.xml"), """
                <report name="duplicate">
                  <group name="Taxonomy Domain">%s</group>
                  <group name="Taxonomy Domain">%s</group>
                  %s
                </report>
                """.formatted(counters, counters, counters));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.parseReport(
                        xml, ReactorCoveragePolicy.COUNTER_TYPES))
                .withMessageContaining("duplicate group");
    }

    @Test
    void policyRequiresEveryCounterAndAPositiveBranchMinimum(@TempDir Path root)
            throws Exception {
        Path zeroBranch = write(root.resolve("zero-branch.json"), policyJson(
                List.copyOf(ReactorCoveragePolicy.COUNTER_TYPES),
                Map.of(
                        "INSTRUCTION", 0.81,
                        "LINE", 0.80,
                        "BRANCH", 0.0,
                        "METHOD", 0.80,
                        "CLASS", 0.80)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.loadPolicy(zeroBranch))
                .withMessageContaining("BRANCH minimum must be greater");

        List<String> withoutClass = ReactorCoveragePolicy.COUNTER_TYPES.stream()
                .filter(counter -> !"CLASS".equals(counter))
                .toList();
        Path missingClass = write(root.resolve("missing-class.json"), policyJson(
                withoutClass,
                Map.of(
                        "INSTRUCTION", 0.81,
                        "LINE", 0.80,
                        "BRANCH", 0.64,
                        "METHOD", 0.80)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.loadPolicy(missingClass))
                .withMessageContaining("requiredCounters must contain exactly")
                .withMessageContaining("missing CLASS");
    }

    @Test
    void rejectsExternalEntitiesInCoverageXml(@TempDir Path root) throws Exception {
        Path xml = write(root.resolve("jacoco.xml"), """
                <!DOCTYPE report [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                <report name="unsafe">&secret;</report>
                """);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.parseReport(
                        xml, ReactorCoveragePolicy.COUNTER_TYPES))
                .withMessageContaining("Cannot parse JaCoCo report");
    }

    private static ReactorCoveragePolicy.CoveragePolicy policy(
            double instruction,
            double line,
            double branch,
            double method,
            double clazz) {
        return new ReactorCoveragePolicy.CoveragePolicy(
                ReactorCoveragePolicy.COUNTER_TYPES,
                Map.of(
                        "INSTRUCTION", instruction,
                        "LINE", line,
                        "BRANCH", branch,
                        "METHOD", method,
                        "CLASS", clazz),
                List.of("Taxonomy Domain", "Taxonomy DSL"));
    }

    private static Map<String, long[]> values(long covered, long missed) {
        Map<String, long[]> values = new LinkedHashMap<>();
        ReactorCoveragePolicy.COUNTER_TYPES.forEach(
                counter -> values.put(counter, new long[] {covered, missed}));
        return values;
    }

    private static String reportXml(
            Map<String, Map<String, long[]>> groups,
            Map<String, long[]> aggregate) {
        StringBuilder xml = new StringBuilder("<report name=\"Taxonomy Aggregate Coverage\">");
        groups.forEach((name, counters) -> xml.append("<group name=\"")
                .append(name).append("\">")
                .append(countersXml(counters))
                .append("</group>"));
        return xml.append(countersXml(aggregate)).append("</report>").toString();
    }

    private static String countersXml(Map<String, long[]> values) {
        StringBuilder xml = new StringBuilder();
        values.forEach((name, counts) -> xml.append("<counter type=\"")
                .append(name)
                .append("\" missed=\"")
                .append(counts[1])
                .append("\" covered=\"")
                .append(counts[0])
                .append("\"/>")
        );
        return xml.toString();
    }

    private static String policyJson(
            List<String> counters,
            Map<String, Double> minimums) {
        String counterJson = counters.stream()
                .map(counter -> "\"" + counter + "\"")
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        StringBuilder minimumJson = new StringBuilder();
        minimums.forEach((counter, value) -> {
            if (!minimumJson.isEmpty()) {
                minimumJson.append(',');
            }
            minimumJson.append('"').append(counter).append("\":").append(value);
        });
        return """
                {
                  "schemaVersion": 1,
                  "requiredCounters": [%s],
                  "aggregateMinimums": {%s},
                  "expectedGroups": ["taxonomy-domain"]
                }
                """.formatted(counterJson, minimumJson);
    }

    private static Path write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }
}
