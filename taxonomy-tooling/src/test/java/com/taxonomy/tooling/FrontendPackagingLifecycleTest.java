package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the actual frontend plugin and production execution in a Docker-like source tree. */
class FrontendPackagingLifecycleTest {
    @TempDir Path fixture;

    @Test void skipTestsPackagesWithoutGithubOrNpmInputs() throws Exception {
        prepare(false);
        Result result=maven("package","-DskipTests");
        assertEquals(0,result.code,result.output);
        assertFalse(Files.exists(fixture.resolve(".github")),"Packaging must not require or create browser test inputs");
    }
    @Test void mavenTestSkipPackagesWithoutGithubOrNpmInputs() throws Exception {
        prepare(false);
        Result result=maven("package","-Dmaven.test.skip=true");
        assertEquals(0,result.code,result.output);
        assertFalse(Files.exists(fixture.resolve(".github")),"Skipping Java tests must not require npm inputs");
    }
    @Test void normalReactorInstallsRealNpmDependencyBeforeChildJUnit() throws Exception {
        prepare(true);
        Result result=maven("test");
        assertEquals(0,result.code,result.output);
        assertTrue(Files.isRegularFile(fixture.resolve(".github/node_modules/fixture-package/package.json")),result.output);
        Element suite=XmlSupport.parse(fixture.resolve("child/target/surefire-reports/TEST-FixtureReadyTest.xml")).getDocumentElement();
        assertEquals("1",suite.getAttribute("tests"));
        assertEquals("0",suite.getAttribute("failures"));
        assertEquals("0",suite.getAttribute("errors"));
        assertEquals("0",suite.getAttribute("skipped"));
    }
    @Test void documentBrowserPreparationSkipsJUnitButStillInstallsItsRuntimeBeforeVerify() throws Exception {
        Path root=JavascriptJUnitBridge.root();
        String workflow=Files.readString(root.resolve(".github/workflows/document-template-report-e2e.yml"));
        String preparation=workflow.substring(workflow.indexOf("- name: Install pinned Node and Chromium"),
                workflow.indexOf("- name: Download the template-backed report in Playwright"));
        assertTrue(preparation.contains("-DskipTests"),"Browser preparation must not repeat the final verify's JUnit tests");
        String verification=workflow.substring(workflow.indexOf("- name: Download the template-backed report in Playwright"),
                workflow.indexOf("- name: Upload browser, DOCX, and screenshot evidence"));
        assertTrue(verification.contains("-Pdocument-template-report-e2e"));
        assertFalse(verification.contains("-Dskip"),"The final document lifecycle must execute its tests");

        prepare(true);
        var fixturePom=XmlSupport.parse(fixture.resolve("pom.xml"));
        Element frontend=XmlSupport.descendants(fixturePom.getDocumentElement(),"plugin").stream()
                .filter(e -> "frontend-maven-plugin".equals(XmlSupport.childText(e,"artifactId")))
                .findFirst().orElseThrow();
        Element executions=XmlSupport.child(frontend,"executions");
        var build=XmlSupport.parse(root.resolve("taxonomy-build/pom.xml")).getDocumentElement();
        XmlSupport.descendants(build,"execution").stream()
                .filter(e -> Set.of("install-pinned-browser-test-dependencies","install-pinned-playwright-browsers")
                        .contains(XmlSupport.childText(e,"id")))
                .forEach(e -> executions.appendChild(fixturePom.importNode(e,true)));
        Files.writeString(fixture.resolve("pom.xml"),xml(fixturePom.getDocumentElement()));
        Path github=fixture.resolve(".github");
        Files.writeString(github.resolve("package.json"),Files.readString(github.resolve("package.json"))
                .replace("\"dependencies\":", "\"scripts\":{\"fixture:browser\":\"node install-browser.cjs\"},\"dependencies\":"));
        Files.writeString(github.resolve("install-browser.cjs"),"require('node:fs').writeFileSync('browser-installed',process.version)");
        String[] browserFlags={"-Dtaxonomy.ui.skip=false","-Dtaxonomy.playwright.install.arguments=run fixture:browser"};
        Result prepared=maven("pre-integration-test","-DskipTests",browserFlags[0],browserFlags[1]);
        assertEquals(0,prepared.code,prepared.output);
        assertTrue(Files.isRegularFile(github.resolve("node_modules/fixture-package/package.json")),prepared.output);
        assertTrue(Files.isRegularFile(github.resolve("browser-installed")),prepared.output);
        Path report=fixture.resolve("child/target/surefire-reports/TEST-FixtureReadyTest.xml");
        assertFalse(Files.exists(report),"Preparation must not execute child JUnit tests");
        Result verified=maven("verify",browserFlags);
        assertEquals(0,verified.code,verified.output);
        Element suite=XmlSupport.parse(report).getDocumentElement();
        assertEquals("1",suite.getAttribute("tests"));
        for(String outcome:List.of("failures","errors","skipped")) assertEquals("0",suite.getAttribute(outcome));
    }
    @Test void usesTheNativeWrapperOnBothSupportedOperatingSystems() {
        assertEquals(List.of("cmd.exe","/d","/c","mvnw.cmd"),wrapperCommand(fixture,"Windows 11"));
        assertEquals(List.of(fixture.resolve("mvnw").toString()),wrapperCommand(fixture,"Linux"));
    }
    private void prepare(boolean npmInputs) throws Exception {
        Path root=JavascriptJUnitBridge.root();
        var project=XmlSupport.parse(root.resolve("pom.xml")).getDocumentElement();
        var execution=XmlSupport.descendants(project,"execution").stream().filter(e -> XmlSupport.childText(e,"id").equals("install-junit-javascript-driver-dependencies")).findFirst().orElseThrow();
        String profiles="";
        for(Element profile:XmlSupport.children(XmlSupport.child(project,"profiles"),"profile")) {
            var property=XmlSupport.child(XmlSupport.child(profile,"activation"),"property");
            if("maven.test.skip".equals(XmlSupport.childText(property,"name"))) profiles+=xml(profile);
        }
        Path managed=Path.of(JavascriptJUnitBridge.nodeExecutable());
        assertTrue(managed.isAbsolute() && Files.isExecutable(managed),"Fixture requires the actual Maven-provisioned Node runtime");
        String plugin="""
            <plugin><groupId>com.github.eirslett</groupId><artifactId>frontend-maven-plugin</artifactId><version>2.0.2</version><inherited>false</inherited>
            <configuration><workingDirectory>${project.basedir}/.github</workingDirectory><installDirectory>%s</installDirectory></configuration><executions>%s</executions></plugin>
            """.formatted(managed.getParent().getParent(),xml(execution));
        Files.writeString(fixture.resolve("pom.xml"),"""
            <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
            <groupId>fixture</groupId><artifactId>frontend-root</artifactId><version>1</version><packaging>pom</packaging>
            %s<properties><taxonomy.frontend.skip>false</taxonomy.frontend.skip><maven.compiler.release>21</maven.compiler.release></properties>
            <build><pluginManagement><plugins>
            <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-resources-plugin</artifactId><version>3.5.0</version></plugin>
            <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.15.0</version></plugin>
            <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.6</version></plugin>
            <!-- This fixture checks frontend/Surefire ordering, not archive packaging.
                 Keep its offline child independent of archive-plugin caches. -->
            <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-jar-plugin</artifactId><version>3.5.1</version>
            <executions><execution><id>default-jar</id><phase>none</phase></execution></executions></plugin>
            </plugins></pluginManagement><plugins>%s</plugins></build><profiles>%s</profiles></project>
            """.formatted(npmInputs ? "<modules><module>child</module></modules>" : "",plugin,profiles));
        Files.createDirectories(fixture.resolve(".mvn/wrapper"));
        Files.copy(root.resolve("mvnw"),fixture.resolve("mvnw"));fixture.resolve("mvnw").toFile().setExecutable(true);
        Files.copy(root.resolve("mvnw.cmd"),fixture.resolve("mvnw.cmd"));
        Files.copy(root.resolve(".mvn/wrapper/maven-wrapper.properties"),fixture.resolve(".mvn/wrapper/maven-wrapper.properties"));
        // A root-only POM reaches package without requiring an unexecuted module jar goal.
        if(!npmInputs) return;
        String junitVersion=Test.class.getPackage().getImplementationVersion();
        assertNotNull(junitVersion,"Use the JUnit artifacts already loaded by the outer Surefire session");
        Files.createDirectories(fixture.resolve("child/src/test/java"));
        Files.writeString(fixture.resolve("child/pom.xml"),"""
            <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><parent><groupId>fixture</groupId><artifactId>frontend-root</artifactId><version>1</version></parent><artifactId>child</artifactId>
            <dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>%s</version><scope>test</scope></dependency></dependencies></project>
            """.formatted(junitVersion));
        Files.writeString(fixture.resolve("child/src/test/java/FixtureReadyTest.java"),"""
            import org.junit.jupiter.api.Test;
            import java.nio.file.*;
            import static org.junit.jupiter.api.Assertions.*;
            class FixtureReadyTest { @Test void pinnedDependencyIsReadyBeforeModuleTests() {
                assertTrue(Files.isRegularFile(Path.of("../.github/node_modules/fixture-package/package.json")));
            } }
            """);
        if(npmInputs) {
            Path github=fixture.resolve(".github");Files.createDirectories(github.resolve("fixture-package"));
            Files.writeString(github.resolve("fixture-package/package.json"),"{\"name\":\"fixture-package\",\"version\":\"1.0.0\"}");
            Files.writeString(github.resolve("package.json"),"{\"name\":\"frontend-fixture\",\"version\":\"1.0.0\",\"dependencies\":{\"fixture-package\":\"file:fixture-package\"}}");
            Files.writeString(github.resolve("package-lock.json"),"{\"name\":\"frontend-fixture\",\"version\":\"1.0.0\",\"lockfileVersion\":3,\"packages\":{\"\":{\"name\":\"frontend-fixture\",\"version\":\"1.0.0\",\"dependencies\":{\"fixture-package\":\"file:fixture-package\"}},\"node_modules/fixture-package\":{\"resolved\":\"fixture-package\",\"link\":true},\"fixture-package\":{\"name\":\"fixture-package\",\"version\":\"1.0.0\"}}}");
        }
    }
    private Result maven(String phase,String... flags) throws Exception {
        List<String> command=new ArrayList<>(wrapperCommand(fixture,System.getProperty("os.name")));
        command.addAll(List.of("-B","-o",phase,"-Dmaven.build.cache.enabled=false"));command.addAll(List.of(flags));
        Path log=fixture.resolve("maven.log");var builder=new ProcessBuilder(command).directory(fixture.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("JAVA_HOME",System.getProperty("java.home"));
        var process=builder.start();
        try {assertTrue(process.waitFor(90,TimeUnit.SECONDS),"Fixture Maven timeout: "+log);return new Result(process.exitValue(),Files.readString(log));}
        finally {process.descendants().forEach(ProcessHandle::destroyForcibly);if(process.isAlive()) process.destroyForcibly();}
    }
    private static List<String> wrapperCommand(Path fixture,String osName) {
        return osName.toLowerCase(Locale.ROOT).startsWith("windows")
                ? List.of("cmd.exe","/d","/c","mvnw.cmd") : List.of(fixture.resolve("mvnw").toString());
    }
    private static String xml(Element element) throws Exception {var output=new StringWriter();var transformer=TransformerFactory.newInstance().newTransformer();transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION,"yes");transformer.transform(new DOMSource(element),new StreamResult(output));return output.toString().replace(" xmlns=\"http://maven.apache.org/POM/4.0.0\"", "");}
    private record Result(int code,String output) {}
}
