package com.taxonomy.tooling;

import org.w3c.dom.Element;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Independent LibreOffice/Poppler checks of the actual civilian acceptance exports. */
final class CivilianDocumentQa {
    private static final String POPPLER_DOCTYPE = "<!DOCTYPE html PUBLIC "
            + "\"-//W3C//DTD XHTML 1.0 Transitional//EN\" "
            + "\"http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd\">";

    private CivilianDocumentQa() { }

    static int run(String[] rawArguments, Path workingDirectory, PrintStream output, PrintStream error) {
        try {
            var arguments = TaxonomyTooling.Arguments.parse(rawArguments);
            Path root = workingDirectory.resolve(arguments.required("artifacts")).toAbsolutePath().normalize();
            String soffice = arguments.optionalOrDefault("soffice", "soffice");
            output.println(FlatJson.pretty(inspect(root, soffice)));
            return 0;
        } catch (IOException | IllegalArgumentException failure) {
            error.println("::error::Civilian document QA failed: " + failure.getMessage());
            return 1;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            error.println("::error::Civilian document QA interrupted");
            return 1;
        }
    }

    static Map<String, Object> inspect(Path root, String soffice) throws IOException, InterruptedException {
        Path output = Files.createDirectories(root.resolve("document-qa"));
        Files.deleteIfExists(output.resolve("quality.json"));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("renderer", command(soffice, "--version").strip());
        Map<String, Object> documents = new LinkedHashMap<>();
        report.put("documents", documents);
        var decision = FlatJson.parseObject(Files.readString(root.resolve("decision.json")));
        var architecture = FlatJson.parseObject(Files.readString(root.resolve("architecture.json")));
        String wordGraphHash=null;
        for (String name : List.of("decision.docx", "report.docx", "architecture.vsdx")) {
            boolean word = name.endsWith(".docx");
            if(word)wordGraphHash=checkFrozenWordSource(name,unzip(root.resolve(name)),architecture,wordGraphHash);
            String stem = name.substring(0, name.lastIndexOf('.'));
            Path pdf = output.resolve(stem + ".pdf");
            Path temporary = Files.createTempDirectory("civilian-lo-");
            try {
                Path rendered = Files.createDirectory(temporary.resolve("output"));
                command(soffice, "-env:UserInstallation=" + temporary.resolve("profile").toUri(),
                        "--headless", "--convert-to", "pdf", "--outdir", rendered.toString(),
                        root.resolve(name).toString());
                Path converted = rendered.resolve(pdf.getFileName());
                require(Files.isRegularFile(converted), "LibreOffice did not produce " + pdf.getFileName());
                Files.copy(converted, pdf, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                deleteDirectory(temporary);
            }
            String text = command("pdftotext", "-raw", pdf.toString(), "-");
            String bbox = command("pdftotext", "-bbox", pdf.toString(), "-");
            List<String> expected = new ArrayList<>(name.equals("decision.docx") ? expectedDecisionText(decision)
                    : name.equals("report.docx") ? List.of(text(architecture,"requirementText"),text(architecture,"snapshotId"))
                    : expectedDiagramText(architecture));
            if(word) {
                expected.add(wordGraphHash);
                for(String collection:List.of("nodes","edges"))for(Object value:array(object(architecture.get("diagram")).get(collection)))expected.add(text(object(value),"id"));
            }
            var document = checkText(name, bbox, text, expected, word);
            try (var files = Files.newDirectoryStream(output, stem + "-*.png")) {
                for (Path old : files) Files.delete(old);
            }
            command("pdftoppm", "-scale-to", "1400", "-png", pdf.toString(), output.resolve(stem).toString());
            document.put("sourceSha256", sha256(root.resolve(name)));
            if(word){document.put("snapshotId",text(architecture,"snapshotId"));document.put("graphSha256",wordGraphHash);}
            document.put("renderedPdfSha256", sha256(pdf));
            documents.put(name, document);
        }
        Files.writeString(output.resolve("quality.json"), FlatJson.pretty(report) + "\n");
        return report;
    }

    static Map<String, Object> checkText(String name, String bbox, String rawText,
                                         List<String> expected, boolean word) throws IOException {
        // Poppler emits this fixed DOCTYPE. Strip it before our hardened parser;
        // never resolve the external XHTML DTD or accept arbitrary declarations.
        var xml = XmlSupport.parse(new ByteArrayInputStream(
                bbox.replace(POPPLER_DOCTYPE, "").getBytes(StandardCharsets.UTF_8)));
        var pages = XmlSupport.descendants(xml.getDocumentElement(), "page");
        require(!pages.isEmpty(), name + ": no rendered pages");
        List<Integer> empty = new ArrayList<>();
        StringBuilder bodyText = new StringBuilder();
        for (int index = 0; index < pages.size(); index++) {
            Element page = pages.get(index);
            // Running furniture is at 24pt/796pt; a continued body may start at 51pt.
            double top = word ? 45 : 0;
            double bottom = Double.parseDouble(page.getAttribute("height")) - (word ? 55 : 0);
            List<String> body = XmlSupport.descendants(page, "word").stream()
                    .filter(w -> Double.parseDouble(w.getAttribute("yMin")) >= top
                            && Double.parseDouble(w.getAttribute("yMax")) < bottom)
                    .map(Element::getTextContent).filter(t -> !t.isBlank()).toList();
            if (body.isEmpty()) empty.add(index + 1);
            if (word) {
                String pageText = normalized(String.join(" ", body));
                require(!pageText.endsWith(normalized("Comparative rationale"))
                                && !pageText.endsWith(normalized("Decision result")),
                        name + ": orphan rationale heading on page " + (index + 1));
            }
            bodyText.append(String.join(" ", body)).append(' ');
        }
        require(empty.isEmpty(), name + ": empty page bodies " + empty);
        // Complete frozen fixture: decision71 pages (48 chapter pages), architecture10.
        // Allow3/2 pages for renderer variance; content, empty-body and orphan gates remain.
        int pageLimit = name.equals("decision.docx") ? 74 : 12;
        require(!word || pages.size() <= pageLimit, name + ": report grew to " + pages.size() + " pages (limit " + pageLimit + ")");
        // Join body paragraphs across pages. Draw's coordinate order interleaves
        // crossing edges and labels, so its label checks use Poppler's raw order.
        String actual = normalized(word ? bodyText.toString() : rawText);
        require(!expected.isEmpty(), name + ": no expected source content");
        for (String phrase : expected) {
            require(!phrase.isBlank() && actual.contains(normalized(phrase)),
                    name + ": missing text " + phrase.substring(0, Math.min(120, phrase.length())));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pages", pages.size());
        result.put("emptyBodyPages", empty);
        result.put("contentAssertions", expected.size());
        return result;
    }

    static String checkFrozenWordSource(String name,Map<String,byte[]> parts,Map<String,Object> architecture,
                                         String previousHash) throws IOException {
        require(parts.containsKey("docProps/custom.xml") && parts.containsKey("word/document.xml"),name+": missing Word source properties");
        var properties=XmlSupport.parse(new ByteArrayInputStream(parts.get("docProps/custom.xml")));
        var values=new LinkedHashMap<String,String>();
        for(var property:XmlSupport.descendants(properties.getDocumentElement(),"property"))values.put(property.getAttribute("name"),property.getTextContent());
        require(text(architecture,"snapshotId").equals(values.get("taxonomy.snapshot.id")),name+": frozen snapshot identity mismatch");
        String hash=values.get("taxonomy.graph.sha256");
        require(hash!=null && hash.matches("[a-f0-9]{64}") && (previousHash==null || previousHash.equals(hash)),name+": frozen graph hash mismatch");
        var body=XmlSupport.parse(new ByteArrayInputStream(parts.get("word/document.xml"))).getDocumentElement();
        String text=body.getTextContent();
        for(String collection:List.of("nodes","edges"))for(Object value:array(object(architecture.get("diagram")).get(collection))) {
            String id=text(object(value),"id");require(text.contains(id),name+": missing graph evidence "+id);
        }
        return hash;
    }

    private static Map<String,byte[]> unzip(Path path) throws IOException {
        var parts=new LinkedHashMap<String,byte[]>();
        try(var input=new java.util.zip.ZipInputStream(Files.newInputStream(path))) {
            for(var entry=input.getNextEntry();entry!=null;entry=input.getNextEntry()) {
                if(entry.getName().equals("word/document.xml") || entry.getName().equals("docProps/custom.xml"))parts.put(entry.getName(),input.readAllBytes());
            }
        }
        return parts;
    }

    private static List<String> expectedDecisionText(Map<String, Object> decision) {
        List<String> expected = new ArrayList<>();
        expected.add(text(decision, "requirement"));
        expected.add(text(object(decision.get("metadata")), "analysisSnapshotId"));
        for (Object value : array(decision.get("chapters"))) {
            var chapter = object(value);
            for (String key : List.of("parentCode", "decisionSummary", "comparativeRationale")) {
                expected.add(text(chapter, key));
            }
            for (Object child : array(chapter.get("children"))) {
                Object reason = object(child).get("reason");
                if (reason instanceof String phrase && !phrase.isBlank()) expected.add(phrase);
            }
        }
        return expected;
    }

    private static List<String> expectedDiagramText(Map<String, Object> architecture) {
        List<String> expected = new ArrayList<>();
        for (Object value : array(object(architecture.get("diagram")).get("nodes"))) {
            var node = object(value);
            if (Boolean.FALSE.equals(node.get("container"))) expected.add(text(node, "label"));
        }
        return expected;
    }

    private static Map<?, ?> object(Object value) {
        if (value instanceof Map<?, ?> object) return object;
        throw new IllegalArgumentException("Expected a JSON object");
    }

    private static List<?> array(Object value) {
        if (value instanceof List<?> array) return array;
        throw new IllegalArgumentException("Expected a JSON array");
    }

    private static String text(Map<?, ?> object, String key) {
        if (object.get(key) instanceof String text) return text;
        throw new IllegalArgumentException("Missing text field " + key);
    }

    private static String normalized(String text) {
        return text.replaceAll("(?U)\\W+", "").toLowerCase(Locale.ROOT);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String command(String... arguments) throws IOException, InterruptedException {
        Path stdout = Files.createTempFile("civilian-command-", ".out");
        Path stderr = Files.createTempFile("civilian-command-", ".err");
        Process process = null;
        try {
            process = new ProcessBuilder(arguments).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
            require(process.waitFor(120, TimeUnit.SECONDS), arguments[0] + " timed out");
            require(process.exitValue() == 0, arguments[0] + " failed (" + process.exitValue() + "): "
                    + Files.readString(stderr) + " " + Files.readString(stdout));
            return Files.readString(stdout);
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            Files.deleteIfExists(stdout);
            Files.deleteIfExists(stderr);
        }
    }

    private static void deleteDirectory(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
