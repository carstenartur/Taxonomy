package com.taxonomy.tooling;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Shared CLI regression checks, also executable without the Maven test launcher. */
final class JunitReportVerificationChecks {
    private JunitReportVerificationChecks() {}

    static void acceptsExecutedSuites(Path root) throws Exception {
        report(root, "Good", "tests=\"2\" failures=\"0\" errors=\"0\" skipped=\"0\"", "<testcase name=\"a\"/><testcase name=\"b\"/>");
        int result = run(root, "TEST-Good.xml", "2");
        check(result == 0, "Valid executed JUnit suite was rejected; exit=" + result);
    }

    static void rejectsMissingOrEmptyEvidence(Path root) throws Exception {
        check(run(root, "TEST-Missing.xml", "1") != 0, "Missing report accepted");
        report(root, "Empty", "tests=\"0\" failures=\"0\" errors=\"0\" skipped=\"0\"", "");
        check(run(root, "TEST-Empty.xml", "1") != 0, "Zero tests accepted");
        check(run(root) != 0, "Empty suite list accepted");
        check(run(root, "TEST-Empty.xml", "0") != 0, "Nonpositive minimum accepted");
        check(run(root, "TEST-Empty.xml") != 0, "Missing minimum accepted");
    }

    static void rejectsFailedSkippedAndUnderExecutedSuites(Path root) throws Exception {
        for (String kind : new String[] {"failure", "error", "skipped"}) {
            report(root, "Broken", "tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\"", "<testcase name=\"a\"><" + kind + "/></testcase>");
            check(run(root, "TEST-Broken.xml", "1") != 0, "Hidden " + kind + " accepted");
        }
        for (String attribute : new String[] {"failures", "errors", "skipped"}) {
            String counters = "tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\"";
            report(root, "BadCount", counters.replace(attribute + "=\"0\"", attribute + "=\"1\""), "<testcase name=\"a\"/>");
            check(run(root, "TEST-BadCount.xml", "1") != 0, "Failure counter ignored");
        }
        report(root, "Short", "tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\"", "<testcase name=\"a\"/>");
        check(run(root, "TEST-Short.xml", "2") != 0, "Insufficient tests accepted");
    }

    static void rejectsMalformedOrInconsistentCounters(Path root) throws Exception {
        for (String tests : new String[] {"-1", "one", "1.0", "999999999999999999999", "2"}) {
            report(root, "Malformed", "tests=\"" + tests + "\" failures=\"0\" errors=\"0\" skipped=\"0\"", "<testcase name=\"a\"/>");
            check(run(root, "TEST-Malformed.xml", "1") != 0, "Malformed/count-only result accepted: " + tests);
        }
        report(root, "Missing", "tests=\"1\"", "<testcase name=\"a\"/>");
        check(run(root, "TEST-Missing.xml", "1") != 0, "Missing counters accepted");
        Files.writeString(root.resolve("TEST-Wrong.xml"), "<testsuites tests=\"5\"/>");
        check(run(root, "TEST-Wrong.xml", "1") != 0, "Aggregate report accepted instead of named suite");
        Files.writeString(root.resolve("TEST-Xml.xml"), "<testsuite");
        check(run(root, "TEST-Xml.xml", "1") != 0, "Malformed XML accepted");
        report(root, "Name", "tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\"", "<testcase name=\"a\"/>");
        Files.move(root.resolve("TEST-Name.xml"), root.resolve("TEST-Other.xml"));
        check(run(root, "TEST-Other.xml", "1") != 0, "Wrong suite identity accepted");
    }

    static void rejectsDoctypeAndChecksAllSuites(Path root) throws Exception {
        Files.writeString(root.resolve("TEST-Entity.xml"), "<!DOCTYPE testsuite [<!ENTITY x SYSTEM 'file:///not-a-readable-test-input'>]><testsuite name=\"Entity\" tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\"><testcase name=\"a\">&x;</testcase></testsuite>");
        check(run(root, "TEST-Entity.xml", "1") != 0, "External entity allowed");
        report(root, "A", "tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\"", "<testcase name=\"a\"/>");
        check(run(root, "TEST-A.xml", "1", "TEST-Absent.xml", "1") != 0, "Only first requested suite checked");
    }

    private static void report(Path root, String name, String attributes, String cases) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("TEST-" + name + ".xml"), "<testsuite name=\"" + name + "\" " + attributes + ">" + cases + "</testsuite>");
    }
    private static int run(Path root, String... pairs) {
        String[] args = new String[pairs.length + 1]; args[0] = "check-junit-reports";
        System.arraycopy(pairs, 0, args, 1, pairs.length);
        var output = new ByteArrayOutputStream();
        return TaxonomyTooling.run(args, root, new PrintStream(output), new PrintStream(output));
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("junit-evidence-checks-");
        acceptsExecutedSuites(root.resolve("positive"));
        rejectsMissingOrEmptyEvidence(root.resolve("missing"));
        rejectsFailedSkippedAndUnderExecutedSuites(root.resolve("failed"));
        rejectsMalformedOrInconsistentCounters(root.resolve("malformed"));
        Files.createDirectories(root.resolve("safe")); rejectsDoctypeAndChecksAllSuites(root.resolve("safe"));
        System.out.println("JUNIT_REPORT_VERIFICATION_OK checks=5");
    }
}
