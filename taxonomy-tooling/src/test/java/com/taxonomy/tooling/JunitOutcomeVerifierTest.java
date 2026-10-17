package com.taxonomy.tooling;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class JunitOutcomeVerifierTest {
 @TempDir Path root;
 @Test void rejectsIncorrectCountsAndMisplacedTerminalOutcomes() throws Exception {
  for(String xml:new String[]{"<testsuite tests='1' failures='0' errors='0' skipped='0'><testcase/><testcase/></testsuite>","<testsuite tests='1' failures='0' errors='0' skipped='0'><testcase/><failure/></testsuite>","<testsuite tests='1' failures='0' errors='0' skipped='0'><testcase><rerunFailure/></testcase></testsuite>"}) {
   put(xml); assertEquals(1,verify(),xml);
  }
 }
 @Test void validatesInclusiveNestedCountersWithoutDoubleCountingSkippedOrParameterizedCases() throws Exception {
  put("<testsuites tests='2' failures='0' errors='0' skipped='1'><testsuite tests='2' failures='0' errors='0' skipped='1'><testcase name='parameter[1]'/><testsuite tests='1' failures='0' errors='0' skipped='1'><testcase name='parameter[2]'><skipped/></testcase></testsuite></testsuite></testsuites>");
  assertEquals(0,verify());
 }
 @Test void canonicalBrowserJUnitOutcomesAreRecountedAlongsideReactorReports() throws Exception {
  put("<testsuite tests='1' failures='0' errors='0' skipped='0'><testcase/></testsuite>");
  Path browser=root.resolve("target/ui-verification/junit-browser-reports/TEST-native.xml");Files.createDirectories(browser.getParent());
  Files.writeString(browser,"<testsuite tests='1' failures='0' errors='0' skipped='0'><testcase/><testcase/></testsuite>");
  assertEquals(1,verify());
 }
 @Test void refusesEmptyReactorEvidence() { assertEquals(1,verify()); }
 private void put(String xml) throws Exception {Path file=root.resolve("target/surefire-reports/TEST-fixture.xml");Files.createDirectories(file.getParent());Files.writeString(file,xml);}
 private int verify() {var output=new PrintStream(new ByteArrayOutputStream());return JunitOutcomeVerifier.run(new String[0],root,output,output);}
}
