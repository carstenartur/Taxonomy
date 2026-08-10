package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DependencyHygienePolicyTest {

    private static final String PDFBOX_VERSION = "3.0.8";

    private final DependencyHygienePolicy policy = new DependencyHygienePolicy();

    @Test
    void alignedPdfboxThreeFamilyPassesAndPublishesEvidence() {
        DependencyHygienePolicy.Evaluation evaluation = policy.evaluate(
                intendedPdfBoxFamily(PDFBOX_VERSION), List.of(), PDFBOX_VERSION);

        assertThat(evaluation.passed()).isTrue();
        assertThat(evaluation.report())
                .contains("org.apache.pdfbox:pdfbox:3.0.8")
                .contains("org.apache.pdfbox:pdfbox-io:3.0.8")
                .contains("org.apache.pdfbox:fontbox:3.0.8")
                .contains("Result: PASS");
    }

    @Test
    void rejectsLegacyAndUnwantedPdfRenderingFamiliesTogether() {
        List<DependencyHygienePolicy.Component> components = new ArrayList<>(
                intendedPdfBoxFamily(PDFBOX_VERSION));
        components.add(component("org.apache.pdfbox", "pdfbox", "2.0.31"));
        components.add(component("org.apache.pdfbox", "xmpbox", "3.0.8"));
        components.add(component(
                "com.vladsch.flexmark", "flexmark-pdf-converter", "0.64.8"));
        components.add(component(
                "com.openhtmltopdf", "openhtmltopdf-pdfbox", "1.0.10"));

        DependencyHygienePolicy.Evaluation evaluation = policy.evaluate(
                components, List.of(), PDFBOX_VERSION);

        assertThat(evaluation.passed()).isFalse();
        assertThat(evaluation.report())
                .contains("BANNED packaged components:")
                .contains("org.apache.pdfbox:pdfbox:2.0.31")
                .contains("org.apache.pdfbox:xmpbox:3.0.8")
                .contains("com.vladsch.flexmark:flexmark-pdf-converter:0.64.8")
                .contains("com.openhtmltopdf:openhtmltopdf-pdfbox:1.0.10")
                .contains("Result: FAIL");
    }

    @Test
    void exactActiveReviewedExceptionSuppressesOnlyItsCoordinate() {
        DependencyHygienePolicy.Component legacy = component(
                "org.apache.pdfbox", "pdfbox", "2.0.31");
        List<DependencyHygienePolicy.Component> components = new ArrayList<>(
                intendedPdfBoxFamily(PDFBOX_VERSION));
        components.add(legacy);
        DependencyHygienePolicy.ReviewedException exception =
                new DependencyHygienePolicy.ReviewedException(
                        legacy,
                        "dependency-owner",
                        "bounded compatibility window",
                        LocalDate.of(2099, 1, 1),
                        "remove the legacy consumer");

        DependencyHygienePolicy.Evaluation evaluation = policy.evaluate(
                components, List.of(exception), PDFBOX_VERSION);

        assertThat(evaluation.passed()).isTrue();
        assertThat(evaluation.report())
                .contains("Active reviewed exceptions: 1")
                .doesNotContain("BANNED packaged components:");
    }

    @Test
    void reportsMissingMisalignedAndUnexpectedVersions() {
        List<DependencyHygienePolicy.Component> components = List.of(
                component("org.apache.pdfbox", "pdfbox", "3.0.7"),
                component("org.apache.pdfbox", "pdfbox-io", "3.0.8"));

        DependencyHygienePolicy.Evaluation evaluation = policy.evaluate(
                components, List.of(), PDFBOX_VERSION);

        assertThat(evaluation.passed()).isFalse();
        assertThat(evaluation.report())
                .contains("Missing intended PDFBox components: fontbox")
                .contains("PDFBox family versions are not aligned: 3.0.7, 3.0.8")
                .contains("PDFBox family does not match expected version 3.0.8")
                .contains("Result: FAIL");
    }

    @Test
    void unknownPdfboxVersionIsRejected() {
        List<DependencyHygienePolicy.Component> components = new ArrayList<>(
                intendedPdfBoxFamily(PDFBOX_VERSION));
        components.add(component("org.apache.pdfbox", "custom", "development"));

        DependencyHygienePolicy.Evaluation evaluation = policy.evaluate(
                components, List.of(), PDFBOX_VERSION);

        assertThat(evaluation.passed()).isFalse();
        assertThat(evaluation.report())
                .contains("org.apache.pdfbox:custom:development");
    }

    @Test
    void requiresExpectedVersion() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.evaluate(
                        intendedPdfBoxFamily(PDFBOX_VERSION), List.of(), " "))
                .withMessageContaining("expected PDFBox version must not be blank");
    }

    @Test
    void writesStableReport(@TempDir Path root) throws Exception {
        Path output = root.resolve("nested/dependency-hygiene-report.txt");
        String report = policy.evaluate(
                intendedPdfBoxFamily(PDFBOX_VERSION), List.of(), PDFBOX_VERSION)
                .report();

        policy.writeReport(output, report);

        assertThat(Files.readString(output)).isEqualTo(report);
    }

    @Test
    void extractsOnlyLeadingNumericMajorVersion() {
        assertThat(DependencyHygienePolicy.versionMajor("3.0.8")).isEqualTo(3);
        assertThat(DependencyHygienePolicy.versionMajor("12-SNAPSHOT")).isEqualTo(12);
        assertThat(DependencyHygienePolicy.versionMajor("v3")).isNull();
        assertThat(DependencyHygienePolicy.versionMajor(null)).isNull();
    }

    private static List<DependencyHygienePolicy.Component> intendedPdfBoxFamily(
            String version) {
        return List.of(
                component("org.apache.pdfbox", "pdfbox", version),
                component("org.apache.pdfbox", "pdfbox-io", version),
                component("org.apache.pdfbox", "fontbox", version));
    }

    private static DependencyHygienePolicy.Component component(
            String group,
            String name,
            String version) {
        return new DependencyHygienePolicy.Component(group, name, version);
    }
}
