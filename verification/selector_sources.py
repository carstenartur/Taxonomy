"""Bounded, disposable RED/GREEN verification; uploads blobs, never moves a branch."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request
import xml.etree.ElementTree as ET

BASE = "da653e2333e5ee80524cdb9373b969e8fbafab39"
SYNC = "taxonomy-build/src/test/java/com/taxonomy/ArchitectureSelectorSynchronizationTest.java"
GRAPH = "taxonomy-build/src/test/java/com/taxonomy/ArchitectureModuleGraphTest.java"
EN = "docs/en/MODULE_BOUNDARIES.md"
DE = "docs/de/MODULE_BOUNDARIES.md"
ALLOWED = {SYNC, GRAPH, EN, DE}
root = Path(sys.argv[2]).resolve()
out = Path(os.environ["RUNNER_TEMP"]) / "selector-source-evidence"
out.mkdir(exist_ok=True)

def git(*args):
    return subprocess.check_output(["git", "-C", str(root), *args], text=True).strip()

if git("rev-parse", "HEAD") != BASE:
    raise RuntimeError("Unexpected immutable checkout")

def replace(path, old, new):
    file = root / path
    text = file.read_text()
    if text.count(old) != 1:
        raise RuntimeError(f"Expected exactly one patch anchor in {path}: {old!r}")
    file.write_text(text.replace(old, new, 1))

TESTS = '''    @ParameterizedTest
    @MethodSource("selectedGuards")
    void missingSelectedGuardSourceIsRejected(String guard) throws Exception {
        writeSelectors(EXPECTED, EXPECTED);
        Files.delete(sourcePath(fixture, guard));
        assertThatThrownBy(() -> assertSelectors(fixture))
                .isInstanceOf(AssertionError.class).hasMessageContaining(guard);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ArchitectureCycleBoundaryTest", "ArchitectureModuleGraphTest"})
    void selectedGuardInTheWrongModuleIsRejected(String guard) throws Exception {
        writeSelectors(EXPECTED, EXPECTED);
        Path source = sourcePath(fixture, guard);
        String other = source.startsWith(fixture.resolve("taxonomy-app"))
                ? "taxonomy-build" : "taxonomy-app";
        Path destination = fixture.resolve(other + "/src/test/java/com/taxonomy/" + guard + ".java");
        Files.createDirectories(destination.getParent());
        Files.move(source, destination);
        assertThatThrownBy(() -> assertSelectors(fixture))
                .isInstanceOf(AssertionError.class).hasMessageContaining(guard);
    }

    @Test
    void selectedGuardSourceOutsideTheCheckoutIsRejected() throws Exception {
        writeSelectors(EXPECTED, EXPECTED);
        String guard = "ArchitectureCycleBoundaryTest";
        Path source = sourcePath(fixture, guard);
        Path outside = externalFixture.resolve(guard + ".java");
        Files.move(source, outside);
        Files.createSymbolicLink(source, outside);
        assertThatThrownBy(() -> assertSelectors(fixture))
                .isInstanceOf(AssertionError.class).hasMessageContaining(guard);
    }

    @Test
    void selectedGuardSourceAliasInsideTheCheckoutIsAccepted() throws Exception {
        writeSelectors(EXPECTED, EXPECTED);
        String guard = "ArchitectureCycleBoundaryTest";
        Path source = sourcePath(fixture, guard);
        Path inside = fixture.resolve("source-aliases/" + guard + ".java");
        Files.createDirectories(inside.getParent());
        Files.move(source, inside);
        Files.createSymbolicLink(source, source.getParent().relativize(inside));
        assertThatCode(() -> assertSelectors(fixture)).doesNotThrowAnyException();
    }

    private static Stream<String> selectedGuards() {
        return EXPECTED.stream();
    }

    private static Path sourcePath(Path root, String guard) {
        String module = switch (guard) {
            case "ArchitectureModuleGraphTest", "ArchitectureModuleExtractionTest",
                    "ArchitectureSelectorSynchronizationTest" -> "taxonomy-build";
            default -> "taxonomy-app";
        };
        return root.resolve(module + "/src/test/java/com/taxonomy/" + guard + ".java");
    }

'''

def prepare_red():
    replace(SYNC, "import org.junit.jupiter.params.provider.ValueSource;",
            "import org.junit.jupiter.params.provider.ValueSource;\nimport org.junit.jupiter.params.provider.MethodSource;")
    replace(SYNC, "import java.util.List;", "import java.util.List;\nimport java.util.stream.Stream;")
    replace(SYNC, "    Path fixture;", "    Path fixture;\n\n    @TempDir\n    Path externalFixture;")
    replace(SYNC, "    private void assertRejected(String target, List<String> changed) throws Exception {",
            TESTS + "    private void assertRejected(String target, List<String> changed) throws Exception {")
    replace(SYNC, "    private void writeSelectors(List<String> pom, List<String> catalog) throws Exception {",
            '''    private void writeSelectors(List<String> pom, List<String> catalog) throws Exception {
        for (String guard : EXPECTED) {
            Path source = sourcePath(fixture, guard);
            Files.createDirectories(source.getParent());
            Files.writeString(source, "package com.taxonomy; class " + guard + " {}\\n");
        }''')


def prepare_green():
    replace(SYNC, "    private static void assertSelectors(Path root) throws Exception {",
            "    static void assertSelectors(Path root) throws Exception {")
    replace(SYNC, '''        assertThat(selectors(json)).as("verification catalogue architecture-tests selector")
                .containsExactlyElementsOf(EXPECTED);
''', '''        assertThat(selectors(json)).as("verification catalogue architecture-tests selector")
                .containsExactlyElementsOf(EXPECTED);
        Path checkout = root.toRealPath();
        for (String guard : EXPECTED) {
            Path source = sourcePath(root, guard);
            assertThat(source).as("selected architecture guard %s in its owning module", guard)
                    .isRegularFile();
            assertThat(source.toRealPath().startsWith(checkout))
                    .as("selected architecture guard %s must remain inside the checkout", guard)
                    .isTrue();
        }
''')
    replace(GRAPH, '''        assertModuleGateOwnerDependencies(root.resolve("taxonomy-build/pom.xml"));
''', '''        assertModuleGateOwnerDependencies(root.resolve("taxonomy-build/pom.xml"));
        ArchitectureSelectorSynchronizationTest.assertSelectors(root);
''')
    replace(EN, '''`.mvn/verification-suites.json`. Its eleven selected test classes are synchronized
between the POM and catalog. Full CI verification remains
''', '''`.mvn/verification-suites.json`. Its fourteen selected test classes are synchronized
between the POM and catalog. In addition to the eleven existing guards,
`ArchitectureModuleGraphTest`, `ArchitectureModuleExtractionTest` and
`ArchitectureSelectorSynchronizationTest` enforce the module graph, extraction
readiness and exact selector synchronization. Every selected guard must retain
its source file in its owning reactor module inside the checkout. The build-owner
contract also invokes the synchronization check, so deleting that check cannot
silently disable it. Full CI verification remains
''')
    replace(DE, '''`.mvn/verification-suites.json`. Die elf ausgewählten Testklassen sind zwischen
POM und Katalog synchronisiert. Die vollständige CI-Verifikation bleibt `./mvnw -B verify -Pci`.
''', '''`.mvn/verification-suites.json`. Die vierzehn ausgewählten Testklassen sind zwischen
POM und Katalog synchronisiert. Zusätzlich zu den elf bestehenden Guards prüfen
`ArchitectureModuleGraphTest`, `ArchitectureModuleExtractionTest` und
`ArchitectureSelectorSynchronizationTest` den Modulgraphen, die Extraktionsreife
und die exakte Synchronisierung der Selektoren. Für jeden ausgewählten Guard muss
die Quelldatei im zuständigen Reactor-Modul innerhalb des Checkouts vorhanden sein.
Der Build-Owner-Vertrag ruft die Synchronisierungsprüfung ebenfalls auf, damit
sie nicht durch das Löschen ihrer Testklasse unbemerkt entfällt.
Die vollständige CI-Verifikation bleibt `./mvnw -B verify -Pci`.
''')


def verify(phase):
    command = ["./mvnw", "-B", "-Parchitecture-tests", "-Dsurefire.failIfNoSpecifiedTests=false"]
    if phase == "green":
        command.append("clean")
    command.append("verify")
    print("COMMAND", phase, " ".join(command), flush=True)
    with (out / f"{phase}.log").open("w") as log:
        result = subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT)
    path = root / "taxonomy-build/target/surefire-reports/TEST-com.taxonomy.ArchitectureSelectorSynchronizationTest.xml"
    if not path.is_file():
        print((out / f"{phase}.log").read_text()[-20000:])
        raise RuntimeError("No selector JUnit report; compile/infrastructure failure is not RED")
    report = ET.parse(path).getroot()
    counts = {k: int(report.attrib[k]) for k in ("tests", "failures", "errors", "skipped")}
    print(phase.upper(), "selector cases", json.dumps(counts), flush=True)
    shutil.copyfile(path, out / f"{phase}-selector.xml")
    if phase == "red":
        assert result.returncode != 0, "Published implementation unexpectedly accepted the regression suite"
        assert counts == {"tests": 27, "failures": 17, "errors": 0, "skipped": 0}, counts
        for case in report.findall("testcase"):
            failure = case.find("failure")
            if failure is not None:
                message = failure.attrib.get("message", "") + (failure.text or "")
                assert "Expecting code to raise a throwable" in message, message
                print("REPRODUCED", case.attrib["name"], flush=True)
    else:
        assert result.returncode == 0, (out / f"{phase}.log").read_text()[-20000:]
        assert counts == {"tests": 27, "failures": 0, "errors": 0, "skipped": 0}, counts
        totals = dict.fromkeys(("tests", "failures", "errors", "skipped"), 0)
        suites = []
        for xml in root.glob("*/target/surefire-reports/TEST-*.xml"):
            suite = ET.parse(xml).getroot()
            suites.append(suite.attrib["name"])
            for key in totals:
                totals[key] += int(suite.attrib[key])
        print("GREEN reactor", json.dumps(totals), "classes", len(suites), flush=True)
        assert totals == {"tests": 172, "failures": 0, "errors": 0, "skipped": 0}, totals
        assert len(suites) == 14, suites
        print(git("diff", "--stat"), flush=True)
        subprocess.run(["git", "diff", "--check"], cwd=root, check=True)
        changed = set(git("diff", "--name-only").splitlines())
        assert changed == ALLOWED, changed
        manifest = {"base": BASE, "files": sorted(changed), "tests": totals, "classes": len(suites)}
        (out / "verification.json").write_text(json.dumps(manifest, indent=2) + "\n")
        (out / "change.patch").write_text(git("diff", "--binary") + "\n")
        for graph in root.glob("*/target/architecture-module-graph.txt"):
            shutil.copyfile(graph, out / graph.name)
        (out / "PASSED").write_text(BASE + "\n")


def publish_blobs():
    assert (out / "PASSED").read_text().strip() == BASE
    changed = set(git("diff", "--name-only").splitlines())
    assert changed == ALLOWED, changed
    token = os.environ["GITHUB_TOKEN"]
    entries = []
    for path in sorted(ALLOWED):
        data = json.dumps({"content": (root / path).read_text(), "encoding": "utf-8"}).encode()
        request = urllib.request.Request(
            "https://api.github.com/repos/carstenartur/Taxonomy/git/blobs", data=data,
            headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json",
                     "X-GitHub-Api-Version": "2022-11-28", "Content-Type": "application/json"}, method="POST")
        with urllib.request.urlopen(request, timeout=30) as response:
            sha = json.load(response)["sha"]
        assert sha == git("hash-object", path), path
        entries.append({"path": path, "mode": "100644", "type": "blob", "sha": sha})
    subprocess.run(["git", "add", "--", *sorted(ALLOWED)], cwd=root, check=True)
    manifest = {"base": BASE, "tree": git("write-tree"), "entries": entries}
    print("VERIFIED_BLOBS " + json.dumps(manifest), flush=True)
    (out / "blobs.json").write_text(json.dumps(manifest, indent=2) + "\n")

mode = sys.argv[1]
if mode == "red":
    prepare_red()
    verify("red")
elif mode == "green":
    prepare_green()
    verify("green")
elif mode == "publish":
    publish_blobs()
else:
    raise RuntimeError("Unknown mode")
