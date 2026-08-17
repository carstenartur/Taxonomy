#!/usr/bin/env python3
"""Apply the final review follow-up fixes and their regression tests."""

from pathlib import Path


WORKFLOW_POLICY = Path(
    "taxonomy-app/src/test/java/com/taxonomy/build/WorkflowTestAuthorityPolicy.java"
)
WORKFLOW_POLICY_TEST = Path(
    "taxonomy-app/src/test/java/com/taxonomy/build/WorkflowTestAuthorityPolicyTest.java"
)
COVERAGE_POLICY = Path(
    "taxonomy-build/src/test/java/com/taxonomy/build/ReactorCoveragePolicy.java"
)
COVERAGE_POLICY_TEST = Path(
    "taxonomy-build/src/test/java/com/taxonomy/build/ReactorCoveragePolicyTest.java"
)


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def main() -> None:
    replace_once(
        WORKFLOW_POLICY,
        '''    private static final Pattern CANONICAL_INVOCATION = Pattern.compile(
            "(?m)(?:^|[\\\\s;&|])\\\\./mvnw\\\\s+-B\\\\s+verify\\\\s+-Pci(?:\\\\s|$)");
''',
        '''    private static final Pattern CANONICAL_INVOCATION = Pattern.compile(
            "(?m)(?:^|(?:&&|\\\\|\\\\||[;|])\\\\s*)"
                    + "\\\\./mvnw\\\\s+-B\\\\s+verify\\\\s+-Pci(?:\\\\s|$)");
''',
        "canonical invocation boundary",
    )

    workflow_test_marker = '''    private void writeCatalog(Path root, Map<String, String> responsibilities)
'''
    workflow_tests = '''    @Test
    void rejectsCanonicalCommandThatIsOnlyEchoed(@TempDir Path root) throws Exception {
        writeCatalog(root, Map.of(
                "ci-cd.yml", "canonical verification",
                "database-compatibility.yml", "database matrix"));
        writeCanonicalWorkflows(root);
        writeWorkflow(root, "ci-cd.yml", """
                name: CI
                jobs:
                  verify:
                    steps:
                      - run: echo ./mvnw -B verify -Pci
                """);

        WorkflowTestAuthorityPolicy.Inspection inspection = policy.inspect(root);

        assertThat(inspection.errors())
                .anyMatch(error -> error.contains(
                        "canonical Maven command in a run step"));
    }

    @Test
    void acceptsCanonicalCommandAfterShellSeparator(@TempDir Path root) throws Exception {
        writeCatalog(root, Map.of(
                "ci-cd.yml", "canonical verification",
                "database-compatibility.yml", "database matrix"));
        writeCanonicalWorkflows(root);
        writeWorkflow(root, "ci-cd.yml", """
                name: CI
                jobs:
                  verify:
                    steps:
                      - run: echo preparing && ./mvnw -B verify -Pci
                """);

        WorkflowTestAuthorityPolicy.Inspection inspection = policy.inspect(root);

        assertThat(inspection.errors()).isEmpty();
    }

'''
    replace_once(
        WORKFLOW_POLICY_TEST,
        workflow_test_marker,
        workflow_tests + workflow_test_marker,
        "workflow boundary regression tests",
    )

    replace_once(
        COVERAGE_POLICY,
        '''        return xmlPath.normalize().toString().replace('\\\\', '/');
''',
        '''        return absolute.toString().replace('\\\\', '/');
''',
        "absolute coverage evidence fallback",
    )

    coverage_test_old = '''        assertThat(ReactorCoveragePolicy.evidencePath(
                xml, root.resolve("other-root").toString()))
                .isEqualTo(xml.normalize().toString().replace('\\\\', '/'));
'''
    coverage_test_new = '''        assertThat(ReactorCoveragePolicy.evidencePath(
                xml, root.resolve("other-root").toString()))
                .isEqualTo(xml.normalize().toString().replace('\\\\', '/'));

        Path relativeExternalReport = Path.of("target/external/jacoco.xml");
        assertThat(ReactorCoveragePolicy.evidencePath(
                relativeExternalReport, root.resolve("other-root").toString()))
                .isEqualTo(relativeExternalReport.toAbsolutePath().normalize()
                        .toString().replace('\\\\', '/'));
'''
    replace_once(
        COVERAGE_POLICY_TEST,
        coverage_test_old,
        coverage_test_new,
        "relative external coverage evidence regression test",
    )


if __name__ == "__main__":
    main()
