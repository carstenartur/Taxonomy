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

    private static Document parsePom(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    static void verifyPom(String xml) throws Exception {
        Document pom = parsePom(xml);
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
        require(workflow.contains("./mvnw -B verify -Ponnx,ci-onnx-reference"), "Run the Maven-owned reference suite");
        require(workflow.contains("**/target/surefire-reports/**"), "Preserve failures before Failsafe starts");
        require(workflow.contains("**/target/failsafe-reports/**"), "Preserve integration and reference evidence");
    }

    static void verifyEvidencePom(String xml, Path root) throws Exception {
        Document pom = parsePom(xml);
        var xpath = XPathFactory.newInstance().newXPath();
        String check = "/project/profiles/profile[id='ci-onnx-reference']/build/plugins/"
                + "plugin[artifactId='exec-maven-plugin']/executions/"
                + "execution[id='verify-executed-onnx-reports' and phase='verify' and goals/goal='exec']";
        var arguments = xpath.evaluateExpression(check + "/configuration/arguments/argument", pom,
                javax.xml.xpath.XPathNodes.class);
        var values = new java.util.ArrayList<String>();
        arguments.forEach(node -> values.add(node.getTextContent()));
        require(values.size() >= 25 && (values.size() - 5) % 2 == 0,
                "Verify the ten mandatory suites with positive execution counts");
        require(values.subList(0, 5).equals(List.of("-cp", "${maven.multiModuleProjectDirectory}/taxonomy-tooling/target/classes",
                "com.taxonomy.tooling.TaxonomyTooling", "check-junit-reports",
                "--since-epoch-millis=${session.request.startTime.time}")), "Require fresh reports from this Maven invocation");
        var seen = new java.util.HashSet<String>();
        for (int i = 5; i < values.size(); i += 2) {
            String report = values.get(i);
            var parts = report.split("/target/(?:surefire|failsafe)-reports/TEST-");
            require(parts.length == 2 && parts[1].endsWith(".xml") && seen.add(report)
                    && values.get(i + 1).matches("[1-9][0-9]*"), "Invalid required report: " + report);
            String testSource = parts[1].substring(0, parts[1].length() - 4).replace('.', '/') + ".java";
            require(Files.isRegularFile(root.resolve(parts[0]).resolve("src/test/java").resolve(testSource)),
                    "Missing test class for required report: " + report);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected checkout root");
        Path root = Path.of(args[0]);
        verifyPom(Files.readString(root.resolve("taxonomy-app/pom.xml")));
        verifyEvidencePom(Files.readString(root.resolve("taxonomy-build/pom.xml")), root);
        verifyWorkflow(Files.readString(root.resolve(".github/workflows/local-onnx-reference.yml")));
        System.out.println("ONNX profile and executed-evidence wiring verified (not an inference test)");
    }
}
