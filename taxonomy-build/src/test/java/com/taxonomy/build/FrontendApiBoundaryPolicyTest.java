package com.taxonomy.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

class FrontendApiBoundaryPolicyTest {

    private final FrontendApiBoundaryPolicy policy = new FrontendApiBoundaryPolicy();

    @Test
    void countsDirectFetchCallsAndRecognizesTransportOwners() {
        assertThat(FrontendApiBoundaryPolicy.countDirectFetch("fetch('/a'); window.fetch('/b');"))
                .isEqualTo(2);
        assertThat(FrontendApiBoundaryPolicy.isTransportOwner("api/taxonomy-api-client.js"))
                .isTrue();
        assertThat(FrontendApiBoundaryPolicy.isTransportOwner("taxonomy-i18n.js")).isTrue();
        assertThat(FrontendApiBoundaryPolicy.isTransportOwner("shared/taxonomy-search.js"))
                .isFalse();
    }

    @Test
    void countsBracketAccessBoundFetchAndLocalTransportAliases() {
        assertThat(
                        FrontendApiBoundaryPolicy.countDirectFetch(
                                """
                                window['fetch']('/api/one');
                                const http = window.fetch.bind(window);
                                http('/api/two');
                                const send = fetch;
                                send('/api/three');
                                """))
                .isEqualTo(5);
    }

    @Test
    void countsParenthesizedDestructuredAndTransitiveAliases() {
        for (String source :
                List.of(
                        "const send=(window.fetch); send('/api/private');",
                        "const {fetch:send}=window; send('/api/private');",
                        "const scope=globalThis; const send=scope['fetch'];"
                                + " send.call(scope,'/api/private');",
                        "let send; send=window['f'+'etch'];"
                                + " send.apply(window,['/api/private']);")) {
            assertThat(FrontendApiBoundaryPolicy.countDirectFetch(source)).as(source).isEqualTo(2);
        }
        assertThat(
                        FrontendApiBoundaryPolicy.countDirectFetch(
                                "const send=fetch; const forward=send; forward('/api/private');"))
                .isEqualTo(3);
    }

    @Test
    void countsExecutableTemplateExpressionsButIgnoresCommentsStringsAndRegex() {
        assertThat(
                        FrontendApiBoundaryPolicy.countDirectFetch(
                                "const output=`state:${fetch('/api/private')}`;"))
                .isEqualTo(1);
        assertThat(
                        FrontendApiBoundaryPolicy.countDirectFetch(
                                """
                                // fetch('/api/comment');
                                /* window.fetch('/api/comment'); */
                                const description="fetch('/api/text')";
                                const pattern=/failed to fetch|fetch\\(/;
                                const template=`fetch('/api/not-code')`;
                                """))
                .isZero();
    }

    @Test
    void aNamedFileIsNotAutomaticallyANativeTransportOwner(@TempDir Path root) throws Exception {
        writeJs(root, "api/new-domain.js", "const send=(window['fetch']); send('/api/private');");
        var scan = policy.scanCurrent(root);
        assertThat(scan.fetchCounts()).containsEntry("api/new-domain.js", 2);
        assertThat(scan.fixedInventoryViolations())
                .anyMatch(value -> value.contains("api/new-domain.js"));
        assertThat(FrontendApiBoundaryPolicy.isTransportOwner("api/new-domain.js")).isFalse();
    }

    @Test
    void browserConstructorTransportCannotBypassTheBoundary() {
        assertThat(FrontendApiBoundaryPolicy.countDirectFetch("new EventSource('/api/events');"))
                .isEqualTo(1);
        assertThat(
                        FrontendApiBoundaryPolicy.countDirectFetch(
                                "const Stream=window.EventSource; new Stream('/api/events');"))
                .isEqualTo(2);
        assertThat(FrontendApiBoundaryPolicy.countDirectFetch("new XMLHttpRequest();"))
                .isEqualTo(1);
    }

