package com.taxonomy.reporting.render.decision;

import org.w3c.dom.Element;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.transform.Transformer;
import java.net.URI;
import java.util.function.Predicate;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;

/** Standalone Java source launcher and drawing converter for locally generated Taxonomy VSDX exports. */
public final class VisioDrawingPdf {
    private static final int MIB = 1024 * 1024;
    private static final int MAX_PAGES = 160; // 32 native diagram pages and 128 evidence pages
    private static final int MAX_INPUT = 32 * MIB;
    static final int MAX_OUTPUT = 64 * MIB;
    private static final String INDEX = "visio/pages/pages.xml";
    private static final String PAGE_RELS = "visio/pages/_rels/pages.xml.rels";
    private static final String TYPES = "[Content_Types].xml";
    private static final String OPC_RELS = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String OFFICE_RELS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String VISIO_RELS = "http://schemas.microsoft.com/visio/2010/relationships/";
    private static final String VISIO = "http://schemas.microsoft.com/office/visio/2012/main";
    private VisioDrawingPdf() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: java VisioDrawingPdf.java input.vsdx output.pdf");
        Path input = Path.of(args[0]), target = Path.of(args[1]).toAbsolutePath();
        if (Files.exists(target)) throw new FileAlreadyExistsException(target.toString());
        if (Files.size(input) > MAX_INPUT) throw new IOException("VSDX exceeds 32 MiB");
        // Stage beside the requested output so a conversion failure leaves existing files intact.
        Path staged = Files.createTempFile(target.getParent(), ".visio-", ".pdf");
        try {
            Files.write(staged, convert(input, "soffice", "pdfunite", "pdfinfo"));
            Files.move(staged, target);
        } finally { Files.deleteIfExists(staged); }
    }

    static byte[] convert(Path input, String soffice, String pdfunite, String pdfinfo) throws IOException, InterruptedException {
        Path work = Files.createTempDirectory("taxonomy-visio-pdf-");
        try {
            var pages = pagePackages(input,work);
            var render = new ArrayList<>(List.of(soffice, "-env:UserInstallation=" + work.resolve("profile").toUri(),
                    "--headless", "--norestore", "--convert-to", "pdf:draw_pdf_Export", "--outdir", work.toString()));
            pages.forEach(page -> render.add(page.toString()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            command(render, work, deadline);
            var merge = new ArrayList<>(List.of(pdfunite));
            long bytes = 0;
            for (Path page : pages) {
                Path pdf = page.resolveSibling(page.getFileName().toString().replace(".vsdx", ".pdf"));
                long size = Files.size(pdf);
                bytes += size;
                if (size == 0 || bytes > MAX_OUTPUT) throw new IOException("Empty or oversized rendered drawings");
                merge.add(pdf.toString());
            }
            Path output = work.resolve("combined.pdf");
            merge.add(output.toString());
            command(merge, work, deadline);
            if (Files.size(output) > MAX_OUTPUT) throw new IOException("PDF exceeds 64 MiB");
            Path info = command(List.of(pdfinfo, output.toString()), work, deadline);
            String count = Files.readAllLines(info).stream().filter(line -> line.startsWith("Pages:"))
                    .map(line -> line.substring("Pages:".length()).strip()).findFirst().orElse("");
            if (!Integer.toString(pages.size()).equals(count)) throw new IOException("PDF page count differs from VSDX");
            return Files.readAllBytes(output);
        } finally {
            try (var files = Files.walk(work)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    static List<Path> pagePackages(Path input, Path work) throws IOException {
        if (Files.size(input) > MAX_INPUT) throw new IOException("VSDX exceeds 32 MiB");
        var parts = new LinkedHashMap<String,byte[]>();
        long total = 0;
        try (var zip = new ZipFile(input.toFile())) {
            if (zip.size() > 2048) throw new IOException("VSDX has too many package parts");
            for (var entries = zip.entries(); entries.hasMoreElements();) {
                var entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                long size = entry.getSize();
                if (size < 0 || (total += size) > 64L * MIB) throw new IOException("VSDX exceeds 64 MiB expanded");
                try (var stream = zip.getInputStream(entry)) {
                    byte[] data = stream.readNBytes((int) size + 1);
                    if (data.length != size || parts.putIfAbsent(entry.getName(),data) != null)
                        throw new IOException("Invalid VSDX package entry");
                }
            }
        }
        try {
            var factory = DocumentBuilderFactory.newNSInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            var parser = factory.newDocumentBuilder();
            var writer = TransformerFactory.newDefaultInstance().newTransformer();
            Element index = root(parts, INDEX, parser, VISIO, "Pages");
            var pages = children(index).stream().filter(e -> VISIO.equals(e.getNamespaceURI()) && "Page".equals(e.getLocalName())).toList();
            if (pages.isEmpty() || pages.size() > MAX_PAGES) throw new IOException("Expected 1–160 Visio drawings");
            Element pageRels = root(parts, PAGE_RELS, parser, OPC_RELS, "Relationships");
            Element types = root(parts, TYPES, parser, "http://schemas.openxmlformats.org/package/2006/content-types", "Types");

            // Follow the rendering graph. Package metadata and the source window selection describe the whole handoff.
            var graph = new HashMap<String,Map<String,String>>();
            for (String name : parts.keySet()) {
                if (!name.endsWith(".rels")) continue;
                Element relations = name.equals(PAGE_RELS) ? pageRels : root(parts, name, parser, OPC_RELS, "Relationships");
                var targets = new LinkedHashMap<String,String>();
                var links = children(relations);
                for (Element relation : links) {
                    if (!OPC_RELS.equals(relation.getNamespaceURI()) || !"Relationship".equals(relation.getLocalName()))
                        throw new IOException("Invalid OPC relationship");
                    String type = relation.getAttribute("Type");
                    if ((name.equals("_rels/.rels") && !type.equals(VISIO_RELS + "document")) || type.equals(VISIO_RELS + "windows"))
                        continue;
                    if (name.equals(PAGE_RELS) && !type.equals(VISIO_RELS + "page")) throw new IOException("Invalid Visio page relationship");
                    String id = relation.getAttribute("Id");
                    String target = targetPart(name, relation);
                    if (id.isEmpty() || !parts.containsKey(target) || targets.putIfAbsent(id,target) != null)
                        throw new IOException("Missing or duplicate OPC relationship target");
                }
                graph.put(name,targets);
                if (targets.size() != links.size()) {
                    parts.put(name, filtered(writer,relations,e -> targets.containsKey(e.getAttribute("Id"))));
                }
            }
            var shared = new LinkedHashSet<String>();
            include("", shared, graph);
            if (!shared.contains(INDEX) || !shared.contains(PAGE_RELS)) throw new IOException("Missing Visio drawing relationship");
            var selectedTargets = new HashSet<String>();
            var files = new ArrayList<Path>();
            long packageBytes = 0;
            for (Element page : pages) {
                var links = children(page).stream().filter(e -> VISIO.equals(e.getNamespaceURI()) && "Rel".equals(e.getLocalName())).toList();
                if (links.size() != 1) throw new IOException("Expected one relationship for each Visio page");
                String id = links.getFirst().getAttributeNS(OFFICE_RELS,"id");
                String target = graph.get(PAGE_RELS).get(id);
                if (target == null || !selectedTargets.add(target)) throw new IOException("Missing or repeated Visio page target");
                var included = new LinkedHashSet<>(shared);
                include(target, included, graph);
                var overrides = new HashMap<String,byte[]>();
                overrides.put(INDEX, filtered(writer, index, e -> e == page));
                overrides.put(PAGE_RELS, filtered(writer, pageRels, e -> id.equals(e.getAttribute("Id"))));
                overrides.put(TYPES, filtered(writer, types, entry -> {
                    String part = entry.getAttribute("PartName");
                    return !"Override".equals(entry.getLocalName()) || (part.startsWith("/") && included.contains(part.substring(1)));
                }));
                included.add(TYPES);
                Path path = work.resolve("drawing-" + files.size() + ".vsdx");
                try (var archive = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(path,StandardOpenOption.CREATE_NEW)))) {
                    for (String name : included) {
                        archive.putNextEntry(new ZipEntry(name));
                        archive.write(overrides.getOrDefault(name, parts.get(name)));
                        archive.closeEntry();
                    }
                }
                packageBytes += Files.size(path);
                if (packageBytes > 512L * MIB) throw new IOException("Drawing packages exceed 512 MiB");
                files.add(path);
            }
            return files;
        } catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException | javax.xml.transform.TransformerException failure) {
            throw new IOException("Invalid Visio drawing package",failure);
        }
    }

    private static Element root(Map<String,byte[]> parts, String name, DocumentBuilder parser, String namespace, String localName)
            throws IOException, org.xml.sax.SAXException {
        byte[] bytes = parts.get(name);
        if (bytes == null) throw new IOException("Missing OPC part: " + name);
        Element element = parser.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
        if (!namespace.equals(element.getNamespaceURI()) || !localName.equals(element.getLocalName()))
            throw new IOException("Invalid OPC part: " + name);
        return element;
    }

    private static List<Element> children(Element parent) {
        var elements = new ArrayList<Element>();
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element element) elements.add(element);
        return elements;
    }

    private static byte[] filtered(Transformer writer, Element source, Predicate<Element> keep)
            throws javax.xml.transform.TransformerException {
        var result = (Element)source.cloneNode(false);
        for (Element child : children(source)) if (keep.test(child)) result.appendChild(child.cloneNode(true));
        var output = new ByteArrayOutputStream();
        writer.transform(new DOMSource(result), new StreamResult(output));
        return output.toByteArray();
    }

    private static String targetPart(String relationships, Element link) throws IOException {
        String target = link.getAttribute("Target");
        if (target.isEmpty() || "External".equals(link.getAttribute("TargetMode"))) throw new IOException("Expected an internal OPC target");
        int separator = relationships.lastIndexOf("_rels/");
        if (separator < 0) throw new IOException("Invalid OPC relationship part");
        try {
            URI path = URI.create("/" + relationships.substring(0,separator)).resolve(target).normalize();
            if (path.isAbsolute() || path.getAuthority() != null || path.getQuery() != null || path.getFragment() != null
                    || !path.getPath().startsWith("/") || path.getPath().startsWith("/../")) throw new IOException("Invalid OPC target");
            return path.getPath().substring(1);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid OPC target", invalid);
        }
    }

    private static void include(String first, Set<String> included, Map<String,Map<String,String>> graph) {
        var pending = new ArrayDeque<String>();
        pending.add(first);
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            if (!name.isEmpty() && !included.add(name)) continue;
            int directory = name.lastIndexOf('/') + 1;
            String relationships = name.substring(0,directory) + "_rels/" + name.substring(directory) + ".rels";
            var targets = graph.get(relationships);
            if (targets == null) continue;
            included.add(relationships);
            if (!name.equals(INDEX)) pending.addAll(targets.values());
        }
    }

    private static Path command(List<String> args, Path work, long deadline) throws IOException, InterruptedException {
        Path log = work.resolve("command.log");
        var builder = new ProcessBuilder(args).directory(work.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("LC_ALL","C");
        Process process = builder.start();
        try {
            if (!process.waitFor(Math.max(0,deadline - System.nanoTime()),TimeUnit.NANOSECONDS))
                throw new IOException("Drawing PDF conversion timed out");
            if (process.exitValue() != 0) throw new IOException("Drawing PDF conversion failed in " + Path.of(args.getFirst()).getFileName());
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5,TimeUnit.SECONDS);
            }
        }
        return log;
    }
}
