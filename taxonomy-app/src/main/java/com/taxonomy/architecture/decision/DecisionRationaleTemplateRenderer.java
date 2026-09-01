package com.taxonomy.architecture.decision;

import com.taxonomy.templates.DecisionRationaleTemplateContract;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateNotFoundException;
import com.taxonomy.templates.DocumentTemplateService;
import com.taxonomy.templates.DocumentTemplateService.TemplateFile;
import com.taxonomy.templates.OoxmlTemplatePackageCodec;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.xwpf.usermodel.BodyElementType;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Materializes the hierarchical decision report into the currently versioned DOTX template.
 *
 * <p>The existing renderer remains the single implementation of dynamic chapters, tables,
 * diagrams, and appendix semantics. This adapter opens the editable cover/header/footer
 * template, replaces stable tokens, removes the body marker, and invokes those proven
 * rendering stages through its package-level typed body writer.</p>
 */
@Component
public final class DecisionRationaleTemplateRenderer {

    private static final String DOCX_MAIN_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";
    private static final String TEMPLATE_ID_PROPERTY = "Taxonomy.Template.Id";
    private static final String TEMPLATE_COMMIT_PROPERTY = "Taxonomy.Template.Commit";
    private static final String TEMPLATE_SHA256_PROPERTY =
            "Taxonomy.Template.PackageSha256";

    private final DocumentTemplateService templates;
    private final DecisionRationaleTemplateContract contract;

    public DecisionRationaleTemplateRenderer(
            DocumentTemplateService templates,
            DecisionRationaleTemplateContract contract) {
        this.templates = Objects.requireNonNull(templates, "templates");
        this.contract = Objects.requireNonNull(contract, "contract");
    }

    public byte[] render(
            DecisionRationaleDocxRenderer delegate,
            DecisionRationaleReport report) {
        Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(report, "report");

        TemplateFile template;
        try {
            template = templates.downloadCurrentValidated(
                    DecisionRationaleTemplateContract.TEMPLATE_ID);
        } catch (TemplateNotFoundException exception) {
            throw unavailable(
                    "Required template is missing. Restart Taxonomy to seed the bundled "
                            + "default or upload a valid template with ID '"
                            + DecisionRationaleTemplateContract.TEMPLATE_ID + "'.",
                    exception);
        } catch (Exception exception) {
            throw unavailable("Required template could not be validated: "
                    + safeMessage(exception), exception);
        }

        DecisionReportLabels labels = new DecisionReportLabels(report.languageTag());
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(
                    convertDotxToDocx(template.content())));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            contract.validateDocument(document);
            replaceTokens(document, tokenValues(report, labels, template));
            removeBodyMarker(document);

            delegate.writeReportBody(document, report, labels);
            writeTemplateProvenanceProperties(document, template);

