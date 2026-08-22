package com.taxonomy.templates;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Converts between a real DOTX file and its canonical, unzipped OOXML package tree.
 *
 * <p>The package tree is the source of truth stored in Git. ZIP metadata is deliberately
 * normalized so the same Git tree always produces the same downloadable DOTX bytes.</p>
 */
@Component
public final class OoxmlTemplatePackageCodec {

    public static final String DOTX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.template";
    public static final String DOTX_MAIN_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.template.main+xml";

    static final int MAX_ARCHIVE_BYTES = 25 * 1024 * 1024;
    static final int MAX_PART_BYTES = 25 * 1024 * 1024;
    static final long MAX_UNCOMPRESSED_BYTES = 100L * 1024 * 1024;
    static final int MAX_PARTS = 2_048;

    private static final Set<String> REQUIRED_PARTS = Set.of(
            "[Content_Types].xml",
            "_rels/.rels",
            "word/document.xml");

    /**
     * Import and validate one complete DOTX file.
     */
    public PackageData unpack(InputStream input) throws IOException {
        byte[] archive = readBounded(input, MAX_ARCHIVE_BYTES, "DOTX archive");
        TreeMap<String, byte[]> parts = new TreeMap<>();
        long totalBytes = 0;

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }
                if (parts.size() >= MAX_PARTS) {
                    throw invalid("DOTX contains more than " + MAX_PARTS + " package parts");
                }

                String path = validatePartPath(entry.getName());
                byte[] content = readBounded(zip, MAX_PART_BYTES, "OOXML part " + path);
                totalBytes += content.length;
                if (totalBytes > MAX_UNCOMPRESSED_BYTES) {
                    throw invalid("DOTX expands beyond the permitted package size");
                }
                if (parts.putIfAbsent(path, content) != null) {
                    throw invalid("DOTX contains a duplicate package part: " + path);
                }
                zip.closeEntry();
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (IOException exception) {
            throw invalid("DOTX is not a readable ZIP/OOXML package", exception);
        }

