package com.taxonomy.sparx.mapping;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import javax.xml.xpath.XPathNodes;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class AdmbwSourceProfileTest {
    private static byte[] readPinnedMdg() throws IOException {
        try (var input = new GZIPInputStream(AdmbwSourceProfileTest.class
                .getResourceAsStream("/admbw/NAFv4-ADMBw-MDG-2026.05.xml.gz"))) {
            return input.readAllBytes();
        }
    }

    private static Document parsePinnedMdg(byte[] bytes) throws Exception {
        AdmbwSourceProfile.CURRENT.verifyMdg(bytes);
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    @Test void pinnedBytesAndSupportedNativeTypesMatchOriginalMdg() throws Exception {
        byte[] bytes = readPinnedMdg();
        var profile = AdmbwSourceProfile.CURRENT;
        var xml = parsePinnedMdg(bytes);
        assertEquals(profile.profileVersion(), xml.getElementsByTagName("Documentation").item(0).getAttributes().getNamedItem("version").getNodeValue());
        assertEquals(profile.nativeNamespace(), xml.getElementsByTagName("Documentation").item(0).getAttributes().getNamedItem("id").getNodeValue());
        var types = new java.util.HashMap<String, String>();
        var requirementTags = new java.util.HashSet<String>();
        var stereotypes = xml.getElementsByTagName("Stereotype");
        for (int i = 0; i < stereotypes.getLength(); i++) {
            var stereotype = (org.w3c.dom.Element) stereotypes.item(i);
            if ("BWRequirement".equals(stereotype.getAttribute("name"))) {
                var tags = stereotype.getElementsByTagName("Tag");
                for (int j = 0; j < tags.getLength(); j++) {
                    var tag = (org.w3c.dom.Element) tags.item(j);
                    if ("String".equals(tag.getAttribute("type")) && tag.getAttribute("values").isEmpty()
                            && tag.getAttribute("default").isEmpty()) requirementTags.add(tag.getAttribute("name"));
                }
            }
            var apply = stereotype.getElementsByTagName("Apply");
            if (apply.getLength() > 0) types.putIfAbsent(stereotype.getAttribute("name"), ((org.w3c.dom.Element)apply.item(0)).getAttribute("type"));
        }
        for (var field : AdmbwRequirementField.values()) assertTrue(requirementTags.contains(field.nativeTag()), field.nativeTag());
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

    @TestFactory
    List<DynamicTest> supportedConnectorPairsMatchDirectedMdgConstraints() throws Exception {
        var profile = AdmbwSourceProfile.CURRENT;
        var xml = parsePinnedMdg(readPinnedMdg());
        var xpath = XPathFactory.newInstance().newXPath();
        String namespace = xpath.evaluate("/MDG.Technology/Documentation/@id", xml);
        var nativeTypes = xpath.evaluateExpression(
                "//UMLProfile[Documentation/@name = /MDG.Technology/Documentation/@id]"
                        + "/Content/Stereotypes/Stereotype", xml, XPathNodes.class);
        Map<String, Element> stereotypes = new HashMap<>();
        for (var node : nativeTypes) {
            Element type = (Element) node;
            assertNull(stereotypes.put(type.getAttribute("name"), type));
        }

        List<DynamicTest> checks = new ArrayList<>();
        for (var entry : profile.connectorRules().entrySet()) {
            String name = entry.getKey();
            var rule = entry.getValue();
            Element connector = stereotypes.get(name);
            assertNotNull(connector, name);
            Set<String> clients = allowedTypes(connector, "client");
            Set<String> suppliers = allowedTypes(connector, "supplier");
            for (String client : rule.clients()) {
                for (String supplier : rule.suppliers()) {
                    Set<String> clientTypes = ancestry(stereotypes, namespace, client);
                    Set<String> supplierTypes = ancestry(stereotypes, namespace, supplier);
                    checks.add(DynamicTest.dynamicTest(name + ": " + client + " → " + supplier, () -> {
                        assertTrue(rule.accepts(client, supplier));
                        assertFalse(Collections.disjoint(clients, clientTypes), "MDG client role");
                        assertFalse(Collections.disjoint(suppliers, supplierTypes), "MDG supplier role");
                    }));
                    checks.add(DynamicTest.dynamicTest(name + " reversed: " + supplier + " → " + client, () -> {
                        assertFalse(rule.accepts(supplier, client));
                        assertTrue(Collections.disjoint(clients, supplierTypes)
                                || Collections.disjoint(suppliers, clientTypes), "MDG accepts reverse direction");
                    }));
                }
            }
        }
        assertEquals(18, checks.size(), "nine supported endpoint pairs and their reversals");
        return checks;
    }

    private static Set<String> allowedTypes(Element connector, String role) {
        var allowed = new HashSet<String>();
        var constraints = connector.getElementsByTagName("metaconstraint");
        for (int i = 0; i < constraints.getLength(); i++) {
            Element constraint = (Element) constraints.item(i);
            if (role.equals(constraint.getAttribute("umlRole"))) {
                allowed.addAll(Arrays.asList(constraint.getAttribute("constraint").split(";")));
            }
        }
        assertFalse(allowed.isEmpty(), connector.getAttribute("name") + " has no MDG " + role);
        return allowed;
    }

    private static Set<String> ancestry(Map<String, Element> stereotypes, String namespace, String type) {
        var qualified = new HashSet<String>();
        var pending = new ArrayDeque<String>();
        pending.add(type);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!qualified.add(namespace + "::" + current)) continue;
            Element element = stereotypes.get(current);
            assertNotNull(element, current);
            // baseStereotypes includes secondary parents absent from generalizes; follow local parents transitively.
            for (String parent : element.getAttribute("baseStereotypes").split("\\s+")) {
                if (stereotypes.containsKey(parent)) pending.addLast(parent);
            }
        }
        return qualified;
    }
}
