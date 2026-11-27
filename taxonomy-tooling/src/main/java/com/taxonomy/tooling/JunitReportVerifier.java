package com.taxonomy.tooling;

import org.w3c.dom.Element;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** Verifies actual named JUnit suites, not a successful build with zero matching tests. */
final class JunitReportVerifier {
    private static final String SINCE = "--since-epoch-millis=";
    private static final Pattern SUFFIXED_SUITE = Pattern.compile("([^()]+)\\(([^()]+)\\)");

    private JunitReportVerifier() {}

    static int run(String[] arguments, Path root, PrintStream output, PrintStream error) {
        long notBefore = 0;
        int firstReport = 0;
        if (arguments.length > 0 && arguments[0].startsWith(SINCE)) {
            try {
                notBefore = Long.parseLong(arguments[0].substring(SINCE.length()));
                if (notBefore <= 0) throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalidStart) {
                error.println("Expected a positive Maven session start time in epoch milliseconds");
                return 2;
            }
            firstReport = 1;
        }
        if (arguments.length <= firstReport || (arguments.length - firstReport) % 2 != 0) {
            error.println("Usage: check-junit-reports [--since-epoch-millis=<millis>] <report.xml> <minimum-tests> ...");
            return 2;
        }
        boolean valid = true;
        for (int index = firstReport; index < arguments.length; index += 2) {
            try {
                int minimum = Integer.parseInt(arguments[index + 1]);
                if (minimum < 1) throw new IllegalArgumentException("Minimum tests must be positive");
                Path report = root.resolve(arguments[index]).normalize();
                if (notBefore > 0 && Files.getLastModifiedTime(report).toMillis() < notBefore)
                    throw new IllegalArgumentException("Report predates the current Maven session");
                Element suite = XmlSupport.parse(report).getDocumentElement();
                if (!"testsuite".equals(suite.getLocalName())
                        || !matchesReportName(report.getFileName().toString(), suite.getAttribute("name")))
                    throw new IllegalArgumentException("Expected the exact named JUnit suite");
                long tests = count(suite, "tests");
                long failures = count(suite, "failures");
                long errors = count(suite, "errors");
                long skipped = count(suite, "skipped");
                output.printf("%s: %d tests, %d failures, %d errors, %d skipped%n",
                        suite.getAttribute("name"), tests, failures, errors, skipped);
                if (tests < minimum || failures != 0 || errors != 0 || skipped != 0
                        || XmlSupport.children(suite, "testcase").size() != tests
                        || !XmlSupport.descendants(suite, "failure").isEmpty()
                        || !XmlSupport.descendants(suite, "error").isEmpty()
                        || !XmlSupport.descendants(suite, "skipped").isEmpty())
                    throw new IllegalArgumentException("Suite has missing, unsuccessful or inconsistent test evidence");
            } catch (IOException | IllegalArgumentException failure) {
                error.println(arguments[index] + ": " + failure.getMessage());
                valid = false;
            }
        }
        return valid ? 0 : 1;
    }

    private static boolean matchesReportName(String fileName, String suiteName) {
        if (fileName.equals("TEST-" + suiteName + ".xml")) return true;
        // Surefire writes Suite(suffix) into the XML and Suite-suffix into the filename.
        var suffixed = SUFFIXED_SUITE.matcher(suiteName);
        return suffixed.matches() && fileName.equals("TEST-" + suffixed.group(1) + "-" + suffixed.group(2) + ".xml");
    }

    private static long count(Element suite, String attribute) {
        String value = suite.getAttribute(attribute);
        if (!value.matches("[0-9]+")) throw new IllegalArgumentException("Missing or invalid counter: " + attribute);
        return Long.parseLong(value);
    }
}
