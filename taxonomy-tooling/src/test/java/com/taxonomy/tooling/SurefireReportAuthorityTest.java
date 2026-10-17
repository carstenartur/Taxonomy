package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import static org.junit.jupiter.api.Assertions.*;

/** A module default must not hide the inherited lane's original JUnit reports. */
class SurefireReportAuthorityTest {
    @Test void toolingInheritsLaneDirectoryUnlessBrowserDriverExplicitlyOverridesIt() throws Exception {
        var root=JavascriptJUnitBridge.root();
        var tooling=XmlSupport.parse(root.resolve("taxonomy-tooling/pom.xml")).getDocumentElement();
        var plugins=XmlSupport.child(XmlSupport.child(tooling,"build"),"plugins");
        for(Element plugin:XmlSupport.children(plugins,"plugin"))
            if("maven-surefire-plugin".equals(XmlSupport.childText(plugin,"artifactId")))
                assertNull(XmlSupport.child(XmlSupport.child(plugin,"configuration"),"reportsDirectory"),
                        "Unconditional child configuration overrides inherited reformulation lane reports");
        assertEquals("",XmlSupport.childText(XmlSupport.child(tooling,"properties"),"taxonomy.javascript.reports"),
                "Browser override must be an explicit caller property");
        var override=XmlSupport.children(XmlSupport.child(tooling,"profiles"),"profile").stream()
                .filter(p -> "javascript-junit-reports".equals(XmlSupport.childText(p,"id"))).findFirst().orElseThrow();
        assertEquals("taxonomy.javascript.reports",XmlSupport.childText(XmlSupport.child(XmlSupport.child(override,"activation"),"property"),"name"));
        assertEquals("${taxonomy.javascript.reports}",XmlSupport.childText(XmlSupport.descendants(override,"configuration").getFirst(),"reportsDirectory"));
        var parent=XmlSupport.parse(root.resolve("pom.xml")).getDocumentElement();
        var lane=XmlSupport.children(XmlSupport.child(parent,"profiles"),"profile").stream()
                .filter(p -> "reformulation-usage-tests".equals(XmlSupport.childText(p,"id"))).findFirst().orElseThrow();
        assertEquals("${project.build.directory}/surefire-reports-reformulation-usage",
                XmlSupport.childText(XmlSupport.descendants(lane,"configuration").getFirst(),"reportsDirectory"));
    }
}
