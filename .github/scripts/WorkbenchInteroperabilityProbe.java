import com.taxonomy.archimate.exchange.ArchiMateExchangeProfile;
import com.taxonomy.archimate.exchange.ArchiMateExchangeReader;
import com.taxonomy.archimate.exchange.ArchiMateXmlExporter;
import com.taxonomy.architecture.export.CanonicalDiagramExportService;
import com.taxonomy.diagram.*;
import com.taxonomy.exchange.ArchiMateExchangeCodec;
import com.taxonomy.exchange.ExchangeXml;
import com.taxonomy.export.ArchiMateDiagramService;
import com.taxonomy.extension.api.integration.IntegrationContracts.ExchangeDocument;
import com.taxonomy.extension.api.integration.IntegrationContracts.MappingLoss;
import com.taxonomy.extension.api.integration.IntegrationContracts.LossDisposition;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.workbench.ArchitectureSnapshotExportService;
import com.taxonomy.portfolio.workbench.ArchitectureWorkbenchService;
import com.taxonomy.portfolio.workbench.ArchitectureWorkbenchDtos.*;
import com.taxonomy.workspace.service.WorkspaceContext;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipInputStream;

/** Uses the actual workbench bundle exporter. The authored projection is not a live analysis or an authorization test. */
public final class WorkbenchInteroperabilityProbe {
    private static final String NS = "http://www.opengroup.org/xsd/archimate/3.0/";
    private static final String NODE_DEFAULTS = "<style xmlns=\"" + NS + "\"><lineColor r=\"92\" g=\"92\" b=\"92\" a=\"100\"/>"
            + "<font name=\"Sans\" size=\"9\"><color r=\"0\" g=\"0\" b=\"0\"/></font></style>";
    private static final String CONNECTION_STYLE = "<style xmlns=\"" + NS + "\"><lineColor r=\"0\" g=\"0\" b=\"0\"/>"
            + "<font name=\"Sans\" size=\"9\"><color r=\"0\" g=\"0\" b=\"0\"/></font></style>";
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected action, source and target paths");
        if (args[0].equals("export")) export(Path.of(args[1]), Path.of(args[2]));
        else if (args[0].equals("compare")) compare(Path.of(args[1]), Path.of(args[2]));
        else throw new IllegalArgumentException("Unsupported workbench probe action");
    }

    private static void export(Path archive, Path xmlPath) throws Exception {
        var service = new ArchitectureSnapshotExportService(new FrozenWorkbench(),
                new CanonicalDiagramExportService(null, null, new ArchiMateDiagramService(), new ArchiMateXmlExporter()));
        var bundle = service.exportArchiMateBundle(42L, "snapshot-archi-product", "fixture-author",
                new WorkspaceContext("fixture-author", "workspace", "main", "repository"));
        if (!ArchiMateExchangeProfile.VERSION.equals(bundle.exporterProfile())) throw new AssertionError("Unexpected workbench profile");
        Files.write(archive, bundle.content());
        byte[] xml = null;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bundle.content()), StandardCharsets.UTF_8)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals("model.archimate.xml")) {
                    if (xml != null) throw new AssertionError("Duplicate XML entry");
                    xml = zip.readAllBytes();
                }
            }
        }
        if (xml == null) throw new AssertionError("Workbench bundle did not contain its model XML");
        new ArchiMateExchangeReader().read(xml);
        Files.write(xmlPath, xml);
        System.out.println("WORKBENCH_EXPORT_PROFILE " + bundle.exporterProfile());
    }

    private static List<MappingLoss> compare(Path source, Path returned) throws Exception {
        // Exercise the production bounded return adapter independently of the product comparator.
        // The strict default still rejects type-erased output; only this original-bound profile accepts it.
        try {
            var returnedModel = new com.taxonomy.archimate.exchange.ArchiMateReturnProfile().read(Files.readAllBytes(source), Files.readAllBytes(returned));
            var reader = new ArchiMateExchangeReader();
            requireEqual("production return diagram", reader.toDiagram(reader.read(Files.readAllBytes(source))), reader.toDiagram(returnedModel));
            System.out.println("RETURN_IMPORT_PROFILE " + com.taxonomy.archimate.exchange.ArchiMateReturnProfile.VERSION);
            System.out.println("RETURN_IMPORT_LOSSES " + tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(returnedModel.losses()));
        } catch (IllegalArgumentException rejected) {
            throw new AssertionError("Production bounded return reader rejected the product output", rejected);
        }
        var codec = new ArchiMateExchangeCodec();
        var before = codec.read(Files.readAllBytes(source), null, true);
        var after = codec.read(Files.readAllBytes(returned), null, true);
        var losses = new ArrayList<MappingLoss>();
        var sourceXml = ExchangeXml.parse(Files.readAllBytes(source));
        var returnedXml = ExchangeXml.parse(Files.readAllBytes(returned));
        requireKnownLanguageLocations(sourceXml);
        requireKnownLanguageLocations(returnedXml);
        comparePropertyRepresentations(sourceXml, returnedXml, losses);
        compareOrganizations(sourceXml, returnedXml, losses);
        compareNames(sourceXml, returnedXml, losses);
        compareLabels(sourceXml, returnedXml, losses);
        compareStyles(sourceXml, returnedXml, losses);
        before = codec.read(ExchangeXml.write(sourceXml), null, true);
        var originalMetadata = modelMetadata(before);
        var returnedMetadata = modelMetadata(after);
        requireEqual("model identity", originalMetadata.identifier(), returnedMetadata.identifier());
        requireEqual("model title", originalMetadata.title(), returnedMetadata.title());
        requireEqual("model property values and frozen provenance", originalMetadata.properties(), returnedMetadata.properties());
        requireEqual("property definition names", originalMetadata.definitionTypes().keySet(), returnedMetadata.definitionTypes().keySet());
        originalMetadata.definitionTypes().forEach((name, originalType) -> {
            String actualType = returnedMetadata.definitionTypes().get(name);
            if (originalType.equals(actualType)) return;
            // Observed with the pinned installed Archi 5.10.0. Values are still compared exactly;
            // the production typed reader remains strict and does not accept this erasure.
            if (!Set.of("number", "boolean").contains(originalType) || !"string".equals(actualType))
                throw new AssertionError("Unrecognized product property type change for " + name + ": " + originalType + " -> " + actualType);
            losses.add(new MappingLoss(name, "propertyDefinition.type", "ARCHI_PROPERTY_TYPE_TO_STRING", LossDisposition.TRANSFORMED,
                    "Archi 5.10.0 exports the property declaration as " + originalType + " -> string; exact property values are checked separately. The typed Taxonomy profile is not preserved."));
        });
        requireEqual("element/relation identities, properties, view membership and geometry",
                InteroperabilityProductProbe.semantics("archimate", before),
                InteroperabilityProductProbe.semantics("archimate", after));
        losses.addAll(InteroperabilityProductProbe.compareConnectionGeometry(before, after));
        System.out.println("PRODUCT_MAPPING_LOSSES " + tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(losses));
        return List.copyOf(losses);
    }

    private static void comparePropertyRepresentations(org.w3c.dom.Document before, org.w3c.dom.Document after, List<MappingLoss> losses) {
        var originals = scopedProperties(before);
        var returned = scopedProperties(after);
        requireEqual("property membership", originals.keySet(), returned.keySet());
        originals.forEach((key, property) -> {
            var actual = returned.get(key);
            var values = ExchangeXml.children(property);
            var actualValues = ExchangeXml.children(actual);
            requireEqual("property value count " + key, values.size(), actualValues.size());
            for (int index = 0; index < values.size(); index++) {
                var value = values.get(index);
                if ("en".equals(value.getAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "lang"))
                        && !actualValues.get(index).hasAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "lang")) {
                    value.removeAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "lang");
                    losses.add(new MappingLoss(key, "value.xml:lang", "ARCHI_PROPERTY_LANGUAGE_OMITTED", LossDisposition.UNSUPPORTED,
                            "Archi 5.10.0 omits the emitted en property-value language marker; the value and all remaining attributes are compared exactly."));
                }
            }
            var expectedValue = (org.w3c.dom.Element) property.cloneNode(true);
            var actualValue = (org.w3c.dom.Element) actual.cloneNode(true);
            expectedValue.removeAttribute("propertyDefinitionRef");
            actualValue.removeAttribute("propertyDefinitionRef");
            requireEqual("property content " + key, ExchangeXml.semantic(ExchangeXml.xml(expectedValue)), ExchangeXml.semantic(ExchangeXml.xml(actualValue)));
        });
    }

    private static Map<String, org.w3c.dom.Element> scopedProperties(org.w3c.dom.Document document) {
        var definitions = new HashMap<String, String>();
        var nodes = document.getElementsByTagNameNS("http://www.opengroup.org/xsd/archimate/3.0/", "propertyDefinition");
        for (int index = 0; index < nodes.getLength(); index++) {
            var definition = (org.w3c.dom.Element) nodes.item(index);
            definitions.put(definition.getAttribute("identifier"), ExchangeXml.text(definition, "name"));
        }
        var result = new TreeMap<String, org.w3c.dom.Element>();
        nodes = document.getElementsByTagNameNS("http://www.opengroup.org/xsd/archimate/3.0/", "property");
        for (int index = 0; index < nodes.getLength(); index++) {
            var property = (org.w3c.dom.Element) nodes.item(index);
            var owner = (org.w3c.dom.Element) property.getParentNode().getParentNode();
            String name = definitions.get(property.getAttribute("propertyDefinitionRef"));
            if (name == null || owner.getAttribute("identifier").isBlank()) throw new AssertionError("Unscoped property");
            if (result.putIfAbsent(owner.getAttribute("identifier") + "/" + name, property) != null)
                throw new AssertionError("Duplicate scoped property");
        }
        return result;
    }

    private static void compareNames(org.w3c.dom.Document before, org.w3c.dom.Document after, List<MappingLoss> losses) {
        var original = scopedFields(before, "name"); var returned = scopedFields(after, "name");
        requireEqual("name membership", original.keySet(), returned.keySet());
        original.forEach((key, name) -> {
            var actual = returned.get(key);
            if ("en".equals(name.getAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "lang"))
                    && !actual.hasAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "lang")) {
                name.removeAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "lang");
                losses.add(new MappingLoss(key, "name.xml:lang", "ARCHI_NAME_LANGUAGE_OMITTED", LossDisposition.UNSUPPORTED,
                        "Archi 5.10.0 omits the emitted en name language marker for " + name.getTextContent()
                                + "; the name text and remaining attributes are compared exactly."));
            }
            requireEqual("name content " + key, field(name), field(actual));
        });
    }

    private static void compareLabels(org.w3c.dom.Document before, org.w3c.dom.Document after, List<MappingLoss> losses) {
        var original = scopedFields(before, "label"); var returned = scopedFields(after, "label");
        var keys = new TreeSet<>(original.keySet()); keys.addAll(returned.keySet());
        for (String key : keys) {
            var label = original.get(key); var actual = returned.get(key);
            if (label != null && actual == null && key.startsWith("node/") && emittedEnglishLabel(label)) {
                losses.add(new MappingLoss(key, "label", "ARCHI_NODE_LABEL_OMITTED", LossDisposition.UNSUPPORTED,
                        "Archi 5.10.0 omits the complete emitted node label, including its en language marker: " + ExchangeXml.xml(label)));
                label.getParentNode().removeChild(label);
            } else requireEqual("visual label " + key, field(label), field(actual));
        }
    }

    private static void compareStyles(org.w3c.dom.Document before, org.w3c.dom.Document after, List<MappingLoss> losses) {
        var original = scopedFields(before, "style"); var returned = scopedFields(after, "style");
        var keys = new TreeSet<>(original.keySet()); keys.addAll(returned.keySet());
        for (String key : keys) {
            var style = original.get(key); var actual = returned.get(key);
            if (Objects.equals(field(style), field(actual))) continue;
            if (style == null && actual != null && key.startsWith("connection/")) {
                requireEqual("observed connection style " + key, ExchangeXml.semantic(CONNECTION_STYLE), field(actual));
                losses.add(new MappingLoss(key, "style", "ARCHI_CONNECTION_STYLE_ADDED", LossDisposition.TRANSFORMED,
                        "Archi 5.10.0 adds the observed black line and Sans 9 black font: " + ExchangeXml.xml(actual)));
            } else if (style != null && actual != null && key.startsWith("node/")) {
                var children = ExchangeXml.children(style); var attributes = attributes(style);
                if (!(attributes.isEmpty() || attributes.equals(Map.of("lineWidth", "3"))) || children.size() != 1
                        || !children.getFirst().getLocalName().equals("fillColor")
                        || !attributes(children.getFirst()).keySet().equals(Set.of("r", "g", "b")))
                    throw new AssertionError("Unrecognized source node style for product transformation: " + key);
                var expected = (org.w3c.dom.Element) style.cloneNode(true);
                expected.removeAttribute("lineWidth");
                ExchangeXml.children(expected).getFirst().setAttribute("a", "100");
                var additions = ExchangeXml.parse(NODE_DEFAULTS.getBytes(StandardCharsets.UTF_8));
                for (var addition : ExchangeXml.children(additions.getDocumentElement()))
                    expected.appendChild(expected.getOwnerDocument().importNode(addition, true));
                requireEqual("observed node style " + key, field(expected), field(actual));
                if (style.hasAttribute("lineWidth")) losses.add(new MappingLoss(key, "style.lineWidth", "ARCHI_NODE_LINE_WIDTH_OMITTED", LossDisposition.UNSUPPORTED,
                        "Archi 5.10.0 omits the emitted lineWidth=3; arbitrary line-width replacements are rejected."));
                losses.add(new MappingLoss(key, "style", "ARCHI_NODE_STYLE_DEFAULTS_ADDED", LossDisposition.TRANSFORMED,
                        "Archi 5.10.0 adds fill alpha 100, line RGBA 92/92/92/100 and Sans 9 black font; original fill RGB remains exact: " + ExchangeXml.xml(actual)));
            } else throw new AssertionError("Unrecognized product style change for " + key);
        }
    }

    private static Map<String, org.w3c.dom.Element> scopedFields(org.w3c.dom.Document document, String field) {
        var result = new TreeMap<String, org.w3c.dom.Element>();
        var nodes = document.getElementsByTagNameNS(NS, field);
        for (int index = 0; index < nodes.getLength(); index++) {
            var value = (org.w3c.dom.Element) nodes.item(index);
            var owner = (org.w3c.dom.Element) value.getParentNode();
            if (field.equals("label") && owner.getLocalName().equals("item")) continue; // Compared with the complete organizations subtree.
            String identity = owner.getLocalName().equals("propertyDefinition") ? ExchangeXml.text(owner, "name") : owner.getAttribute("identifier");
            if (identity.isBlank() || result.putIfAbsent(owner.getLocalName() + "/" + identity, value) != null)
                throw new AssertionError("Unscoped or duplicate " + field + " field");
        }
        return result;
    }

    private static void requireKnownLanguageLocations(org.w3c.dom.Document document) {
        var elements = document.getElementsByTagNameNS("*", "*");
        for (int index = 0; index < elements.getLength(); index++) {
            var element = (org.w3c.dom.Element) elements.item(index);
            if (element.hasAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "lang")
                    && (!NS.equals(element.getNamespaceURI()) || !Set.of("name", "label", "value").contains(element.getLocalName())))
                throw new AssertionError("Unexpected language-bearing field: " + element.getLocalName());
        }
    }

    private static boolean emittedEnglishLabel(org.w3c.dom.Element label) {
        return attributes(label).equals(Map.of(javax.xml.XMLConstants.XML_NS_URI + "|lang", "en"))
                && ExchangeXml.children(label).isEmpty() && !label.getTextContent().isBlank();
    }

    private static Map<String, String> attributes(org.w3c.dom.Element element) {
        var result = new TreeMap<String, String>();
        for (int index = 0; index < element.getAttributes().getLength(); index++) {
            var attribute = element.getAttributes().item(index);
            if (javax.xml.XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())) continue;
            result.put(attribute.getNamespaceURI() == null ? attribute.getNodeName() : attribute.getNamespaceURI() + "|" + attribute.getLocalName(), attribute.getNodeValue());
        }
        return result;
    }

    private static String field(org.w3c.dom.Element element) {
        return element == null ? null : ExchangeXml.semantic(ExchangeXml.xml(element));
    }

    private static void compareOrganizations(org.w3c.dom.Document before, org.w3c.dom.Document after, List<MappingLoss> losses) {
        var original = ExchangeXml.children(before.getDocumentElement()).stream().filter(e -> e.getLocalName().equals("organizations")).toList();
        var returned = ExchangeXml.children(after.getDocumentElement()).stream().filter(e -> e.getLocalName().equals("organizations")).toList();
        if (original.size() == 1 && returned.isEmpty()) {
            var groups = ExchangeXml.children(original.getFirst());
            for (var group : groups) {
                if (!group.getLocalName().equals("item") || group.hasAttribute("identifierRef")) throw new AssertionError("Unexpected source folder shape");
                var members = ExchangeXml.children(group).stream().filter(e -> e.getLocalName().equals("item")).toList();
                if (members.isEmpty() || members.stream().anyMatch(e -> !e.hasAttribute("identifierRef") || !ExchangeXml.children(e).isEmpty()))
                    throw new AssertionError("Only the observed flat source type folders may be omitted");
                var labels = ExchangeXml.children(group).stream().filter(e -> e.getLocalName().equals("label")).toList();
                if (labels.size() != 1 || !emittedEnglishLabel(labels.getFirst()) || ExchangeXml.children(group).size() != members.size() + 1)
                    throw new AssertionError("Only the observed en-labelled source type folders may be omitted");
                losses.add(new MappingLoss(before.getDocumentElement().getAttribute("identifier") + "/organizations/" + ExchangeXml.text(group, "label"), "organizations", "ARCHI_TYPE_FOLDERS_OMITTED", LossDisposition.UNSUPPORTED,
                        "Archi 5.10.0 omits the flat source type folder " + ExchangeXml.text(group, "label")
                                + ", including its label's en language marker, with references " + members.stream().map(e -> e.getAttribute("identifierRef")).toList() + "; element identities and view membership are compared separately."));
            }
            before.getDocumentElement().removeChild(original.getFirst());
        } else {
            requireEqual("organization folders", original.stream().map(e -> ExchangeXml.semantic(ExchangeXml.xml(e))).toList(),
                    returned.stream().map(e -> ExchangeXml.semantic(ExchangeXml.xml(e))).toList());
        }
    }

    private record ModelMetadata(String identifier, String title, Map<String, String> definitionTypes, Map<String, String> properties) {}

    private static ModelMetadata modelMetadata(ExchangeDocument document) {
        var metadata = document.metadata();
        var definitions = new TreeMap<String, List<String>>();
        var namedTypes = new TreeMap<String, String>();
        var root = ExchangeXml.parse(metadata.get("propertyDefinitions").getBytes(StandardCharsets.UTF_8)).getDocumentElement();
        for (var definition : ExchangeXml.children(root)) {
            String name = ExchangeXml.text(definition, "name");
            if (namedTypes.putIfAbsent(name, definition.getAttribute("type")) != null) throw new AssertionError("Duplicate property name");
            definitions.put(definition.getAttribute("identifier"), List.of(name, definition.getAttribute("type")));
        }
        var properties = new TreeMap<String, String>();
        root = ExchangeXml.parse(metadata.get("properties").getBytes(StandardCharsets.UTF_8)).getDocumentElement();
        for (var property : ExchangeXml.children(root)) {
            var definition = definitions.get(property.getAttribute("propertyDefinitionRef"));
            var values = ExchangeXml.children(property);
            if (definition == null || values.size() != 1 || !values.getFirst().getLocalName().equals("value"))
                throw new AssertionError("Invalid model provenance property");
            if (properties.putIfAbsent(definition.getFirst(), values.getFirst().getTextContent()) != null)
                throw new AssertionError("Duplicate model provenance property");
        }
        return new ModelMetadata(metadata.get("identifier"), metadata.get("title"), namedTypes, properties);
    }

    private static void requireEqual(String part, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("Product changed workbench " + part
                + ": expected=" + expected + "; actual=" + actual);
    }

    private static final class FrozenWorkbench extends ArchitectureWorkbenchService {
        FrozenWorkbench() { super(null, null, null, null, null, null); }
        @Override public Projection load(Long projectId, String snapshot, String username, WorkspaceContext context) {
            if (!Objects.equals(projectId, 42L) || !snapshot.equals("snapshot-archi-product")
                    || !username.equals("fixture-author") || !context.repositoryId().equals("repository"))
                throw new AssertionError("Workbench export changed frozen source coordinates");
            var graph = new DiagramModel("Workbench interoperability fixture", List.of(
                    new DiagramNode("CP", "Subscriber protection", "Capabilities", .98765, true, 1),
                    new DiagramNode("CR", "Delivery service", "Core Services", .87654, false, 2)),
                    List.of(new DiagramEdge("relation", "CR", "CP", "FULFILLS", .76543, "trace")), new DiagramLayout("LR", true));
            var metadata = new ElementMetadata("CP", "Subscriber protection", "CP", 98, .98765, .8, "HUMAN", "CP",
                    "Recorded fixture contribution", false, null, null, "Fixture evidence reference", "fixture-author",
                    Instant.parse("2026-09-01T12:00:00Z"), "Reviewed fixture rationale");
            return new Projection(42L, "PRJ", "Fixture project", 7L, "REQ", "Fixture requirement",
                    "Protect subscriber information and retain delivery history.", snapshot, AnalysisStatus.SUCCESS,
                    Instant.parse("2026-09-01T11:00:00Z"), "PRIVATE_PROVIDER", "PRIVATE_MODEL", "workspace", "main",
                    "a".repeat(40), graph, null, Map.of("CP", metadata), Map.of(), List.of(),
                    new SnapshotProvenance(99L, "catalogue-frozen-fixture", "repository"));
        }
    }
}