    @Test
    void classicJavascriptHtmlCommentEndsAtTheLineBreak(@TempDir Path root) throws Exception {
        String executable = "<!-- compatibility comment\nfetch('/api/private', {method:'POST'});\n";
        assertThat(FrontendApiBoundaryPolicy.countDirectFetch(executable)).isEqualTo(1);
        assertThat(
                        FrontendApiBoundaryPolicy.countDirectFetch(
                                "--> comment fetch('/api/ignored')\nfetch('/api/private');"))
                .isEqualTo(1);
        for (String terminator :
                List.of("\n", "\r", Character.toString(0x2028), Character.toString(0x2029))) {
            for (String prefix : List.of("//", "<!--", "-->")) {
                assertThat(
                                FrontendApiBoundaryPolicy.countDirectFetch(
                                        prefix
                                                + " compatibility comment"
                                                + terminator
                                                + "fetch('/api/private');"))
                        .as(
                                "line comment %s ending in %s",
                                prefix, terminator.codePoints().boxed().toList())
                        .isEqualTo(1);
            }
        }
        writeJs(root, "shared/comment-bypass.js", executable);
        write(
                root.resolve("taxonomy-app/src/main/resources/templates/comment-bypass.html"),
                "<script>" + executable + "//--></script>");
        assertThat(policy.scanCurrent(root).fixedInventoryViolations())
                .containsExactly(
                        "shared/comment-bypass.js: direct /api fetch at lines [2]",
                        "templates/comment-bypass.html: direct /api fetch at lines [2]");
    }

    @Test
    void everyTemplateScansExecutableInlineScriptsWithoutCountingHtmlProse(@TempDir Path root)
            throws Exception {
        writeJs(root, "shared/clean.js", "const safe = true;");
        write(
                root.resolve("taxonomy-app/src/main/resources/templates/tools/standalone.html"),
                "<p>fetch is a browser API</p>\n"
                        + "<script>const send = window['fetch']; send('/api/private');</script>");
        write(root.resolve("taxonomy-app/src/main/resources/templates/tools/quoted-attribute.html"),
                "<script data-description=\"a > b\">const send=window['fetch']; send('/api/private');</script>");
        var scan = policy.scanCurrent(root);
        assertThat(scan.fetchCounts()).containsEntry("templates/tools/quoted-attribute.html", 2);
        assertThat(scan.fetchCounts()).containsEntry("templates/tools/standalone.html", 2);
        assertThat(scan.fixedInventoryViolations())
                .anyMatch(
                        message ->
                                message.contains("templates/tools/standalone.html")
                                        && message.contains("[2]"));
    }

    @Test
    void specializedStreamExceptionNeverAuthorizesOrdinaryFetch(@TempDir Path root)
            throws Exception {
        writeJs(root, "core/taxonomy-scoring.js", "new EventSource('/api/analyze-stream');");
        assertThat(policy.scanCurrent(root).fixedInventoryViolations()).isEmpty();
        writeJs(root, "core/taxonomy-scoring.js", "fetch('/unrelated');");
        assertThat(policy.scanCurrent(root).fixedInventoryViolations())
                .anyMatch(message -> message.contains("EventSource-only"));
    }

    @Test
    void scansFixedInventoryLineNumbersTemplateAndUnexpectedModules(@TempDir Path root)
            throws Exception {
        writeJs(root, "api/taxonomy-api-client.js", "fetch('/api/owned');\n");
        writeJs(root, "shared/clean.js", "const safe = true;\n");
        writeJs(root, "taxonomy-i18n.js", "fetch('/api/i18n');\n");
        writeJs(
                root,
                "shared/taxonomy-search.js",
                """
                const one = fetch('/api/one');
                const two = fetch(`/api/two`);
                """);
        writeJs(
                root,
                "workspace/new-feature.mjs",
                """
                const safe = true;
                fetch('/api/new');
                """);
        write(
                root.resolve("taxonomy-app/src/main/resources/templates/index.html"),
                "<script>fetch('/api/template')</script>\n");

        FrontendApiBoundaryPolicy.CurrentScan scan = policy.scanCurrent(root);

        assertThat(scan.fetchCounts())
                .containsEntry("api/taxonomy-api-client.js", 1)
                .containsEntry("shared/clean.js", 0)
                .containsEntry("taxonomy-i18n.js", 1)
                .containsEntry("shared/taxonomy-search.js", 2)
                .containsEntry("workspace/new-feature.mjs", 1)
                .containsEntry("templates/index.html", 1);
        assertThat(scan.legacyApiInventory())
                .containsEntry("taxonomy-i18n.js", 1)
                .containsEntry("templates/index.html", 1);
        assertThat(scan.fixedInventoryViolations())
                .containsExactly(
                        "shared/taxonomy-search.js: direct /api fetch at lines [1, 2]",
                        "workspace/new-feature.mjs: direct /api fetch at lines [2]",
                        "templates/index.html: direct /api fetch at lines [1]");
        assertThat(scan.reducibleAllowlist()).isEmpty();
    }