        validatePackage(parts);
        return new PackageData(parts, totalBytes, packageSha256(parts));
    }

    /**
     * Build a deterministic, valid DOTX ZIP projection from a canonical package tree.
     */
    public byte[] pack(Map<String, byte[]> packageParts) throws IOException {
        TreeMap<String, byte[]> parts = defensiveSortedCopy(packageParts);
        validatePackage(parts);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, byte[]> part : parts.entrySet()) {
                ZipEntry entry = new ZipEntry(part.getKey());
                entry.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0));
                entry.setComment(null);
                entry.setExtra(null);
                zip.putNextEntry(entry);
                zip.write(part.getValue());
                zip.closeEntry();
            }
        }
        if (output.size() > MAX_ARCHIVE_BYTES) {
            throw invalid("Generated DOTX exceeds the permitted archive size");
        }
        return output.toByteArray();
    }

    /**
     * Validate a package tree without changing any original OOXML bytes.
     */
    public void validatePackage(Map<String, byte[]> packageParts) {
        if (packageParts == null || packageParts.isEmpty()) {
            throw invalid("DOTX package is empty");
        }
        if (packageParts.size() > MAX_PARTS) {
            throw invalid("DOTX contains more than " + MAX_PARTS + " package parts");
        }

        long total = 0;
        for (Map.Entry<String, byte[]> entry : packageParts.entrySet()) {
            String path = validatePartPath(entry.getKey());
            byte[] content = entry.getValue();
            if (content == null) {
                throw invalid("OOXML part has no content: " + path);
            }
            if (content.length > MAX_PART_BYTES) {
                throw invalid("OOXML part is too large: " + path);
            }
            total += content.length;
            if (total > MAX_UNCOMPRESSED_BYTES) {
                throw invalid("DOTX expands beyond the permitted package size");
            }
            rejectUnsafePart(path);
        }

        for (String required : REQUIRED_PARTS) {
            if (!packageParts.containsKey(required)) {
                throw invalid("DOTX is missing required OOXML part: " + required);
            }
        }

        validateContentTypes(packageParts.get("[Content_Types].xml"));
        packageParts.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith(".rels"))
                .forEach(entry -> validateRelationships(entry.getKey(), entry.getValue()));
    }

    private static TreeMap<String, byte[]> defensiveSortedCopy(Map<String, byte[]> input) {
        if (input == null) {
            throw invalid("DOTX package must not be null");
        }
        TreeMap<String, byte[]> result = new TreeMap<>();
        input.forEach((path, bytes) -> result.put(path,
                bytes == null ? null : Arrays.copyOf(bytes, bytes.length)));
        return result;
    }

    private static String validatePartPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw invalid("OOXML package part has an empty path");
        }
        if (rawPath.startsWith("/") || rawPath.endsWith("/")
                || rawPath.contains("\\") || rawPath.contains("\0")) {
            throw invalid("Unsafe OOXML package path: " + rawPath);
        }
        String[] segments = rawPath.split("/", -1);
        for (String segment : segments) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)
                    || segment.indexOf(':') >= 0) {
                throw invalid("Unsafe OOXML package path: " + rawPath);
            }
        }
        return rawPath;
    }

    private static void rejectUnsafePart(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith("vbaproject.bin")
                || lower.startsWith("word/activex/")
                || lower.startsWith("word/embeddings/")
                || lower.startsWith("customui/")
                || lower.startsWith("_xmlsignatures/")) {
            throw invalid("Unsupported active, embedded or signed OOXML part: " + path);
        }
    }

    private static void validateContentTypes(byte[] xml) {
        Document document = parseXml("[Content_Types].xml", xml);
        NodeList overrides = document.getElementsByTagNameNS("*", "Override");
        boolean templateMainPart = false;
        for (int index = 0; index < overrides.getLength(); index++) {
            Element element = (Element) overrides.item(index);
            if ("/word/document.xml".equals(element.getAttribute("PartName"))
                    && DOTX_MAIN_CONTENT_TYPE.equals(element.getAttribute("ContentType"))) {
                templateMainPart = true;
                break;
            }
        }
        if (!templateMainPart) {
            throw invalid("Package is not a macro-free Word DOTX template");
        }
    }

    private static void validateRelationships(String path, byte[] xml) {
        Document document = parseXml(path, xml);
        NodeList relationships = document.getElementsByTagNameNS("*", "Relationship");
        for (int index = 0; index < relationships.getLength(); index++) {
            Element relationship = (Element) relationships.item(index);
            if (!"External".equalsIgnoreCase(relationship.getAttribute("TargetMode"))) {
                continue;
            }
            String type = relationship.getAttribute("Type");
            if (type.endsWith("/hyperlink")) {
                continue;
            }
            throw invalid("External OOXML relationship is not permitted in " + path);
        }
    }

    private static Document parseXml(String path, byte[] content) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(content));
        } catch (ParserConfigurationException | SAXException | IOException exception) {
            throw invalid("Invalid or unsafe XML in OOXML part " + path, exception);
        }
    }

    private static byte[] readBounded(InputStream input, int maximum, String description)
            throws IOException {
        if (input == null) {
            throw invalid(description + " is missing");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 16_384));
        byte[] buffer = new byte[8_192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > maximum) {
                throw invalid(description + " exceeds " + maximum + " bytes");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    static String packageSha256(Map<String, byte[]> parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            new TreeMap<>(parts).forEach((path, content) -> {
                digest.update(path.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(content);
                digest.update((byte) 0);
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private static IllegalArgumentException invalid(String message, Exception cause) {
        return new IllegalArgumentException(message, cause);
    }

    /**
     * One validated, unzipped OOXML package.
     */
    public record PackageData(
            Map<String, byte[]> parts,
            long uncompressedSize,
            String sha256) {

        public PackageData {
            LinkedHashMap<String, byte[]> copy = new LinkedHashMap<>();
            new TreeMap<>(parts).forEach((path, value) ->
                    copy.put(path, Arrays.copyOf(value, value.length)));
            parts = Map.copyOf(copy);
        }
    }
}
