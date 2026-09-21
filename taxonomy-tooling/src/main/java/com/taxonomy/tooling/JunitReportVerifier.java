package com.taxonomy.tooling;

import org.w3c.dom.Element;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;

/** Verifies actual named JUnit suites, not a successful build with zero matching tests. */
final class JunitReportVerifier {
    private JunitReportVerifier() {}

    static int run(String[] arguments, Path root, PrintStream output, PrintStream error) {
        if (arguments.length == 0 || arguments.length % 2 != 0) {
            error.println("Usage: check-junit-reports <report.xml> <minimum-tests> [<report.xml> <minimum-tests> ...]");
            return 2;
        }
        boolean valid = true;
        for (int index = 0; index < arguments.length; index += 2) {
            try {
                int minimum = Integer.parseInt(arguments[index + 1]);
                if (minimum < 1) throw new IllegalArgumentException("Minimum tests must be positive");
                Path report = root.resolve(arguments[index]).normalize();
                Element suite = XmlSupport.parse(report).getDocumentElement();
                if (!"testsuite".equals(suite.getLocalName())
                        || !report.getFileName().toString().equals("TEST-" + suite.getAttribute("name") + ".xml"))
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

    private static long count(Element suite, String attribute) {
        String value = suite.getAttribute(attribute);
        if (!value.matches("[0-9]+")) throw new IllegalArgumentException("Missing or invalid counter: " + attribute);
        return Long.parseLong(value);
    }
}
