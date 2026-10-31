package com.taxonomy.tooling;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CoverageReplayCostTest {
 @Test void replayEndsBeforeOrdinaryTestsAndDisablesCompilersInBothInheritanceTrees() throws Exception {
  var root=JavascriptJUnitBridge.root();
  var workflow=java.nio.file.Files.readString(root.resolve(".github/workflows/ci-cd.yml"));
  assertTrue(workflow.contains("./mvnw -B process-test-classes -Pci,ci-app-test-shards-coverage"), "Replay must not enter test/package/integration-test phases");
  for (String path: java.util.List.of("pom.xml", "plugins/taxonomy-mermaid-plugin/pom.xml")) {
   var project=XmlSupport.parse(root.resolve(path)).getDocumentElement();
   var profile=XmlSupport.descendants(project,"profile").stream().filter(e->"ci-app-test-shards-coverage".equals(XmlSupport.childText(e,"id"))).findFirst().orElseThrow();
   for (String id: java.util.List.of("default-compile", "default-testCompile", "default-resources", "default-testResources")) {
    var execution=XmlSupport.descendants(profile,"execution").stream().filter(e->id.equals(XmlSupport.childText(e,"id"))).findFirst().orElseThrow();
    if (id.equals("default-compile") || id.equals("default-testCompile")) {
     assertEquals("true",XmlSupport.childText(XmlSupport.child(execution,"configuration"),id.equals("default-compile")?"skipMain":"skip"),path+" "+id);
    } else assertEquals("none",XmlSupport.childText(execution,"phase"), path+" "+id);
   }
  }
  var build=XmlSupport.parse(root.resolve("taxonomy-build/pom.xml")).getDocumentElement();
  for(String id: java.util.List.of("coverage-policy-only", "verify-complete-app-test-shard-inventory")) {
   var execution=XmlSupport.descendants(build,"execution").stream().filter(e->id.equals(XmlSupport.childText(e,"id"))).findFirst().orElseThrow();
   assertEquals(id.equals("coverage-policy-only")?"process-test-classes":"validate",XmlSupport.childText(execution,"phase"));
  }
 }
 @Test void uiPackagingKeepsFreshDownloadableRuntimeSbomWhileFullVerificationKeepsAggregate() throws Exception {
  var root=JavascriptJUnitBridge.root();
  var project=XmlSupport.parse(root.resolve("pom.xml")).getDocumentElement();
  var ui=XmlSupport.descendants(project,"profile").stream().filter(e->"ci-ui-application-package".equals(XmlSupport.childText(e,"id"))).findFirst().orElseThrow();
  assertEquals("true",XmlSupport.childText(XmlSupport.child(ui,"properties"),"cyclonedx.skip"));
  assertNotEquals("true",XmlSupport.childText(XmlSupport.child(project,"properties"),"cyclonedx.skip"));
  var app=XmlSupport.parse(root.resolve("taxonomy-app/pom.xml")).getDocumentElement();
  var appUi=XmlSupport.descendants(app,"profile").stream().filter(e->"ci-ui-application-package".equals(XmlSupport.childText(e,"id"))).findFirst().orElseThrow();
  var bom=XmlSupport.descendants(appUi,"execution").stream().filter(e->"ui-runtime-sbom".equals(XmlSupport.childText(e,"id"))).findFirst().orElseThrow();
  assertEquals("initialize",XmlSupport.childText(bom,"phase"),"Generate before copy-legal; same-phase plugin ordering copies no BOM on a clean checkout");
  assertEquals("makeBom",XmlSupport.text(XmlSupport.child(XmlSupport.child(bom,"goals"),"goal")));
  var config=XmlSupport.child(bom,"configuration");
  assertEquals("false",XmlSupport.childText(config,"skip"));
  assertEquals("taxonomy-sbom",XmlSupport.childText(config,"outputName"));
  assertEquals("all",XmlSupport.childText(config,"outputFormat"));
  assertEquals("${maven.multiModuleProjectDirectory}/target",XmlSupport.childText(config,"outputDirectory"));
 }
 @Test void coldNpmDependenciesAreProvisionedEvenWhenRootCacheIsWarm() throws Exception {
  var root=JavascriptJUnitBridge.root();
  var cache=XmlSupport.parse(root.resolve(".mvn/maven-build-cache-config.xml")).getDocumentElement();
  assertTrue(XmlSupport.descendants(cache,"execId").stream().map(XmlSupport::text).anyMatch("install-junit-javascript-driver-dependencies"::equals),"node_modules are not attached cache outputs, so their install must run on cache hits");
  var pom=XmlSupport.parse(root.resolve("pom.xml")).getDocumentElement();
  var npm=XmlSupport.descendants(pom,"execution").stream().filter(p -> XmlSupport.childText(p,"id").equals("install-junit-javascript-driver-dependencies")).findFirst().orElseThrow();
  assertEquals("${taxonomy.frontend.skip}",XmlSupport.childText(XmlSupport.child(npm,"configuration"),"skip"));
  assertEquals("test",XmlSupport.childText(npm,"phase"),"frontend 2.0.2 only honors skipTests during test/integration-test, and root test must provision dependencies before module JUnit");
  assertFalse(XmlSupport.childText(XmlSupport.child(npm,"configuration"),"skipTests").equals("false"),"Explicit skipTests=false would bypass Docker's requested test skip");
 }

 @Test void repositoryWideJUnitOwnersCannotReuseAModuleOnlyCacheKey() throws Exception {
  var tooling=XmlSupport.parse(JavascriptJUnitBridge.root().resolve("taxonomy-tooling/pom.xml")).getDocumentElement();
  assertEquals("false",XmlSupport.childText(XmlSupport.child(tooling,"properties"),"maven.build.cache.enabled"),"Off-module JavaScript inputs and native-browser mode must execute their actual JUnit callbacks");
 }
 @Test void coverageReusesVerifiedSbomAndFrontendInsteadOfRepackagingThem() throws Exception {
  var project=XmlSupport.parse(JavascriptJUnitBridge.root().resolve("pom.xml")).getDocumentElement();
  var plugin=XmlSupport.children(XmlSupport.child(XmlSupport.child(project,"build"),"plugins"),"plugin").stream().filter(p -> XmlSupport.childText(p,"artifactId").equals("cyclonedx-maven-plugin")).findFirst().orElseThrow();
  var defaultBom=XmlSupport.children(XmlSupport.child(plugin,"executions"),"execution").stream().filter(e -> XmlSupport.childText(e,"id").equals("default")).findFirst().orElseThrow();
  assertEquals("none",XmlSupport.childText(defaultBom,"phase"),"Root POM packaging must not generate the duplicate Boot aggregate");
  var coverage=XmlSupport.children(XmlSupport.child(project,"profiles"),"profile").stream().filter(p -> XmlSupport.childText(p,"id").equals("ci-app-test-shards-coverage")).findFirst().orElseThrow();
  assertEquals("true",XmlSupport.childText(XmlSupport.child(coverage,"properties"),"cyclonedx.skip"));
  assertEquals("true",XmlSupport.childText(XmlSupport.child(coverage,"properties"),"taxonomy.frontend.skip"));
 }
}
