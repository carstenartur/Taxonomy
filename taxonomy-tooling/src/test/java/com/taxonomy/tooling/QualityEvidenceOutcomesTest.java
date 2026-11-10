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
        assertEquals(2L, QualitySite.totals(QualitySite.reports(temp)).get("tests"));
    }
    @Test void publicationRevalidatesRawReportsInsteadOfTrustingSummary() throws Exception {
        Files.createDirectories(temp.resolve("tests"));
        Files.writeString(temp.resolve("tests/TEST-example.xml"), "<testsuite tests='1' failures='0' errors='0' skipped='0'><testcase/></testsuite>");
        Files.createDirectories(temp.resolve("coverage"));
        Files.writeString(temp.resolve("coverage/jacoco.xml"), "<report><counter type='INSTRUCTION' missed='0' covered='1'/></report>");
        QualitySite.generate(temp,"abc","unknown","local","now",java.util.Map.of());
        Files.writeString(temp.resolve("tests/TEST-example.xml"), "<testsuite tests='1' failures='0' errors='0' skipped='0'><testcase><failure/></testcase></testsuite>");
        assertThrows(Exception.class, () -> QualitySite.verifyLocal(temp,"abc"));
    }
    private int collect(String xml) throws Exception {
        Files.writeString(temp.resolve("TEST-fixture.xml"), xml);
        try { QualitySite.totals(QualitySite.reports(temp)); return 0; }
        catch (Exception failure) { return 1; }
    }
}
