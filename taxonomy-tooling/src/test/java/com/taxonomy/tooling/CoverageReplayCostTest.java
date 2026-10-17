package com.taxonomy.tooling;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CoverageReplayCostTest {
 @Test void coldNpmDependenciesAreProvisionedEvenWhenRootCacheIsWarm() throws Exception {
  var root=JavascriptJUnitBridge.root();
  var cache=XmlSupport.parse(root.resolve(".mvn/maven-build-cache-config.xml")).getDocumentElement();
  assertTrue(XmlSupport.descendants(cache,"execId").stream().map(XmlSupport::text).anyMatch("install-junit-javascript-driver-dependencies"::equals),"node_modules are not attached cache outputs, so their install must run on cache hits");
  var pom=XmlSupport.parse(root.resolve("pom.xml")).getDocumentElement();
  var npm=XmlSupport.descendants(pom,"execution").stream().filter(p -> XmlSupport.childText(p,"id").equals("install-junit-javascript-driver-dependencies")).findFirst().orElseThrow();
  assertEquals("${taxonomy.frontend.skip}",XmlSupport.childText(XmlSupport.child(npm,"configuration"),"skip"));
  assertFalse(XmlSupport.childText(XmlSupport.child(npm,"configuration"),"skipTests").equals("false"),"Docker -DskipTests packaging must retain frontend npm's skipTests contract");
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
