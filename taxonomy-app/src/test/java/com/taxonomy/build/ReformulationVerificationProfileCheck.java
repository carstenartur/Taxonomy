package com.taxonomy.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import tools.jackson.databind.ObjectMapper;

/** Executable contract for the Maven selector and its discoverable workflow entry. */
final class ReformulationVerificationProfileCheck {
    private static final String PROFILE = "reformulation-usage-tests";
    private static final List<String> REQUIRED = List.of(
            "ReformulationUsageBoundaryTest", "ReformulationUsageTest",
            "ReformulationUsageWorkerTest", "ReformulationLocalRecoveryTest",
            "ReformulationEncodingTest", "ArchitectureContextDependencyRatchetTest",
            "WorkflowTestAuthorityPolicyTest", "PythonSourceRatchetRepositoryTest",
            "ReformulationVerificationProfileTest");

    static void verify(Path root) throws Exception {
        var catalog = new ObjectMapper().readTree(Files.readString(root.resolve(".mvn/verification-suites.json")));
        String catalogTests = catalog.path("profiles").path(PROFILE).path("test").asText();
        require(Arrays.asList(catalogTests.split(",")).equals(REQUIRED),
                "Maven catalogue must retain the six regression suites, both repository guards and their profile contract");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var pom = factory.newDocumentBuilder().parse(root.resolve("pom.xml").toFile());
        var profiles = pom.getElementsByTagName("profile");
        Element selected = null;
        for (int i = 0; i < profiles.getLength(); i++) {
            var profile = (Element) profiles.item(i);
            if (PROFILE.equals(profile.getElementsByTagName("id").item(0).getTextContent())) {
                require(selected == null, "Duplicate Maven profile");
                selected = profile;
            }
        }
        require(selected != null, "Maven must own the reformulation selector");
        require(selected.getElementsByTagName("test").item(0).getTextContent().equals(catalogTests),
                "POM and verification catalogue selectors differ");
        require("false".equals(selected.getElementsByTagName("surefire.failIfNoSpecifiedTests").item(0).getTextContent()),
                "Upstream modules without a selected suite must remain supported");
        String workflow = Files.readString(root.resolve(".github/workflows/reformulation-usage-contract.yml"));
        String runs = WorkflowTestAuthorityPolicy.runBlocks(workflow);
        require(runs.contains("-P" + PROFILE), "Workflow must invoke the named Maven profile");
        require(!runs.contains("-Dtest=") && !runs.contains("-Dit.test="), "Workflow may not own test selectors");
        require(!catalog.path("workflowResponsibilities").path("reformulation-usage-contract.yml").asText().isBlank(),
                "Workflow must be classified in the catalogue");
        require(runs.contains("-pl taxonomy-tooling,taxonomy-analysis -am test"),
                "Keep the existing complete analysis/upstream unit run");
        var locations = selected.getElementsByTagName("reportsDirectory");
        require(locations.getLength() == 1 && locations.item(0).getTextContent().equals(
                        "${project.build.directory}/surefire-reports-reformulation-usage"),
                "Each Maven phase must use a separate report directory");
        int verifySecond = workflow.indexOf("name: Require persistence suites and positive test counts");
        int archive = workflow.indexOf("name: Archive the tested public source tree", verifySecond);
        require(verifySecond >= 0 && archive > verifySecond, "Missing second-phase report verification");
        String secondReports = workflow.substring(verifySecond, archive);
        require(secondReports.contains("/target/surefire-reports-reformulation-usage/TEST-")
                        && !secondReports.contains("/target/surefire-reports/TEST-"),
                "Second-phase verifier must not accept first-phase reports");
        require(workflow.contains("taxonomy-*/target/surefire-reports/TEST-*.xml")
                        && workflow.contains("taxonomy-*/target/surefire-reports-reformulation-usage/TEST-*.xml"),
                "Archive both phases without overwriting their separate evidence");
        require(runs.contains("TEST-com.taxonomy.build.WorkflowTestAuthorityPolicyTest.xml 7")
                        && runs.contains("TEST-com.taxonomy.tooling.PythonSourceRatchetRepositoryTest.xml 4")
                        && runs.contains("TEST-com.taxonomy.build.ReformulationVerificationProfileTest.xml 1"),
                "Require positive executed counts for both repository guards");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        verify(Path.of(args[0]));
        System.out.println("REFORMULATION_MAVEN_PROFILE_OK");
    }
}
