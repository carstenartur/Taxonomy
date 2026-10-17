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
