package com.taxonomy;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Keep real acceptance coverage while isolating its heap from the ordinary test JVM. */
class CivilianAcceptanceForkContractTest {
    @Test
    void civilianAcceptanceHasOneMandatoryIsolatedOwnerWithoutChangingItsBudget() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var pom = factory.newDocumentBuilder().parse(Path.of("pom.xml").toFile());
        var xpath = XPathFactory.newInstance().newXPath();
        String plugin = "/project/build/plugins/plugin[artifactId='maven-surefire-plugin']";
        Node isolated = (Node) xpath.evaluate(plugin + "/executions/execution[id='civilian-acceptance-isolated']",
                pom, XPathConstants.NODE);
        assertNotNull(isolated, "the complete civilian acceptance needs an isolated execution, not shared ambient heap");
        assertEquals("test", xpath.evaluate("phase", isolated));
        assertEquals("test", xpath.evaluate("goals/goal", isolated));
        assertEquals("CivilianArchitectureAcceptanceTest", xpath.evaluate("configuration/test", isolated));
        assertEquals("1", xpath.evaluate("configuration/forkCount", isolated));
        assertEquals("false", xpath.evaluate("configuration/reuseForks", isolated));
        assertEquals("true", xpath.evaluate("configuration/failIfNoTests", isolated));
        assertEquals("true", xpath.evaluate("configuration/failIfNoSpecifiedTests", isolated));
        assertEquals("1", xpath.evaluate("count(" + plugin
                + "/executions/execution[configuration/test='CivilianArchitectureAcceptanceTest'])", pom));
        assertEquals("**/CivilianArchitectureAcceptanceTest.java", xpath.evaluate(plugin
                + "/executions/execution[id='default-test']/configuration/excludes/exclude", pom));
        for (String setting : new String[] {"argLine", "skip", "skipTests", "excludedGroups", "reportsDirectory"}) {
            assertEquals("0", xpath.evaluate("count(configuration/" + setting + ")", isolated),
                    "isolation must not change memory, coverage or result publication: " + setting);
        }
    }
}
