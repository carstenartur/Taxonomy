package com.taxonomy.build;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import javax.tools.ToolProvider;
import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** The same packaged-product driver is exercised locally without claiming an installed-product pass. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkbenchProductProbeTest {
    private static final String NS = "http://www.opengroup.org/xsd/archimate/3.0/";
    @TempDir static Path temporary;
    private URLClassLoader loader;
    private Class<?> probe;
    private Path xml;
    private Map<String, byte[]> bundle;

    @BeforeAll
    void compileActualDriverAndExportActualWorkbenchBundle() throws Exception {
        Path root = ExternalDriverSupport.root();
        Path classes = Files.createDirectory(temporary.resolve("classes"));
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "25",
                "-cp", classpath, "-d", classes.toString(),
                root.resolve(".github/scripts/InteroperabilityProductProbe.java").toString(),
                root.resolve(".github/scripts/WorkbenchInteroperabilityProbe.java").toString()));
        loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader());
        probe = loader.loadClass("WorkbenchInteroperabilityProbe");
        Path zip = temporary.resolve("workbench.zip");
        xml = temporary.resolve("model.archimate.xml");
        invoke("export", zip, xml);
        bundle = new LinkedHashMap<>();
        try (var stream = new ZipInputStream(new ByteArrayInputStream(Files.readAllBytes(zip)), StandardCharsets.UTF_8)) {
            for (var entry = stream.getNextEntry(); entry != null; entry = stream.getNextEntry()) {
                assertNull(bundle.put(entry.getName(), stream.readAllBytes()));
            }
        }
    }

    @AfterAll
    void closeDriverLoader() throws Exception {
        if (loader != null) loader.close();
    }

    @Test
    void installedProductReceivesExactSnapshotServiceBytesAndProfile() throws Exception {
        assertEquals(java.util.Set.of("model.archimate.xml", "mapping-profile.tsv", "manifest.json"), bundle.keySet());
        assertArrayEquals(bundle.get("model.archimate.xml"), Files.readAllBytes(xml));
        var manifest = new ObjectMapper().readTree(bundle.get("manifest.json"));
        assertEquals("taxonomy-archimate-3.1-v2", manifest.path("profile").asText());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(xml))),
                manifest.path("xmlSha256").asText());
        assertEquals("snapshot-archi-product", manifest.path("provenance").path("taxonomy.snapshotId").path("value").asText());
    }

    @Test
    void unchangedExportPassesSemanticComparison() throws Exception {
        invoke("compare", xml, xml);
    }

    @Test
    void alteredFrozenRequirementProvenanceFails() throws Exception {
        Path changed = changed("Protect subscriber information and retain delivery history.", "Invented requirement.");
        assertThrows(AssertionError.class, () -> invoke("compare", xml, changed));
    }

    @Test
    void alteredElementLabelFails() throws Exception {
        Path changed = changed("Subscriber protection", "Unrelated capability");
        assertThrows(AssertionError.class, () -> invoke("compare", xml, changed));
    }

    @Test
    void alteredViewGeometryFails() throws Exception {
        String original = Files.readString(xml);
        var matcher = java.util.regex.Pattern.compile(" x=\"(\\d+)\"").matcher(original);
        assertTrue(matcher.find());
        Path changed = temporary.resolve("moved.xml");
        Files.writeString(changed, original.substring(0, matcher.start(1))
                + (Integer.parseInt(matcher.group(1)) + 1) + original.substring(matcher.end(1)));
        assertThrows(AssertionError.class, () -> invoke("compare", xml, changed));
    }

    @Test
    void observedArchiPropertyTypeErasureIsRecordedWithoutLosingValues() throws Exception {
        Path changed = changed("type=\"boolean\"", "type=\"string\"");
        invoke("compare", xml, changed);
        var comparison = probe.getDeclaredMethod("compare", Path.class, Path.class);
        comparison.setAccessible(true);
        var losses = new ObjectMapper().valueToTree(comparison.invoke(null, xml, changed));
        assertEquals(3, losses.size());
        for (var loss : losses) {
            assertEquals("ARCHI_PROPERTY_TYPE_TO_STRING", loss.path("code").asText());
            assertEquals("TRANSFORMED", loss.path("disposition").asText());
            assertTrue(loss.path("detail").asText().contains("boolean -> string"));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new com.taxonomy.archimate.exchange.ArchiMateExchangeReader().read(Files.readAllBytes(changed)));
    }

    @Test
    void unexpectedPropertyTypeChangeFails() throws Exception {
        Path changed = changed("type=\"boolean\"", "type=\"number\"");
        assertThrows(AssertionError.class, () -> invoke("compare", xml, changed));
    }

    @Test
    void changedBooleanValueStillFailsAfterObservedTypeErasure() throws Exception {
        Path changed = changed("type=\"boolean\"", "type=\"string\"");
        String content = Files.readString(changed);
        assertTrue(content.contains(">true</value>"));
        Files.writeString(changed, content.replace(">true</value>", ">false</value>"));
        assertThrows(AssertionError.class, () -> invoke("compare", xml, changed));
    }

    @Test
    void observedLanguageAndFolderLossesAreRecorded() throws Exception {
        var document = com.taxonomy.exchange.ExchangeXml.parse(Files.readAllBytes(xml));
        var values = document.getElementsByTagNameNS("http://www.opengroup.org/xsd/archimate/3.0/", "value");
        for (int index = 0; index < values.getLength(); index++)
            ((org.w3c.dom.Element) values.item(index)).removeAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "lang");
        var organizations = document.getElementsByTagNameNS("http://www.opengroup.org/xsd/archimate/3.0/", "organizations").item(0);
        organizations.getParentNode().removeChild(organizations);
        Path changed = temporary.resolve("observed-archi-representation.xml");
        Files.write(changed, com.taxonomy.exchange.ExchangeXml.write(document));
        var comparison = probe.getDeclaredMethod("compare", Path.class, Path.class);
        comparison.setAccessible(true);
        var losses = new ObjectMapper().valueToTree(comparison.invoke(null, xml, changed));
        var codes = new java.util.HashSet<String>();
        for (var loss : losses) {
            codes.add(loss.path("code").asText());
            assertEquals("UNSUPPORTED", loss.path("disposition").asText());
        }
        assertEquals(java.util.Set.of("ARCHI_PROPERTY_LANGUAGE_OMITTED", "ARCHI_TYPE_FOLDERS_OMITTED"), codes);
    }

    @Test
    void unexpectedLanguageChangeFails() throws Exception {
        Path changed = changed("xml:lang=\"en\"", "xml:lang=\"de\"");
        assertThrows(AssertionError.class, () -> invoke("compare", xml, changed));
    }

    @Test
    void changedNodeLineWidthFails() throws Exception {
        Path changed = changed("lineWidth=\"3\"", "lineWidth=\"30\"");
        assertThrows(AssertionError.class, () -> invoke("compare", xml, changed));
    }

    @Test
    void changedVisualNodeLabelFailsWithoutChangingTheElementName() throws Exception {
        Path changed = changed("<label xml:lang=\"en\">Subscriber protection</label>",
                "<label xml:lang=\"en\">Changed visual label</label>");
        assertThrows(AssertionError.class, () -> invoke("compare", xml, changed));
    }

    @Test
    void changedRelationshipNameFailsWithoutChangingItsType() throws Exception {
        Path changed = changed("<name xml:lang=\"en\">FULFILLS</name>",
                "<name xml:lang=\"en\">Changed relationship name</name>");
        assertThrows(AssertionError.class, () -> invoke("compare", xml, changed));
    }

    @Test
    void observedNativePresentationAndNameLanguageLossesAreRecordedPerItem() throws Exception {
        Path changed = writeChanged(observedNativeRepresentations());
        var comparison = probe.getDeclaredMethod("compare", Path.class, Path.class);
        comparison.setAccessible(true);
        var losses = new ObjectMapper().valueToTree(comparison.invoke(null, xml, changed));
        var counts = new java.util.TreeMap<String, Integer>();
        for (var loss : losses)
            counts.merge(loss.path("code").asText(), 1, Integer::sum);
        assertEquals(Map.of("ARCHI_PROPERTY_TYPE_TO_STRING", 8, "ARCHI_PROPERTY_LANGUAGE_OMITTED", 96,
                "ARCHI_NAME_LANGUAGE_OMITTED", 89, "ARCHI_NODE_LABEL_OMITTED", 3,
                "ARCHI_NODE_LINE_WIDTH_OMITTED", 2, "ARCHI_NODE_STYLE_DEFAULTS_ADDED", 3,
                "ARCHI_CONNECTION_STYLE_ADDED", 1, "ARCHI_TYPE_FOLDERS_OMITTED", 2), counts);
        var items = new java.util.HashSet<String>();
        for (var loss : losses) {
            assertFalse(loss.path("artifactId").asText().isBlank());
            assertTrue(items.add(loss.path("artifactId").asText() + "/" + loss.path("field").asText()
                    + "/" + loss.path("code").asText()), "Every loss must identify its affected item");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"fill-alpha", "line-color", "font", "line-width", "connection-font"})
    void unknownStyleChangesFailAfterAllObservedLosses(String change) throws Exception {
        var document = observedNativeRepresentations();
        var node = first(document, "node");
        var style = com.taxonomy.exchange.ExchangeXml.children(node).stream().filter(e -> e.getLocalName().equals("style")).findFirst().orElseThrow();
        switch (change) {
            case "fill-alpha" -> first(style, "fillColor").setAttribute("a", "99");
            case "line-color" -> first(style, "lineColor").setAttribute("r", "91");
            case "font" -> first(style, "font").setAttribute("name", "Serif");
            case "line-width" -> style.setAttribute("lineWidth", "30");
            case "connection-font" -> first(first(document, "connection"), "font").setAttribute("size", "10");
            default -> fail("Unknown test mutation");
        }
        Path changed = writeChanged(document);
        assertThrows(AssertionError.class, () -> invoke("compare", xml, changed));
    }

    @ParameterizedTest
    @ValueSource(strings = {"model", "element", "relationship", "view", "propertyDefinition"})
    void unknownNameLanguagesFailAfterAllObservedLosses(String ownerType) throws Exception {
        var document = observedNativeRepresentations();
        var owner = first(document, ownerType);
        var name = com.taxonomy.exchange.ExchangeXml.children(owner).stream().filter(e -> e.getLocalName().equals("name")).findFirst().orElseThrow();
        name.setAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "xml:lang", "de");
        Path changed = writeChanged(document);
        assertThrows(AssertionError.class, () -> invoke("compare", xml, changed));
    }

    /** Observed Archi 5.10.0 presentation, independently transcribed from the retained native pair. */
    private org.w3c.dom.Document observedNativeRepresentations() throws Exception {
        var document = com.taxonomy.exchange.ExchangeXml.parse(Files.readAllBytes(xml));
        var all = document.getElementsByTagNameNS(NS, "*");
        for (int index = 0; index < all.getLength(); index++) {
            var element = (org.w3c.dom.Element) all.item(index);
            element.removeAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "lang");
            if (element.getLocalName().equals("propertyDefinition") && java.util.Set.of("number", "boolean").contains(element.getAttribute("type")))
                element.setAttribute("type", "string");
        }
        var organizations = first(document, "organizations"); organizations.getParentNode().removeChild(organizations);
        var nodes = document.getElementsByTagNameNS(NS, "node");
        assertEquals(3, nodes.getLength());
        for (int index = 0; index < nodes.getLength(); index++) {
            var node = (org.w3c.dom.Element) nodes.item(index);
            var label = first(node, "label"); node.removeChild(label);
            var style = first(node, "style"); style.removeAttribute("lineWidth");
            first(style, "fillColor").setAttribute("a", "100");
            var defaults = com.taxonomy.exchange.ExchangeXml.parse(("<style xmlns=\"" + NS + "\">"
                    + "<lineColor r=\"92\" g=\"92\" b=\"92\" a=\"100\"/>"
                    + "<font name=\"Sans\" size=\"9\"><color r=\"0\" g=\"0\" b=\"0\"/></font></style>").getBytes(StandardCharsets.UTF_8));
            for (var child : com.taxonomy.exchange.ExchangeXml.children(defaults.getDocumentElement()))
                style.appendChild(document.importNode(child, true));
        }
        var connection = first(document, "connection");
        var connectionStyle = com.taxonomy.exchange.ExchangeXml.parse(("<style xmlns=\"" + NS + "\">"
                + "<lineColor r=\"0\" g=\"0\" b=\"0\"/>"
                + "<font name=\"Sans\" size=\"9\"><color r=\"0\" g=\"0\" b=\"0\"/></font></style>").getBytes(StandardCharsets.UTF_8));
        connection.appendChild(document.importNode(connectionStyle.getDocumentElement(), true));
        return document;
    }

    private static org.w3c.dom.Element first(org.w3c.dom.Node owner, String name) {
        var nodes = owner instanceof org.w3c.dom.Document document
                ? document.getElementsByTagNameNS(NS, name) : ((org.w3c.dom.Element) owner).getElementsByTagNameNS(NS, name);
        assertTrue(nodes.getLength() > 0, "Mutation must affect an emitted " + name);
        return (org.w3c.dom.Element) nodes.item(0);
    }

    private Path writeChanged(org.w3c.dom.Document document) throws Exception {
        Path changed = temporary.resolve("changed-" + java.util.UUID.randomUUID() + ".xml");
        Files.write(changed, com.taxonomy.exchange.ExchangeXml.write(document));
        return changed;
    }

    private Path changed(String before, String after) throws Exception {
        String original = Files.readString(xml);
        assertTrue(original.contains(before), "Fixture mutation must affect an actual exported value");
        Path changed = temporary.resolve("changed-" + java.util.UUID.randomUUID() + ".xml");
        Files.writeString(changed, original.replace(before, after));
        return changed;
    }

    private void invoke(String action, Path source, Path target) throws Exception {
        try {
            probe.getMethod("main", String[].class).invoke(null, (Object) new String[]{action, source.toString(), target.toString()});
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Error error) throw error;
            if (failure.getCause() instanceof Exception exception) throw exception;
            throw failure;
        }
    }
}
