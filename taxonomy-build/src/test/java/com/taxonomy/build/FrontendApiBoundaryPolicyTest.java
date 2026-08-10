package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class FrontendApiBoundaryPolicyTest {

    private final FrontendApiBoundaryPolicy policy = new FrontendApiBoundaryPolicy();

    @Test
    void countsDirectFetchCallsAndRecognizesTransportOwners() {
        assertThat(FrontendApiBoundaryPolicy.countDirectFetch(
                "fetch('/a'); window.fetch('/b');")).isEqualTo(2);
        assertThat(FrontendApiBoundaryPolicy.isTransportOwner(
                "api/taxonomy-api-client.js")).isTrue();
        assertThat(FrontendApiBoundaryPolicy.isTransportOwner(
                "taxonomy-i18n.js")).isTrue();
        assertThat(FrontendApiBoundaryPolicy.isTransportOwner(
                "shared/taxonomy-search.js")).isFalse();
    }

    @Test
    void scansFixedInventoryLineNumbersTemplateAndUnexpectedModules(
            @TempDir Path root) throws Exception {
        writeJs(root, "api/client.js", "fetch('/api/owned');\n");
        writeJs(root, "taxonomy-i18n.js", "fetch('/api/i18n');\n");
        writeJs(root, "shared/taxonomy-search.js", """
                const one = fetch('/api/one');
                const two = fetch(`/api/two`);
                """);
        writeJs(root, "workspace/new-feature.mjs", """
                const safe = true;
                fetch('/api/new');
                """);
        write(root.resolve(
                "taxonomy-app/src/main/resources/templates/index.html"),
                "<script>fetch('/api/template')</script>\n");

        FrontendApiBoundaryPolicy.CurrentScan scan = policy.scanCurrent(root);

        assertThat(scan.fetchCounts())
                .containsEntry("api/client.js", 1)
                .containsEntry("taxonomy-i18n.js", 1)
                .containsEntry("shared/taxonomy-search.js", 2)
                .containsEntry("workspace/new-feature.mjs", 1);
        assertThat(scan.legacyApiInventory())
                .containsEntry("taxonomy-i18n.js", 1)
                .containsEntry("shared/taxonomy-search.js", 2)
                .containsEntry("templates/index.html", 1);
        assertThat(scan.fixedInventoryViolations())
                .containsExactly(
                        "workspace/new-feature.mjs: direct /api fetch at lines [2]");
        assertThat(scan.reducibleAllowlist())
                .doesNotContain("taxonomy-i18n.js", "shared/taxonomy-search.js")
                .contains("core/taxonomy-analysis.js");
    }

    @Test
    void existingLegacyDebtMayDecrease() {
        FrontendApiBoundaryPolicy.Inspection inspection = policy.evaluate(
                scan(Map.of(
                        "shared/search.js", 2,
                        "api/client.js", 5)),
                Map.of(
                        "shared/search.js", 3,
                        "api/client.js", 1),
                "base");

        assertThat(inspection.passed()).isTrue();
        assertThat(inspection.currentDebt()).isEqualTo(2);
        assertThat(inspection.baselineDebt()).isEqualTo(3);
        assertThat(inspection.report()).contains("Result: PASS");
    }

    @Test
    void rejectsIncreasedNewAndShiftedLegacyDebtTogether() {
        FrontendApiBoundaryPolicy.Inspection inspection = policy.evaluate(
                scan(Map.of(
                        "shared/search.js", 4,
                        "workspace/new-feature.js", 1,
                        "shared/shifted.js", 2)),
                Map.of(
                        "shared/search.js", 3,
                        "shared/shifted.js", 1,
                        "shared/removed.js", 2),
                "base");

        assertThat(inspection.passed()).isFalse();
        assertThat(inspection.failures())
                .anyMatch(failure -> failure.contains("increased from 3 to 4"))
                .anyMatch(failure -> failure.contains("introduces 1 direct fetch()"))
                .anyMatch(failure -> failure.contains("increased from 1 to 2"))
                .anyMatch(failure -> failure.contains("debt increased from 6 to 7"));
        assertThat(inspection.report()).contains("Result: FAIL");
    }

    @Test
    void fixedInventoryViolationsFailEvenWhenGitDebtDoesNotGrow() {
        FrontendApiBoundaryPolicy.CurrentScan scan = new FrontendApiBoundaryPolicy.CurrentScan(
                Map.of("workspace/new.js", 1),
                Map.of(),
                List.of("workspace/new.js: direct /api fetch at lines [4]"),
                Set.of());

        FrontendApiBoundaryPolicy.Inspection inspection = policy.evaluate(
                scan, Map.of("workspace/new.js", 1), "base");

        assertThat(inspection.passed()).isFalse();
        assertThat(inspection.failures())
                .contains("workspace/new.js: direct /api fetch at lines [4]");
    }

    @Test
    void inspectLoadsOnlyCurrentPathsFromTheRequestedRevision(@TempDir Path root)
            throws Exception {
        writeJs(root, "shared/existing.js", "fetch('/other');\n");
        writeJs(root, "api/client.js", "fetch('/api/owned');\n");

        FrontendApiBoundaryPolicy.Inspection inspection = policy.inspect(
                root,
                "abc123",
                (revision, repositoryPath) -> {
                    assertThat(revision).isEqualTo("abc123");
                    if (repositoryPath.endsWith("shared/existing.js")) {
                        return Optional.of("fetch('/old'); fetch('/old-two');");
                    }
                    if (repositoryPath.endsWith("api/client.js")) {
                        return Optional.of("");
                    }
                    return Optional.empty();
                });

        assertThat(inspection.passed()).isTrue();
        assertThat(inspection.currentDebt()).isEqualTo(1);
        assertThat(inspection.baselineDebt()).isEqualTo(2);
    }

    @Test
    void rejectsBlankBaselineAndUnreadableUtf8(@TempDir Path root) throws Exception {
        writeJs(root, "api/client.js", "fetch('/api/owned');\n");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.inspect(
                        root, " ", (revision, path) -> Optional.empty()))
                .withMessageContaining("baseline ref must not be blank");

        Path invalid = root.resolve(
                "taxonomy-app/src/main/resources/static/js/shared/invalid.js");
        Files.createDirectories(invalid.getParent());
        Files.write(invalid, new byte[] {(byte) 0xc3, (byte) 0x28});
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.scanCurrent(root))
                .withMessageContaining("cannot read");
    }

    @Test
    void writesStableReport(@TempDir Path root) throws Exception {
        Path output = root.resolve("target/frontend-api-boundary-report.txt");
        String report = "Frontend API boundary\n\nResult: PASS\n";

        policy.writeReport(output, report);

        assertThat(Files.readString(output)).isEqualTo(report);
    }

    private static FrontendApiBoundaryPolicy.CurrentScan scan(
            Map<String, Integer> counts) {
        return new FrontendApiBoundaryPolicy.CurrentScan(
                counts, Map.of(), List.of(), Set.of());
    }

    private static void writeJs(Path root, String relative, String content)
            throws Exception {
        write(root.resolve(
                "taxonomy-app/src/main/resources/static/js").resolve(relative),
                content);
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