    @Test
    void existingLegacyDebtMayDecrease() {
        FrontendApiBoundaryPolicy.Inspection inspection =
                policy.evaluate(
                        scan(
                                Map.of(
                                        "shared/search.js", 2,
                                        "api/taxonomy-api-client.js", 5)),
                        Map.of(
                                "shared/search.js", 3,
                                "api/taxonomy-api-client.js", 1),
                        "base");

        assertThat(inspection.passed()).isTrue();
        assertThat(inspection.currentDebt()).isEqualTo(2);
        assertThat(inspection.baselineDebt()).isEqualTo(3);
        assertThat(inspection.report()).contains("Result: PASS");
    }

    @Test
    void rejectsIncreasedNewAndShiftedLegacyDebtTogether() {
        FrontendApiBoundaryPolicy.Inspection inspection =
                policy.evaluate(
                        scan(
                                Map.of(
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
    void rejectsAdditionalTemplateFetchDebt() {
        FrontendApiBoundaryPolicy.Inspection inspection =
                policy.evaluate(
                        scan(Map.of("templates/index.html", 2)),
                        Map.of("templates/index.html", 1),
                        "base");

        assertThat(inspection.passed()).isFalse();
        assertThat(inspection.failures())
                .contains("templates/index.html: direct fetch() count increased from 1 to 2")
                .contains("legacy direct fetch() debt increased from 1 to 2");
    }

    @Test
    void fixedInventoryViolationsFailEvenWhenGitDebtDoesNotGrow() {
        FrontendApiBoundaryPolicy.CurrentScan scan =
                new FrontendApiBoundaryPolicy.CurrentScan(
                        Map.of("workspace/new.js", 1),
                        Map.of(),
                        List.of("workspace/new.js: direct /api fetch at lines [4]"),
                        Set.of());

        FrontendApiBoundaryPolicy.Inspection inspection =
                policy.evaluate(scan, Map.of("workspace/new.js", 1), "base");

        assertThat(inspection.passed()).isFalse();
        assertThat(inspection.failures())
                .contains("workspace/new.js: direct /api fetch at lines [4]");
    }

    @Test
    void inspectUsesCurrentPathsWhenReaderCannotListTheBaseline(@TempDir Path root)
            throws Exception {
        writeJs(root, "shared/existing.js", "fetch('/other');\n");
        writeJs(root, "api/taxonomy-api-client.js", "fetch('/api/owned');\n");

        FrontendApiBoundaryPolicy.Inspection inspection =
                policy.inspect(
                        root,
                        "abc123",
                        (revision, repositoryPath) -> {
                            assertThat(revision).isEqualTo("abc123");
                            if (repositoryPath.endsWith("shared/existing.js")) {
                                return Optional.of("fetch('/old'); fetch('/old-two');");
                            }
                            if (repositoryPath.endsWith("api/taxonomy-api-client.js")) {
                                throw new AssertionError(
                                        "transport owner baseline must not be read");
                            }
                            return Optional.empty();
                        });

        assertThat(inspection.passed()).isTrue();
        assertThat(inspection.currentDebt()).isEqualTo(1);
        assertThat(inspection.baselineDebt()).isEqualTo(2);
    }

    @Test
    void skipsEveryTransportOwnerBaselineRead(@TempDir Path root) throws Exception {
        writeJs(root, "api/taxonomy-api-client.js", "fetch('/api/owned');\n");
        writeJs(root, "taxonomy-i18n.js", "fetch('/api/i18n');\n");
        writeJs(root, "shared/existing.js", "fetch('/legacy');\n");
        List<String> reads = new ArrayList<>();

        FrontendApiBoundaryPolicy.Inspection inspection =
                policy.inspect(
                        root,
                        "baseline",
                        (revision, repositoryPath) -> {
                            reads.add(repositoryPath);
                            if (repositoryPath.endsWith("shared/existing.js")) {
                                return Optional.of("fetch('/legacy');");
                            }
                            throw new AssertionError(
                                    "Unexpected transport-owner baseline read: " + repositoryPath);
                        });

        assertThat(inspection.passed()).isTrue();
        assertThat(reads)
                .containsExactly("taxonomy-app/src/main/resources/static/js/shared/existing.js");
    }

    @Test
    void retainsBaselineDebtWhenCurrentFetchCountReachedZero(@TempDir Path root) throws Exception {
        writeJs(root, "shared/cleaned.js", "const migrated = true;\n");

        FrontendApiBoundaryPolicy.Inspection inspection =
                policy.inspect(
                        root,
                        "baseline",
                        (revision, repositoryPath) ->
                                repositoryPath.endsWith("shared/cleaned.js")
                                        ? Optional.of("fetch('/old'); fetch('/old-two');")
                                        : Optional.empty());

        assertThat(inspection.passed()).isTrue();
        assertThat(inspection.currentDebt()).isZero();
        assertThat(inspection.baselineDebt()).isEqualTo(2);
        assertThat(inspection.report())
                .contains("0 call(s) in 0 file(s); baseline 2 call(s) in 1 file(s)");
    }

    @Test
    void retainsBaselineDebtFromFilesDeletedByTheChange(@TempDir Path root) throws Exception {
        writeJs(root, "shared/cleaned.js", "const migrated = true;\n");
        FrontendApiBoundaryPolicy.RevisionTextReader reader =
                new FrontendApiBoundaryPolicy.RevisionTextReader() {
                    @Override
                    public Optional<String> read(String revision, String repositoryPath) {
                        assertThat(revision).isEqualTo("baseline");
                        if (repositoryPath.endsWith("shared/cleaned.js")) {
                            return Optional.of("fetch('/old');");
                        }
                        if (repositoryPath.endsWith("shared/deleted.mjs")) {
                            return Optional.of("fetch('/one'); fetch('/two');");
                        }
                        if (repositoryPath.endsWith("templates/index.html")) {
                            return Optional.of("<script>fetch('/template')</script>");
                        }
                        return Optional.empty();
                    }

                    @Override
                    public Set<String> paths(String revision, String repositoryPathPrefix) {
                        assertThat(revision).isEqualTo("baseline");
                        if (repositoryPathPrefix.equals(
                                "taxonomy-app/src/main/resources/static/js")) {
                            return Set.of(
                                    "taxonomy-app/src/main/resources/static/js/shared/cleaned.js",
                                    "taxonomy-app/src/main/resources/static/js/shared/deleted.mjs",
                                    "taxonomy-app/src/main/resources/static/js/shared/ignored.txt");
                        }
                        if (repositoryPathPrefix.equals(
                                "taxonomy-app/src/main/resources/templates")) {
                            return Set.of("taxonomy-app/src/main/resources/templates/index.html");
                        }
                        throw new AssertionError(
                                "Unexpected baseline path prefix: " + repositoryPathPrefix);
                    }
                };

        FrontendApiBoundaryPolicy.Inspection inspection = policy.inspect(root, "baseline", reader);

        assertThat(inspection.passed()).isTrue();
        assertThat(inspection.currentDebt()).isZero();
        assertThat(inspection.baselineDebt()).isEqualTo(4);
        assertThat(inspection.report())
                .contains("0 call(s) in 0 file(s); baseline 4 call(s) in 3 file(s)");
    }

    @Test
    void rejectsBlankBaselineAndUnreadableUtf8(@TempDir Path root) throws Exception {
        writeJs(root, "api/taxonomy-api-client.js", "fetch('/api/owned');\n");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.inspect(root, " ", (revision, path) -> Optional.empty()))
                .withMessageContaining("baseline ref must not be blank");

        Path invalid = root.resolve("taxonomy-app/src/main/resources/static/js/shared/invalid.js");
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

    private static FrontendApiBoundaryPolicy.CurrentScan scan(Map<String, Integer> counts) {
        return new FrontendApiBoundaryPolicy.CurrentScan(counts, Map.of(), List.of(), Set.of());
    }

    private static void writeJs(Path root, String relative, String content) throws Exception {
        write(root.resolve("taxonomy-app/src/main/resources/static/js").resolve(relative), content);
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
