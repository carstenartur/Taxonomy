package com.taxonomy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureExceptionLedgerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void checkedLedgerMatchesExactlyTheExceptionsUsedByArchUnit() throws Exception {
        Path ledgerPath = findRepositoryRoot().resolve(".github/architecture-exceptions.json");
        JsonNode root = objectMapper.readTree(Files.readString(ledgerPath));

        assertThat(root.path("schemaVersion").asInt()).isEqualTo(1);
        JsonNode exceptions = root.path("exceptions");
        assertThat(exceptions.isArray()).isTrue();

        Set<String> ids = new LinkedHashSet<>();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (JsonNode entry : exceptions) {
            String id = requiredText(entry, "id");
            assertThat(ids.add(id)).as("duplicate exception id %s", id).isTrue();
            requiredText(entry, "kind");
            requiredText(entry, "fromPackage");
            requiredText(entry, "toPackage");
            requiredText(entry, "owner");
            requiredText(entry, "rationale");
            requiredText(entry, "removalCondition");
            LocalDate expiry = LocalDate.parse(requiredText(entry, "expiresOn"));
            assertThat(expiry)
                    .as("architecture exception %s must not be expired", id)
                    .isAfterOrEqualTo(today);
        }

        assertThat(ids)
                .as("ledger entries and hard-coded ArchUnit exceptions must match exactly")
                .containsExactlyInAnyOrderElementsOf(
                        ArchitectureCycleBoundaryTest.DOCUMENTED_EXCEPTION_IDS);
    }

    /** This pre-existing guard remains selected independently of the new module checks. */
    @Test
    void moduleChecksRemainReachableFromTheArchitectureProfile() throws Exception {
        assertModuleChecksSelected(findRepositoryRoot());
    }

    private static void assertModuleChecksSelected(Path root) throws Exception {
        Path checkout = root.toRealPath();
        Path pom = selectorFile(checkout, "pom.xml");
        Path catalogue = selectorFile(checkout, ".mvn/verification-suites.json");
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        String selected;
        org.w3c.dom.Document reactor;
        try (var input = Files.newInputStream(pom)) {
            reactor = factory.newDocumentBuilder().parse(input);
            var nodes = (org.w3c.dom.NodeList) javax.xml.xpath.XPathFactory.newInstance().newXPath().evaluate(
                    "/*[local-name()='project']/*[local-name()='profiles']"
                            + "/*[local-name()='profile'][*[local-name()='id']='architecture-tests']"
                            + "/*[local-name()='properties']/*[local-name()='test']",
                    reactor, javax.xml.xpath.XPathConstants.NODESET);
            assertThat(nodes.getLength()).as("one architecture-tests Maven selector").isEqualTo(1);
            selected = nodes.item(0).getTextContent();
        }
        String recorded = new ObjectMapper().readTree(Files.readString(catalogue))
                .path("profiles").path("architecture-tests").path("test").asText();
        for (String selector : java.util.List.of(selected, recorded)) {
            var entries = java.util.Arrays.stream(selector.split(","))
                    .map(String::strip).filter(value -> !value.isEmpty()).toList();
            assertThat(entries).as("module checks must remain reachable from the architecture profile")
                    .contains("ArchitectureModuleGraphTest", "ArchitectureModuleExtractionTest",
                            "ArchitectureSelectorSynchronizationTest").doesNotHaveDuplicates();
        }
        // This fixed execution must also detect deletion of all downstream gate classes.
        // Parse their declarations without depending on taxonomy-build test bytecode.
        for (String guard : MODULE_GUARDS) assertModuleGuardDeclaration(checkout, guard);
        assertBuildOwnerExecution(checkout, reactor, factory);
    }

    private static void assertBuildOwnerExecution(Path checkout, org.w3c.dom.Document reactor,
            javax.xml.parsers.DocumentBuilderFactory factory) throws Exception {
        assertThat(xmlValues(reactor, "/*[local-name()='project']/*[local-name()='modules']/*[local-name()='module']"))
                .as("build owner reactor membership must be unconditional and unique")
                .containsOnlyOnce("taxonomy-build");
        org.w3c.dom.Document owner;
        try (var input = Files.newInputStream(selectorFile(checkout, "taxonomy-build/pom.xml"))) {
            owner = factory.newDocumentBuilder().parse(input);
        }
        String project = "/*[local-name()='project']";
        assertThat(xmlValues(owner, project + "/*[local-name()='artifactId']"))
                .as("build owner artifact identity").containsExactly("taxonomy-build");
        var packaging = xmlValues(owner, project + "/*[local-name()='packaging']");
        assertThat(packaging.isEmpty() || packaging.equals(java.util.List.of("jar")))
                .as("build owner must have the executable jar lifecycle").isTrue();
        assertBuildOwnerReactorVersions(reactor, owner, project);
        String executions = project + "/*[local-name()='build']/*[local-name()='plugins']/*[local-name()='plugin']"
                + "[*[local-name()='groupId']='org.apache.maven.plugins']"
                + "[*[local-name()='artifactId']='maven-surefire-plugin']"
                + "/*[local-name()='executions']/*[local-name()='execution']";
        for (String guard : MODULE_GUARDS) {
            String execution = executions + "[*[local-name()='id']='required-" + guard + "']";
            assertThat(xmlValues(owner, execution + "/*[local-name()='phase']"))
                    .as("required architecture execution for %s must run at test", guard).containsExactly("test");
            assertThat(xmlValues(owner, execution + "/*[local-name()='goals']/*[local-name()='goal']"))
                    .as("required architecture execution for %s must invoke Surefire", guard).containsExactly("test");
            String configuration = execution + "/*[local-name()='configuration']";
            for (var requirement : java.util.Map.of("test", guard, "failIfNoTests", "true",
                    "failIfNoSpecifiedTests", "true").entrySet()) {
                assertThat(xmlValues(owner, configuration + "/*[local-name()='" + requirement.getKey() + "']"))
                        .as("required architecture execution for %s: %s", guard, requirement.getKey())
                        .containsExactly(requirement.getValue());
            }
        }
    }

    private static void assertBuildOwnerReactorVersions(org.w3c.dom.Document reactor,
            org.w3c.dom.Document owner, String project) throws Exception {
        String reactorVersion = onlyValue(xmlValues(reactor, project + "/*[local-name()='version']"),
                "root reactor version");
        String dependencies = project + "/*[local-name()='dependencies']/*[local-name()='dependency']";
        for (String artifact : java.util.List.of("taxonomy-app", "taxonomy-coverage", "taxonomy-tooling")) {
            String dependency = dependencies + "[*[local-name()='groupId']='com.taxonomy']"
                    + "[*[local-name()='artifactId']='" + artifact + "']";
            String declaredVersion = onlyValue(xmlValues(owner, dependency + "/*[local-name()='version']"),
                    "direct build-owner dependency com.taxonomy:" + artifact + " version");
            String effectiveVersion = "${project.version}".equals(declaredVersion)
                    ? reactorVersion : declaredVersion;
            assertThat(effectiveVersion)
                    .as("direct build-owner dependency com.taxonomy:%s must use the current reactor version", artifact)
                    .isEqualTo(reactorVersion);
        }
    }

    private static String onlyValue(java.util.List<String> values, String description) {
        assertThat(values).as("exactly one %s", description).hasSize(1);
        return values.getFirst();
    }

    private static java.util.List<String> xmlValues(org.w3c.dom.Node root, String expression) throws Exception {
        var nodes = (org.w3c.dom.NodeList) javax.xml.xpath.XPathFactory.newInstance().newXPath()
                .evaluate(expression, root, javax.xml.xpath.XPathConstants.NODESET);
        java.util.List<String> values = new java.util.ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) values.add(nodes.item(i).getTextContent().strip());
        return values;
    }

    private static void assertModuleGuardDeclaration(Path checkout, String guard) throws Exception {
        Path source = moduleGuardSource(checkout, guard);
        assertThat(source).as("selected architecture guard %s in its owning module", guard).isRegularFile();
        assertThat(source.toRealPath().startsWith(checkout))
                .as("selected architecture guard %s must remain inside the checkout", guard).isTrue();
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("JDK compiler for selected architecture guard declarations").isNotNull();
        var diagnostics = new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
        java.util.List<String> declared = new java.util.ArrayList<>();
        try (var files = compiler.getStandardFileManager(diagnostics, null, java.nio.charset.StandardCharsets.UTF_8)) {
            var task = (com.sun.source.util.JavacTask) compiler.getTask(new java.io.StringWriter(), files,
                    diagnostics, java.util.List.of("--release", "21", "-proc:none"), null,
                    files.getJavaFileObjectsFromPaths(java.util.List.of(source)));
            for (var unit : task.parse()) {
                if (unit.getPackageName() == null || !unit.getPackageName().toString().equals("com.taxonomy")) continue;
                for (var declaration : unit.getTypeDecls()) {
                    if (declaration instanceof com.sun.source.tree.ClassTree type
                            && type.getKind() == com.sun.source.tree.Tree.Kind.CLASS) {
                        declared.add(type.getSimpleName().toString());
                    }
                }
            }
        }
        assertThat(diagnostics.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == javax.tools.Diagnostic.Kind.ERROR).toList())
                .as("selected architecture guard %s must parse as Java", guard).isEmpty();
        assertThat(declared).as("selected architecture guard %s must be declared in its owning source", guard)
                .contains(guard);
    }

    private static Path selectorFile(Path checkout, String relative) throws Exception {
        Path file = checkout.resolve(relative).toRealPath();
        if (!file.startsWith(checkout) || !Files.isRegularFile(file)) {
            throw new IllegalStateException("Architecture selector outside checkout: " + relative);
        }
        return file;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"pom", "catalogue", "both"})
    void legacyGuardRejectsMissingModuleChecksInTemporaryPolicy(String changed,
            @org.junit.jupiter.api.io.TempDir Path checkout) throws Exception {
        String complete = "ArchitectureExceptionLedgerTest,ArchitectureModuleGraphTest,"
                + "ArchitectureModuleExtractionTest,ArchitectureSelectorSynchronizationTest";
        String incomplete = "ArchitectureExceptionLedgerTest";
        writeSelectorFixture(checkout, changed.equals("catalogue") ? complete : incomplete,
                changed.equals("pom") ? complete : incomplete);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> assertModuleChecksSelected(checkout))
                .isInstanceOf(AssertionError.class).hasMessageContaining("module checks must remain reachable");
    }

    @Test
    void additionalPhysicalExtractionChecksRemainAllowed(
            @org.junit.jupiter.api.io.TempDir Path checkout) throws Exception {
        String selector = "ArchitectureExceptionLedgerTest,ArchitectureModuleGraphTest,"
                + "ArchitectureModuleExtractionTest,ArchitectureSelectorSynchronizationTest,"
                + "ArchitectureWorkspaceModuleExtractionTest";
        writeSelectorFixture(checkout, selector, selector);
        assertModuleChecksSelected(checkout);
    }

    @Test
    void independentAnchorRejectsStaleBuildOwnerReactorVersion(
            @org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        completeModuleGuardFixture(root);
        Path owner = root.resolve("taxonomy-build/pom.xml");
        Files.writeString(owner, Files.readString(owner).replaceFirst(
                "<version>\\$\\{project.version}</version>", "<version>1.3.9</version>"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> assertModuleChecksSelected(root))
                .isInstanceOf(AssertionError.class).hasMessageContaining("current reactor version");
    }

    private static void writeSelectorFixture(Path root, String pom, String catalogue) throws Exception {
        for (String guard : MODULE_GUARDS) {
            Path source = moduleGuardSource(root, guard);
            Files.createDirectories(source.getParent());
            Files.writeString(source, "package com.taxonomy; class " + guard + " {}\n");
        }
        Files.createDirectories(root.resolve(".mvn"));
        Files.writeString(root.resolve("pom.xml"), "<project xmlns='http://maven.apache.org/POM/4.0.0'>"
                + "<modelVersion>4.0.0</modelVersion><groupId>com.taxonomy</groupId>"
                + "<artifactId>taxonomy</artifactId><version>1.4.0-SNAPSHOT</version>"
                + "<modules><module>taxonomy-build</module></modules>"
                + "<profiles><profile><id>architecture-tests</id><properties><test>" + pom
                + "</test></properties></profile></profiles></project>");
        Files.writeString(root.resolve("taxonomy-build/pom.xml"), ownerExecutionFixture());
        Files.writeString(root.resolve(".mvn/verification-suites.json"), new ObjectMapper().writeValueAsString(
                java.util.Map.of("profiles", java.util.Map.of("architecture-tests", java.util.Map.of("test", catalogue)))));
    }


    private static final java.util.List<String> MODULE_GUARDS = java.util.List.of(
            "ArchitectureModuleGraphTest", "ArchitectureModuleExtractionTest",
            "ArchitectureSelectorSynchronizationTest");

    private static Path moduleGuardSource(Path root, String guard) {
        return root.resolve("taxonomy-build/src/test/java/com/taxonomy/" + guard + ".java");
    }

    private static void completeModuleGuardFixture(Path root) throws Exception {
        String selector = "ArchitectureExceptionLedgerTest," + String.join(",", MODULE_GUARDS);
        writeSelectorFixture(root, selector, selector);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "ArchitectureModuleGraphTest", "ArchitectureModuleExtractionTest",
            "ArchitectureSelectorSynchronizationTest", "all"})
    void moduleAnchorRejectsMissingGuardSources(String missing,
            @org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        completeModuleGuardFixture(root);
        for (String guard : MODULE_GUARDS) {
            if (missing.equals("all") || missing.equals(guard)) Files.delete(moduleGuardSource(root, guard));
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> assertModuleChecksSelected(root))
                .isInstanceOf(AssertionError.class).hasMessageContaining("selected architecture guard");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "renamed", "wrong-package", "comment-only", "nested-only", "invalid"})
    void moduleAnchorRejectsMissingGuardDeclarations(String kind,
            @org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        completeModuleGuardFixture(root);
        String guard = "ArchitectureSelectorSynchronizationTest";
        String text = switch (kind) {
            case "renamed" -> "package com.taxonomy; class Renamed {}";
            case "wrong-package" -> "package wrong; class " + guard + " {}";
            case "comment-only" -> "package com.taxonomy; /* class " + guard + " {} */";
            case "nested-only" -> "package com.taxonomy; class Outer { class " + guard + " {} }";
            default -> "package com.taxonomy; class " + guard + " {";
        };
        Files.writeString(moduleGuardSource(root, guard), text);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> assertModuleChecksSelected(root))
                .isInstanceOf(AssertionError.class).hasMessageContaining("selected architecture guard");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"file", "directory"})
    void moduleAnchorRejectsExternalGuardAliases(String kind,
            @org.junit.jupiter.api.io.TempDir Path root,
            @org.junit.jupiter.api.io.TempDir Path outside) throws Exception {
        completeModuleGuardFixture(root);
        Path source = moduleGuardSource(root, MODULE_GUARDS.getFirst());
        Path original = kind.equals("file") ? source : source.getParent();
        Path target = outside.resolve(original.getFileName());
        Files.move(original, target);
        if (kind.equals("file")) Files.writeString(target, "EXTERNAL_CONTENT_MUST_NOT_BE_PARSED");
        Files.createSymbolicLink(original, target);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> assertModuleChecksSelected(root))
                .isInstanceOf(AssertionError.class).hasMessageContaining("inside the checkout");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"file", "directory"})
    void moduleAnchorAcceptsContainedGuardAliases(String kind,
            @org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        completeModuleGuardFixture(root);
        Path source = moduleGuardSource(root, MODULE_GUARDS.getFirst());
        Path original = kind.equals("file") ? source : source.getParent();
        Path target = root.resolve("contained-alias");
        Files.move(original, target);
        Files.createSymbolicLink(original, target);
        assertModuleChecksSelected(root);
    }


    private static String ownerExecutionFixture() {
        var xml = new StringBuilder("<project><artifactId>taxonomy-build</artifactId><packaging>jar</packaging>"
                + "<dependencies>"
                + "<dependency><groupId>com.taxonomy</groupId><artifactId>taxonomy-app</artifactId>"
                + "<version>${project.version}</version></dependency>"
                + "<dependency><groupId>com.taxonomy</groupId><artifactId>taxonomy-coverage</artifactId>"
                + "<version>${project.version}</version><type>pom</type></dependency>"
                + "<dependency><groupId>com.taxonomy</groupId><artifactId>taxonomy-tooling</artifactId>"
                + "<version>${project.version}</version><scope>test</scope></dependency>"
                + "</dependencies>"
                + "<build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>"
                + "<artifactId>maven-surefire-plugin</artifactId><executions>");
        for (String guard : MODULE_GUARDS) {
            xml.append("<execution><id>required-").append(guard).append("</id><phase>test</phase>")
                    .append("<goals><goal>test</goal></goals><configuration><test>").append(guard)
                    .append("</test><failIfNoTests>true</failIfNoTests>")
                    .append("<failIfNoSpecifiedTests>true</failIfNoSpecifiedTests></configuration></execution>");
        }
        return xml.append("</executions></plugin></plugins></build></project>").toString();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"missing", "profile-only", "duplicate"})
    void independentAnchorRequiresTheBuildOwnerInTheDefaultReactor(String kind,
            @org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        completeModuleGuardFixture(root);
        Path pom = root.resolve("pom.xml");
        String declaration = "<modules><module>taxonomy-build</module></modules>";
        String replacement = switch (kind) {
            case "profile-only" -> "<profiles><profile><id>not-active</id>" + declaration + "</profile></profiles>";
            case "duplicate" -> "<modules><module>taxonomy-build</module><module>taxonomy-build</module></modules>";
            default -> "";
        };
        Files.writeString(pom, Files.readString(pom).replace(declaration, replacement));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> assertModuleChecksSelected(root))
                .isInstanceOf(AssertionError.class).hasMessageContaining("build owner reactor membership");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"pom", "war", "different-artifact"})
    void independentAnchorRequiresExecutableBuildOwnerPackaging(String kind,
            @org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        completeModuleGuardFixture(root);
        Path pom = root.resolve("taxonomy-build/pom.xml");
        String text = Files.readString(pom);
        text = kind.equals("different-artifact")
                ? text.replace("<artifactId>taxonomy-build</artifactId>", "<artifactId>not-the-build-owner</artifactId>")
                : text.replace("<packaging>jar</packaging>", "<packaging>" + kind + "</packaging>");
        Files.writeString(pom, text);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> assertModuleChecksSelected(root))
                .isInstanceOf(AssertionError.class).hasMessageContaining("build owner");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "missing-execution", "wrong-class", "wrong-phase", "wrong-goal", "tolerate-no-tests", "tolerate-no-match"})
    void everyGuardHasItsOwnFailClosedTestExecution(String kind,
            @org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        completeModuleGuardFixture(root);
        Path pom = root.resolve("taxonomy-build/pom.xml");
        String text = Files.readString(pom);
        String replacement = switch (kind) {
            case "missing-execution" -> text.replaceFirst("<execution>.*?</execution>", "");
            case "wrong-class" -> text.replaceFirst("<test>ArchitectureModuleGraphTest</test>", "<test>OtherTest</test>");
            case "wrong-phase" -> text.replaceFirst("<phase>test</phase>", "<phase>none</phase>");
            case "wrong-goal" -> text.replaceFirst("<goal>test</goal>", "<goal>help</goal>");
            case "tolerate-no-tests" -> text.replaceFirst("<failIfNoTests>true</failIfNoTests>", "<failIfNoTests>false</failIfNoTests>");
            default -> text.replaceFirst("<failIfNoSpecifiedTests>true</failIfNoSpecifiedTests>", "<failIfNoSpecifiedTests>false</failIfNoSpecifiedTests>");
        };
        Files.writeString(pom, replacement);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> assertModuleChecksSelected(root))
                .isInstanceOf(AssertionError.class).hasMessageContaining("required architecture execution");
    }

    @Test
    void implicitJarPackagingRemainsAValidBuildOwner(@org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        completeModuleGuardFixture(root);
        Path pom = root.resolve("taxonomy-build/pom.xml");
        Files.writeString(pom, Files.readString(pom).replace("<packaging>jar</packaging>", ""));
        assertModuleChecksSelected(root);
    }

    private static String requiredText(JsonNode entry, String field) {
        String value = entry.path(field).asText();
        assertThat(value)
                .as("ledger field %s must be present and non-blank", field)
                .isNotBlank();
        return value;
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve(".github/architecture-exceptions.json"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate repository root");
    }
}
