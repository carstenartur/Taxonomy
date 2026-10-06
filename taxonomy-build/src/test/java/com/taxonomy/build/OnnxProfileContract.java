package com.taxonomy.build;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Offline build contract; this checks test wiring, not model inference. */
final class OnnxProfileContract {
    private static final String PROFILE = "/project/profiles/profile[id='onnx']/build/plugins/plugin";
    private static final String MODEL_PATH = "${maven.multiModuleProjectDirectory}/models/multilingual-minilm";

    private OnnxProfileContract() { }

    static void verifyPom(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Document pom = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        var xpath = XPathFactory.newInstance().newXPath();
        for (String plugin : List.of("maven-surefire-plugin", "maven-failsafe-plugin")) {
            String configuration = PROFILE + "[artifactId='" + plugin + "']/configuration/";
            require(MODEL_PATH.equals(xpath.evaluate(configuration
                    + "environmentVariables/TAXONOMY_EMBEDDING_MODEL_DIR", pom)),
                    plugin + " must receive the pre-provisioned model directory in the onnx profile");
            require("false".equals(xpath.evaluate(configuration
                    + "environmentVariables/TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD", pom)),
                    plugin + " must keep inference-time downloads disabled");
        }
        require("${runOnnxTests}".equals(xpath.evaluate(PROFILE
                + "[artifactId='maven-failsafe-plugin']/configuration/systemPropertyVariables/runOnnxTests", pom)),
                "Failsafe must forward the onnx profile's opt-in into its test JVM");
    }

    static void verifyWorkflow(String workflow) {
        require(workflow.contains("ref: ${{ github.event.pull_request.head.sha || github.sha }}"),
                "Reference measurements must identify the actual PR head (or manual-dispatch SHA)");
        require(workflow.contains("./mvnw -B verify -Ponnx"), "Keep the Maven-owned suite command");
        require(workflow.contains("check-junit-reports"), "Require executed JUnit evidence, not only Maven exit zero");
        for (String report : List.of(
                "surefire-reports/TEST-com.taxonomy.OnnxEmbeddingServiceTest.xml 4",
                "surefire-reports/TEST-com.taxonomy.OnnxRestEndpointTest.xml 5",
                "failsafe-reports/TEST-com.taxonomy.LocalOnnxPipelineIT.xml 9",
                "failsafe-reports/TEST-com.taxonomy.OnnxSeleniumIT.xml 1")) {
            require(workflow.contains(report), "Missing positive test evidence: " + report);
        }
        require(workflow.contains("**/target/surefire-reports/**"), "Preserve failures before Failsafe starts");
        require(workflow.contains("**/target/failsafe-reports/**"), "Preserve integration and reference evidence");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected checkout root");
        Path root = Path.of(args[0]);
        verifyPom(Files.readString(root.resolve("taxonomy-app/pom.xml")));
        verifyWorkflow(Files.readString(root.resolve(".github/workflows/local-onnx-reference.yml")));
        System.out.println("ONNX profile and executed-evidence wiring verified (not an inference test)");
    }
}
