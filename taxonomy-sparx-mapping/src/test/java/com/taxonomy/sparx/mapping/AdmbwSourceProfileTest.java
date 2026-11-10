package com.taxonomy.sparx.mapping;

import org.junit.jupiter.api.Test;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class AdmbwSourceProfileTest {
    @Test void pinnedBytesAndSupportedNativeTypesMatchOriginalMdg() throws Exception {
        byte[] bytes;
        try (var input = new GZIPInputStream(getClass().getResourceAsStream("/admbw/NAFv4-ADMBw-MDG-2026.05.xml.gz"))) {
            bytes = input.readAllBytes();
        }
        var profile = AdmbwSourceProfile.CURRENT;
        profile.verifyMdg(bytes);
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var xml = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        assertEquals(profile.profileVersion(), xml.getElementsByTagName("Documentation").item(0).getAttributes().getNamedItem("version").getNodeValue());
        assertEquals(profile.nativeNamespace(), xml.getElementsByTagName("Documentation").item(0).getAttributes().getNamedItem("id").getNodeValue());
        var types = new java.util.HashMap<String, String>();
        var stereotypes = xml.getElementsByTagName("Stereotype");
        for (int i = 0; i < stereotypes.getLength(); i++) {
            var stereotype = (org.w3c.dom.Element) stereotypes.item(i);
            var apply = stereotype.getElementsByTagName("Apply");
            if (apply.getLength() > 0) types.putIfAbsent(stereotype.getAttribute("name"), ((org.w3c.dom.Element)apply.item(0)).getAttribute("type"));
        }
        profile.elementTypes().forEach((name, type) -> assertEquals(type, types.get(name), name));
        profile.connectorTypes().forEach((name, type) -> assertEquals(type, types.get(name), name));
        for (String qualified : profile.diagramTypes().values()) {
            String[] parts = qualified.split("::", 2);
            boolean found = false;
            var profiles = xml.getElementsByTagName("UMLProfile");
            for (int i = 0; i < profiles.getLength(); i++) {
                var nativeProfile = (org.w3c.dom.Element) profiles.item(i);
                var documentation = nativeProfile.getElementsByTagName("Documentation");
                if (documentation.getLength() == 0 || !((org.w3c.dom.Element)documentation.item(0)).getAttribute("name").equals(parts[0])) continue;
                var diagramTypes = nativeProfile.getElementsByTagName("Stereotype");
                for (int j = 0; j < diagramTypes.getLength(); j++) {
                    if (((org.w3c.dom.Element)diagramTypes.item(j)).getAttribute("name").equals(parts[1])) found = true;
                }
            }
            assertTrue(found, qualified);
        }
        bytes[bytes.length - 1] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> profile.verifyMdg(bytes));
        assertThrows(UnsupportedOperationException.class, () -> profile.elementTypes().put("Invented", "Class"));
    }
}
