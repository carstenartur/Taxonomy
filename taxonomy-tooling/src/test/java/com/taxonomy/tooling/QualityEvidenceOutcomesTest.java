package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real generator/publication entry points, with JUnit owning every assertion. */
class QualityEvidenceOutcomesTest {
    @TempDir Path temp;
    @ParameterizedTest @ValueSource(strings={
        "<testsuite tests='1' failures='0' errors='0' skipped='0'><testcase/><testcase/></testsuite>",
        "<testsuite tests='1' failures='0' errors='0' skipped='0'><testcase><failure/></testcase></testsuite>",
        "<testsuite tests='1' failures='0' errors='0' skipped='0'><testcase><error/></testcase></testsuite>",
        "<testsuite tests='1' failures='0' errors='0' skipped='0'><testcase><skipped/></testcase></testsuite>",
        "<testsuite tests='1.0' failures='0' errors='0' skipped='0'><testcase/></testsuite>",
        "<testsuite tests='1' failures='0' errors='0' skipped='0'><testcase><rerunFailure/></testcase></testsuite>",
        "<testsuite tests='1' failures='0' errors='0' skipped='0'><testcase><flakyError/></testcase></testsuite>",
        "<testsuite tests='1' failures='1' errors='1' skipped='0'><testcase><failure/><error/></testcase></testsuite>",
        "<testsuite tests='1' failures='0' errors='0' skipped='0'><testcase/><failure/></testsuite>",
        "<testsuite tests='1' failures='0' errors='0' skipped='0'><testcase><system-out><error/></system-out></testcase></testsuite>",
        "<testsuite tests='1' failures='0' errors='0' skipped='0' flakes='1'><testcase/></testsuite>",
        "<testsuites tests='9' failures='0' errors='0' skipped='0'><testsuite tests='1' failures='0' errors='0' skipped='0'><testcase/></testsuite></testsuites>"
    }) void rejectsInconsistentOrAmbiguousPublishedOutcomes(String xml) throws Exception {
        assertNotEquals(0, collect(xml), "Inconsistent XML accepted: " + xml);
    }
    @Test void nestedSuiteCountersAreValidatedAndNotDoubleCounted() throws Exception {
        assertEquals(0, collect("<testsuites tests='2' failures='0' errors='0' skipped='1'><testsuite tests='2' failures='0' errors='0' skipped='1'><testcase name='parameter[1]'/><testsuite tests='1' failures='0' errors='0' skipped='1'><testcase name='parameter[2]'><skipped/></testcase></testsuite></testsuite></testsuites>"));
        assertTrue(Files.readString(temp.resolve("result.txt")).contains("'tests': 2"));
    }
    @Test void publicationRevalidatesRawReportsInsteadOfTrustingSummary() throws Exception {
        Files.createDirectories(temp.resolve("tests"));
        Files.writeString(temp.resolve("tests/TEST-example.xml"), "<testsuite tests='1' failures='0' errors='0' skipped='0'><testcase/></testsuite>");
        Files.createDirectories(temp.resolve("coverage"));
        Files.writeString(temp.resolve("coverage/jacoco.xml"), "<report><counter type='INSTRUCTION' missed='0' covered='1'/></report>");
        assertEquals(0, run("g.write_outputs(root, 'abc', 'now')"));
        Files.writeString(temp.resolve("tests/TEST-example.xml"), "<testsuite tests='1' failures='0' errors='0' skipped='0'><testcase><failure/></testcase></testsuite>");
        assertNotEquals(0, run("v.verify_local(root, 'abc')"));
    }
    private int collect(String xml) throws Exception {
        Files.writeString(temp.resolve("TEST-fixture.xml"), xml);
        return run("print(g.collect_tests(root))");
    }
    private int run(String expression) throws Exception {
        String loader="import importlib.util\nfrom pathlib import Path\n" +
            "def load(name,path):\n s=importlib.util.spec_from_file_location(name,path); m=importlib.util.module_from_spec(s); s.loader.exec_module(m); return m\n";
        String script=loader + "root=Path(" + quote(temp.toString()) + ")\n" +
            "g=load('generate'," + quote(JavascriptJUnitBridge.root().resolve(".github/scripts/generate-quality-site.py").toString()) + ")\n" +
            "v=load('verify'," + quote(JavascriptJUnitBridge.root().resolve(".github/scripts/verify-quality-publication.py").toString()) + ")\n" + expression;
        var process=new ProcessBuilder("python3","-c",script).redirectErrorStream(true).redirectOutput(temp.resolve("result.txt").toFile()).start();
        return process.waitFor();
    }
    private static String quote(String value) { return "'"+value.replace("\\","\\\\").replace("'","\\'")+"'"; }
}
