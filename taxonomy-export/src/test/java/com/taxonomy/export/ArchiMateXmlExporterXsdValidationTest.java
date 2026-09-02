package com.taxonomy.export;

import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramLayout;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import org.junit.jupiter.api.Test;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ArchiMateXmlExporterXsdValidationTest {

    private static final String RESOURCE_ROOT = "/archimate-3.1/";
    private static final String UPSTREAM_COMMIT =
            "5cb1c0e2053a8e072ea8d29e47338555fec9483f";
    private static final Set<String> SCHEMA_FILES = Set.of(
            "archimate3_Diagram.xsd",
            "archimate3_View.xsd",
            "archimate3_Model.xsd",
            "dc.xsd",
            "xml.xsd");

    private final ArchiMateDiagramService diagramService = new ArchiMateDiagramService();
    private final ArchiMateXmlExporter exporter = new ArchiMateXmlExporter();

    @Test
    void representativeExportValidatesAgainstPinnedArchiMate31Schema() throws Exception {
        byte[] xml = exporter.export(diagramService.convert(representativeDiagram()));

        Schema schema = loadOfflineSchema();
        schema.newValidator().validate(new StreamSource(new ByteArrayInputStream(xml)));
    }

    @Test
    void pinnedSchemaResourcesRetainRecordedBytesLicenceAndOrigin() throws Exception {
        Map<String, String> expectedDigests = readExpectedDigests();

        assertThat(expectedDigests.keySet()).containsExactlyInAnyOrderElementsOf(SCHEMA_FILES);
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        for (String fileName : SCHEMA_FILES) {
            try (InputStream input = resource(fileName).openStream()) {
                String actual = HexFormat.of().formatHex(
                        sha256.digest(input.readAllBytes()));
                assertThat(actual)
                        .as("SHA-256 of %s", fileName)
                        .isEqualTo(expectedDigests.get(fileName));
            }
            sha256.reset();
        }

        assertThat(readUtf8("ORIGIN.md"))
                .contains("Source repository: `archimatetool/archi`")
                .contains("Source commit: `" + UPSTREAM_COMMIT + "`");
        assertThat(readUtf8("LICENSE.archi.txt"))
                .startsWith("The MIT License (MIT)");
    }

    private Schema loadOfflineSchema() throws Exception {
        SchemaFactory factory = SchemaFactory.newInstance(
                XMLConstants.W3C_XML_SCHEMA_NS_URI);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setResourceResolver(new PinnedSchemaResolver());

        URL diagramSchema = resource("archimate3_Diagram.xsd");
        try (InputStream input = diagramSchema.openStream()) {
            StreamSource source = new StreamSource(input);
            source.setSystemId(diagramSchema.toExternalForm());
            return factory.newSchema(source);
        }
    }

    private Map<String, String> readExpectedDigests() throws IOException {
        Map<String, String> digests = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                resource("SHA256SUMS").openStream(), StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.trim().split("\\s+", 2);
                if (parts.length != 2) {
                    throw new IOException("Malformed SHA256SUMS line: " + line);
                }
                digests.put(parts[1], parts[0]);
            }
        }
        return digests;
    }

    private String readUtf8(String fileName) throws IOException {
        try (InputStream input = resource(fileName).openStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private URL resource(String fileName) {
        return Objects.requireNonNull(
                getClass().getResource(RESOURCE_ROOT + fileName),
                () -> "Missing test resource " + RESOURCE_ROOT + fileName);
    }

    private static DiagramModel representativeDiagram() {
        return new DiagramModel(
                "Pinned schema validation",
                List.of(
                        new DiagramNode(
                                "capability", "Secure communications", "Capabilities",
                                0.91, true, 1),
                        new DiagramNode(
                                "service", "Messaging service", "Core Services",
                                0.74, false, 3)),
                List.of(new DiagramEdge(
                        "supports", "capability", "service", "SUPPORTS", 0.74)),
                new DiagramLayout("LR", true));
    }

    private static final class PinnedSchemaResolver implements LSResourceResolver {

        @Override
        public LSInput resolveResource(
                String type,
                String namespaceUri,
                String publicId,
                String systemId,
                String baseUri) {
            if (systemId == null) {
                return null;
            }
            int slash = Math.max(systemId.lastIndexOf('/'), systemId.lastIndexOf('\\'));
            String fileName = slash >= 0 ? systemId.substring(slash + 1) : systemId;
            if (!SCHEMA_FILES.contains(fileName)) {
                throw new IllegalArgumentException(
                        "Unexpected external ArchiMate schema reference: " + systemId);
            }

            URL resource = Objects.requireNonNull(
                    ArchiMateXmlExporterXsdValidationTest.class.getResource(
                            RESOURCE_ROOT + fileName),
                    () -> "Missing pinned schema " + fileName);
            try {
                ResourceInput input = new ResourceInput();
                input.setPublicId(publicId);
                input.setSystemId(resource.toExternalForm());
                input.setBaseURI(resource.toExternalForm());
                input.setEncoding(StandardCharsets.UTF_8.name());
                input.setByteStream(resource.openStream());
                return input;
            } catch (IOException exception) {
                throw new UncheckedIOException(
                        "Cannot open pinned schema " + fileName, exception);
            }
        }
    }

    private static final class ResourceInput implements LSInput {

        private Reader characterStream;
        private InputStream byteStream;
        private String stringData;
        private String systemId;
        private String publicId;
        private String baseUri;
        private String encoding;
        private boolean certifiedText;

        @Override
        public Reader getCharacterStream() {
            return characterStream;
        }

        @Override
        public void setCharacterStream(Reader characterStream) {
            this.characterStream = characterStream;
        }

        @Override
        public InputStream getByteStream() {
            return byteStream;
        }

        @Override
        public void setByteStream(InputStream byteStream) {
            this.byteStream = byteStream;
        }

        @Override
        public String getStringData() {
            return stringData;
        }

        @Override
        public void setStringData(String stringData) {
            this.stringData = stringData;
        }

        @Override
        public String getSystemId() {
            return systemId;
        }

        @Override
        public void setSystemId(String systemId) {
            this.systemId = systemId;
        }

        @Override
        public String getPublicId() {
            return publicId;
        }

        @Override
        public void setPublicId(String publicId) {
            this.publicId = publicId;
        }

        @Override
        public String getBaseURI() {
            return baseUri;
        }

        @Override
        public void setBaseURI(String baseUri) {
            this.baseUri = baseUri;
        }

        @Override
        public String getEncoding() {
            return encoding;
        }

        @Override
        public void setEncoding(String encoding) {
            this.encoding = encoding;
        }

        @Override
        public boolean getCertifiedText() {
            return certifiedText;
        }

        @Override
        public void setCertifiedText(boolean certifiedText) {
            this.certifiedText = certifiedText;
        }
    }
}
