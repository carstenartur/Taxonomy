package com.taxonomy.tooling;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StandaloneUiAuthorityVersionTest {
 @Test void canonicalCiSelectsRealDocumentAndRenderingEvidenceWhileSliceKeepsItsExistingOwner() throws Exception {
  var build=XmlSupport.parse(JavascriptJUnitBridge.root().resolve("taxonomy-build/pom.xml")).getDocumentElement();
  var profiles=XmlSupport.children(XmlSupport.child(build,"profiles"),"profile");
  var ci=profiles.stream().filter(p -> XmlSupport.childText(p,"id").equals("ci")).findFirst().orElseThrow();
  assertEquals("true",XmlSupport.childText(XmlSupport.child(ci,"properties"),"documentTemplateE2E"));
  assertEquals("true",XmlSupport.childText(XmlSupport.child(ci,"properties"),"documentTemplateRenderPreview"));
  var slice=profiles.stream().filter(p -> XmlSupport.childText(p,"id").equals("ci-app-test-shards-core")).findFirst().orElseThrow();
  assertEquals("false",XmlSupport.childText(XmlSupport.child(slice,"properties"),"documentTemplateE2E"));
 }
 @Test void standaloneOwnersDoNotDependOnApplicationReleaseVersion() throws Exception {
  var root=JavascriptJUnitBridge.root();
  var ui=XmlSupport.parse(root.resolve(".github/ui-verification-pom.xml")).getDocumentElement();
  assertNull(XmlSupport.child(ui,"parent"),"Application release/version bump must not break independently invoked UI/image/product owners");
  var reactor=XmlSupport.parse(root.resolve("pom.xml")).getDocumentElement();
  assertEquals(XmlSupport.childText(XmlSupport.child(reactor,"parent"),"version"),XmlSupport.childText(XmlSupport.child(ui,"properties"),"spring-boot.version"));
  assertEquals(XmlSupport.childText(XmlSupport.child(reactor,"properties"),"java.version"),XmlSupport.childText(XmlSupport.child(ui,"properties"),"maven.compiler.release"));
  assertEquals("1",XmlSupport.childText(ui,"version"));
 }
}