            document.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw unavailable(
                    "Could not materialize template commit " + template.commitId()
                            + ": " + safeMessage(exception),
                    exception);
        }
    }

    private static Map<String, String> tokenValues(
            DecisionRationaleReport report,
            DecisionReportLabels labels,
            TemplateFile template) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(DecisionRationaleTemplateContract.TITLE_TOKEN, value(report.title()));
        values.put("{{taxonomy.report.subtitle}}", labels.subtitle());
        values.put("{{taxonomy.report.status}}", statusLabel(report, labels));
        values.put(DecisionRationaleTemplateContract.REQUIREMENT_TOKEN,
                value(report.requirement()));
        values.put("{{taxonomy.report.generatedAt}}",
                formatInstant(report.metadata().generatedAt(),
                        report.metadata().reportTimeZone(),
                        labels.german()));
        values.put("{{taxonomy.report.generatedBy}}",
                value(report.metadata().generatedBy()));
        values.put("{{taxonomy.report.taxonomyVersion}}",
                value(report.metadata().taxonomyDataVersion()));
        values.put("{{taxonomy.report.applicationVersion}}",
                value(report.metadata().taxonomyApplicationVersion()));
        values.put("{{taxonomy.report.commit}}",
                value(report.metadata().taxonomyBuildCommit()));
        values.put("{{taxonomy.report.repository}}",
                value(report.metadata().repositoryId()));
        values.put("{{taxonomy.report.workspace}}",
                value(report.metadata().workspaceId()));
        values.put("{{taxonomy.report.branch}}",
                value(report.metadata().branch()));
        values.put("{{taxonomy.report.basedOnCommit}}",
                value(report.metadata().basedOnCommit()));
        values.put("{{taxonomy.report.analysisProvider}}",
                value(report.metadata().analysisProvider()));
        values.put("{{taxonomy.template.id}}",
                DecisionRationaleTemplateContract.TEMPLATE_ID);
        values.put("{{taxonomy.template.commit}}",
                abbreviate(template.commitId()));
        values.put("{{taxonomy.template.sha256}}",
                value(template.manifest().packageSha256()));
        return Map.copyOf(values);
    }

    private static void writeTemplateProvenanceProperties(
            XWPFDocument document,
            TemplateFile template) {
        POIXMLProperties.CustomProperties properties =
                document.getProperties().getCustomProperties();
        replaceCustomProperty(
                properties,
                TEMPLATE_ID_PROPERTY,
                DecisionRationaleTemplateContract.TEMPLATE_ID);
        replaceCustomProperty(
                properties,
                TEMPLATE_COMMIT_PROPERTY,
                value(template.commitId()));
        replaceCustomProperty(
                properties,
                TEMPLATE_SHA256_PROPERTY,
                value(template.manifest().packageSha256()));
    }

    private static void replaceCustomProperty(
            POIXMLProperties.CustomProperties properties,
            String name,
            String value) {
        var underlying = properties.getUnderlyingProperties();
        for (int index = underlying.sizeOfPropertyArray() - 1;
                index >= 0;
                index--) {
            if (name.equals(underlying.getPropertyArray(index).getName())) {
                underlying.removeProperty(index);
            }
        }
        properties.addProperty(name, value);
    }

    private static void replaceTokens(
            XWPFDocument document,
            Map<String, String> values) {
        replaceContainer(document.getParagraphs(), document.getTables(), values);
        for (XWPFHeader header : document.getHeaderList()) {
            replaceContainer(header.getParagraphs(), header.getTables(), values);
        }
        for (XWPFFooter footer : document.getFooterList()) {
            replaceContainer(footer.getParagraphs(), footer.getTables(), values);
        }
    }

    private static void replaceContainer(
            List<XWPFParagraph> paragraphs,
            List<XWPFTable> tables,
            Map<String, String> values) {
        for (XWPFParagraph paragraph : paragraphs) {
            replaceParagraph(paragraph, values);
        }
        for (XWPFTable table : tables) {
            replaceTable(table, values);
        }
    }

    private static void replaceTable(
            XWPFTable table,
            Map<String, String> values) {
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                replaceContainer(cell.getParagraphs(), cell.getTables(), values);
            }
        }
    }

    /**
     * Replace tokens across run boundaries while retaining the first run's formatting.
     *
     * <p>Word may split a visually contiguous placeholder into several runs after an
     * administrator edits its formatting. Reconstructing the paragraph text makes that
     * common case deterministic rather than relying on one token per run.</p>
     */
    private static void replaceParagraph(
            XWPFParagraph paragraph,
            Map<String, String> values) {
        String original = paragraph.getText();
        if (original == null || original.isEmpty()) {
            return;
        }
        String replaced = original;
        for (Map.Entry<String, String> token : values.entrySet()) {
            replaced = replaced.replace(token.getKey(), token.getValue());
        }
        if (replaced.equals(original)) {
            return;
        }

        List<XWPFRun> runs = paragraph.getRuns();
        XWPFRun first = runs.isEmpty() ? paragraph.createRun() : runs.get(0);
        for (int index = paragraph.getRuns().size() - 1; index >= 1; index--) {
            paragraph.removeRun(index);
        }
        while (first.getCTR().sizeOfTArray() > 1) {
            first.getCTR().removeT(first.getCTR().sizeOfTArray() - 1);
        }
        if (first.getCTR().sizeOfTArray() == 0) {
            first.setText(replaced);
        } else {
            first.setText(replaced, 0);
        }
    }

    private static void removeBodyMarker(XWPFDocument document) {
        List<IBodyElement> elements = new ArrayList<>(document.getBodyElements());
        for (int index = 0; index < elements.size(); index++) {
            IBodyElement element = elements.get(index);
            if (element.getElementType() != BodyElementType.PARAGRAPH) {
                continue;
            }
            XWPFParagraph paragraph = (XWPFParagraph) element;
            if (DecisionRationaleTemplateContract.BODY_MARKER
                    .equals(paragraph.getText().strip())) {
                document.removeBodyElement(index);
                return;
            }
        }
        throw new IllegalArgumentException(
                "Required body marker "
                        + DecisionRationaleTemplateContract.BODY_MARKER
                        + " was not found");
    }

    /**
     * A DOTX and a DOCX use the same package structure but different main-part content
     * types. Convert before Apache POI opens the package so the emitted artifact is a
     * genuine DOCX rather than a template with a misleading file extension.
     */
    private static byte[] convertDotxToDocx(byte[] dotx) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(dotx.length);
        boolean contentTypeChanged = false;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(dotx));
             ZipOutputStream zip = new ZipOutputStream(output)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                byte[] content = input.readAllBytes();
                if ("[Content_Types].xml".equals(entry.getName())) {
                    String xml = new String(content, StandardCharsets.UTF_8);
                    if (!xml.contains(OoxmlTemplatePackageCodec.DOTX_MAIN_CONTENT_TYPE)) {
                        throw new IOException(
                                "Template package does not declare the DOTX main content type");
                    }
                    xml = xml.replace(
                            OoxmlTemplatePackageCodec.DOTX_MAIN_CONTENT_TYPE,
                            DOCX_MAIN_CONTENT_TYPE);
                    content = xml.getBytes(StandardCharsets.UTF_8);
                    contentTypeChanged = true;
                }
                ZipEntry copy = new ZipEntry(entry.getName());
                if (entry.getComment() != null) {
                    copy.setComment(entry.getComment());
                }
                zip.putNextEntry(copy);
                if (!entry.isDirectory()) {
                    zip.write(content);
                }
                zip.closeEntry();
                input.closeEntry();
            }
        }
        if (!contentTypeChanged) {
            throw new IOException("Template package is missing [Content_Types].xml");
        }
        return output.toByteArray();
    }

    private static String statusLabel(
            DecisionRationaleReport report,
            DecisionReportLabels labels) {
        return switch (report.status()) {
            case FINAL -> labels.finalStatus();
            case FINAL_WITH_WARNINGS -> labels.finalWarnings();
            case DRAFT_INCOMPLETE -> labels.draft();
            case NO_RESULT -> labels.noResult();
        };
    }

    private static String formatInstant(
            Instant instant,
            String zoneId,
            boolean german) {
        if (instant == null) {
            return "unknown";
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(value(zoneId));
        } catch (RuntimeException exception) {
            zone = ZoneOffset.UTC;
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(
                german ? "dd.MM.yyyy HH:mm z" : "yyyy-MM-dd HH:mm z",
                german ? Locale.GERMAN : Locale.ENGLISH);
        return formatter.withZone(zone).format(instant);
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "unknown" : value.strip();
    }

    private static String abbreviate(String value) {
        String normalized = value(value);
        return normalized.length() <= 12 ? normalized : normalized.substring(0, 12);
    }

    private static IllegalStateException unavailable(
            String detail,
            Exception cause) {
        return new IllegalStateException(
                "Decision report template '"
                        + DecisionRationaleTemplateContract.TEMPLATE_ID
                        + "' is unavailable: " + detail,
                cause);
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }
}
