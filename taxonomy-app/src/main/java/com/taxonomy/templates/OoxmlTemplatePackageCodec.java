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
import java.net.URI;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
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
    private static final String OFFICE_DOCUMENT_RELATIONSHIP_SUFFIX =
            "/officeDocument";

    /**
     * Import and validate one complete DOTX file.
     */
    public PackageData unpack(InputStream input) throws IOException {
        byte[] archive = readBounded(input, MAX_ARCHIVE_BYTES, "DOTX archive");
        TreeMap<String, byte[]> parts = new TreeMap<>();
        Set<String> caseInsensitivePaths = new HashSet<>();
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
                String foldedPath = path.toLowerCase(Locale.ROOT);
                if (!caseInsensitivePaths.add(foldedPath)) {
                    throw invalid("DOTX contains duplicate or case-colliding package part: "
                            + path);
                }
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
        Set<String> caseInsensitivePaths = new HashSet<>();
        for (Map.Entry<String, byte[]> entry : packageParts.entrySet()) {
            String path = validatePartPath(entry.getKey());
            if (!caseInsensitivePaths.add(path.toLowerCase(Locale.ROOT))) {
                throw invalid("DOTX contains duplicate or case-colliding package part: " + path);
            }
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
                .filter(entry -> isXmlPart(entry.getKey()))
                .forEach(entry -> parseXml(entry.getKey(), entry.getValue()));
        validateRelationships(packageParts);
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

    /** Shared package-relative path contract for imports and read-side entry points. */
    static String validatePartPath(String rawPath) {
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
        String lower = path.toLowerCase(Locale.ROOT);
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
            String contentType = element.getAttribute("ContentType");
            rejectActiveContentType(contentType);
            if ("/word/document.xml".equals(element.getAttribute("PartName"))
                    && DOTX_MAIN_CONTENT_TYPE.equals(contentType)) {
                templateMainPart = true;
            }
        }
        NodeList defaults = document.getElementsByTagNameNS("*", "Default");
        for (int index = 0; index < defaults.getLength(); index++) {
            rejectActiveContentType(((Element) defaults.item(index))
                    .getAttribute("ContentType"));
        }
        if (!templateMainPart) {
            throw invalid("Package is not a macro-free Word DOTX template");
        }
    }

    private static void rejectActiveContentType(String contentType) {
        String lower = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (lower.contains("macroenabled")
                || lower.contains("vbaproject")
                || lower.contains("activex")
                || lower.contains("oleobject")) {
            throw invalid("Unsupported active OOXML content type: " + contentType);
        }
    }

    private static void validateRelationships(Map<String, byte[]> packageParts) {
        int officeDocumentRelationships = 0;
        for (Map.Entry<String, byte[]> entry : packageParts.entrySet()) {
            String relationshipPath = entry.getKey();
            if (!relationshipPath.endsWith(".rels")) {
                continue;
            }
            String sourcePart = relationshipSourcePart(relationshipPath);
            if (!sourcePart.isEmpty() && !packageParts.containsKey(sourcePart)) {
                throw invalid("Relationship part has no source OOXML part: "
                        + relationshipPath);
            }

            Document document = parseXml(relationshipPath, entry.getValue());
            NodeList relationships = document.getElementsByTagNameNS("*", "Relationship");
            for (int index = 0; index < relationships.getLength(); index++) {
                Element relationship = (Element) relationships.item(index);
                String target = relationship.getAttribute("Target");
                String type = relationship.getAttribute("Type");
                boolean external = "External".equalsIgnoreCase(
                        relationship.getAttribute("TargetMode"));
                if (external) {
                    if (type.endsWith("/hyperlink")) {
                        continue;
                    }
                    throw invalid("External OOXML relationship is not permitted in "
                            + relationshipPath);
                }

                String resolvedTarget = resolveRelationshipTarget(
                        relationshipPath, sourcePart, target);
                if (!packageParts.containsKey(resolvedTarget)) {
                    throw invalid("OOXML relationship in " + relationshipPath
                            + " targets missing package part " + resolvedTarget);
                }
                if ("_rels/.rels".equals(relationshipPath)
                        && type.endsWith(OFFICE_DOCUMENT_RELATIONSHIP_SUFFIX)) {
                    officeDocumentRelationships++;
                    if (!"word/document.xml".equals(resolvedTarget)) {
                        throw invalid("Root officeDocument relationship must target "
                                + "word/document.xml");
                    }
                }
            }
        }
        if (officeDocumentRelationships != 1) {
            throw invalid("Package must contain exactly one internal root officeDocument relationship");
        }
    }

    private static String relationshipSourcePart(String relationshipPath) {
        if ("_rels/.rels".equals(relationshipPath)) {
            return "";
        }
        int marker = relationshipPath.lastIndexOf("_rels/");
        if (marker < 0) {
            throw invalid("Invalid OOXML relationship part path: " + relationshipPath);
        }
        String prefix = relationshipPath.substring(0, marker);
        String relationshipFile = relationshipPath.substring(marker + "_rels/".length());
        if (!relationshipFile.endsWith(".rels")
                || relationshipFile.length() <= ".rels".length()) {
            throw invalid("Invalid OOXML relationship part path: " + relationshipPath);
        }
        return prefix + relationshipFile.substring(
                0, relationshipFile.length() - ".rels".length());
    }

    private static String resolveRelationshipTarget(
            String relationshipPath,
            String sourcePart,
            String target) {
        if (target == null || target.isBlank() || target.contains("\\")
                || target.contains("\0")) {
            throw invalid("Invalid OOXML relationship target in " + relationshipPath);
        }
        try {
            URI uri = URI.create(target);
            if (uri.isAbsolute() || uri.getAuthority() != null) {
                throw invalid("Internal OOXML relationship target must be package-relative in "
                        + relationshipPath);
            }
            String targetPath = uri.getPath();
            if (targetPath == null || targetPath.isBlank()) {
                throw invalid("Invalid OOXML relationship target in " + relationshipPath);
            }
            while (targetPath.startsWith("/")) {
                targetPath = targetPath.substring(1);
            }
            String baseDirectory = "";
            int slash = sourcePart.lastIndexOf('/');
            if (slash >= 0) {
                baseDirectory = sourcePart.substring(0, slash + 1);
            }
            Path resolved = Path.of(baseDirectory)
                    .resolve(targetPath)
                    .normalize();
            if (resolved.isAbsolute() || resolved.startsWith("..")) {
                throw invalid("OOXML relationship escapes the package root in "
                        + relationshipPath);
            }
            String normalized = resolved.toString().replace('\\', '/');
            return validatePartPath(normalized);
        } catch (IllegalArgumentException exception) {
            if (exception.getMessage() != null
                    && exception.getMessage().startsWith("OOXML")) {
                throw exception;
            }
            throw invalid("Invalid OOXML relationship target in "
                    + relationshipPath, exception);
        }
    }

    private static boolean isXmlPart(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".xml") || lower.endsWith(".rels");
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
