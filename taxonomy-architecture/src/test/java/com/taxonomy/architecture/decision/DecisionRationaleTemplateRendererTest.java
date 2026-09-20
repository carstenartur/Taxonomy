package com.taxonomy.architecture.decision;

import com.taxonomy.extension.api.report.ReportRenderResult;
import com.taxonomy.templates.DecisionRationaleTemplateContract;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateManifest;
import com.taxonomy.templates.DocumentTemplateService;
import com.taxonomy.templates.DocumentTemplateService.TemplateFile;
import com.taxonomy.templates.OoxmlTemplatePackageCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DecisionRationaleTemplateRendererTest {

    private static final String TEMPLATE_COMMIT =
            "0123456789abcdef0123456789abcdef01234567";
    private static final String TEMPLATE_SHA256 = "a".repeat(64);
    private static final List<String> VISIBLE_PROVENANCE_TOKENS = List.of(
            "{{taxonomy.template.id}}",
            "{{taxonomy.template.commit}}",
            "{{taxonomy.template.sha256}}");

    @Mock
    private DocumentTemplateService templates;

    @Mock
    private DecisionChapterDiagramRenderer diagrams;

    @Test
    void materializesTheReportIntoTheVersionedTemplateAndEmitsAGenuineDocx()
            throws Exception {
        OoxmlTemplatePackageCodec codec = new OoxmlTemplatePackageCodec();
        byte[] dotx;
        try (InputStream input = DecisionRationaleTemplateRendererTest.class
                .getResourceAsStream(
                        "/" + DecisionRationaleTemplateContract.DEFAULT_RESOURCE)) {
            assertThat(input).isNotNull();
            Map<String, byte[]> parts = new LinkedHashMap<>(
                    codec.unpack(input).parts());
            boolean visibleProvenanceWasPresent = false;
            for (String path : List.copyOf(parts.keySet())) {
                if (!(path.endsWith(".xml") || path.endsWith(".rels"))) {
                    continue;
                }
                String xml = new String(parts.get(path), StandardCharsets.UTF_8);
                for (String token : VISIBLE_PROVENANCE_TOKENS) {
                    visibleProvenanceWasPresent |= xml.contains(token);
                    xml = xml.replace(token, "");
                }
                parts.put(path, xml.getBytes(StandardCharsets.UTF_8));
            }
            assertThat(visibleProvenanceWasPresent).isTrue();
            parts.entrySet().stream()
                    .filter(entry -> entry.getKey().endsWith(".xml")
                            || entry.getKey().endsWith(".rels"))
                    .forEach(entry -> {
                        String xml = new String(
                                entry.getValue(), StandardCharsets.UTF_8);
                        for (String token : VISIBLE_PROVENANCE_TOKENS) {
                            assertThat(xml)
                                    .as(entry.getKey())
                                    .doesNotContain(token);
                        }
                    });
            dotx = codec.pack(parts);
        }
        TemplateManifest manifest = new TemplateManifest(
                1,
                DecisionRationaleTemplateContract.TEMPLATE_ID,
                DecisionRationaleTemplateContract.DISPLAY_NAME,
                DecisionRationaleTemplateContract.TEMPLATE_ID + ".dotx",
                OoxmlTemplatePackageCodec.DOTX_MEDIA_TYPE,
                "2026-08-22T16:00:00Z",
                "taxonomy-bootstrap",
                4_096,
                10,
                TEMPLATE_SHA256);
        when(templates.downloadCurrentValidated(
                DecisionRationaleTemplateContract.TEMPLATE_ID))
                .thenReturn(new TemplateFile(
                        manifest,
                        TEMPLATE_COMMIT,
                        dotx,
                        Instant.parse("2026-08-22T16:00:00Z")));

        RecordingDecisionRationaleDocxRenderer delegate =
                new RecordingDecisionRationaleDocxRenderer(diagrams);
        DecisionRationaleTemplateRenderer renderer =
                new DecisionRationaleTemplateRenderer(
                        templates,
                        new DecisionRationaleTemplateContract());
        DecisionRationaleReport report = report();

        assertThat(delegate.render(report)).isNotEmpty();
        assertThat(delegate.reportBodyWrites()).isEqualTo(1);

        ReportRenderResult artifact = renderer.renderArtifact(delegate, report);
        assertThat(delegate.reportBodyWrites()).isEqualTo(2);
        assertThat(artifact.artifactMetadata()).containsExactlyInAnyOrderEntriesOf(
                new DecisionReportTemplateProvenance(
                        DecisionRationaleTemplateContract.TEMPLATE_ID,
                        TEMPLATE_COMMIT,
                        TEMPLATE_SHA256,
                        1).artifactMetadata());
        byte[] docx = artifact.bytes();
        try (var document = new org.apache.poi.xwpf.usermodel.XWPFDocument(
                new ByteArrayInputStream(docx))) {
            assertThat(document.getParagraphs()).noneMatch(p -> p.getText().isBlank()
                    && (p.isPageBreak() || p.getRuns().stream().anyMatch(r ->
                    r.getCTR().getBrList().stream().anyMatch(b ->
                            org.openxmlformats.schemas.wordprocessingml.x2006.main.STBrType.PAGE.equals(b.getType())))));
        }
        Map<String, byte[]> entries = unzip(docx);

        String contentTypes = text(entries, "[Content_Types].xml");
        assertThat(contentTypes)
                .contains("wordprocessingml.document.main+xml")
                .doesNotContain("wordprocessingml.template.main+xml");

        String documentXml = text(entries, "word/document.xml");
        assertThat(documentXml)
                .contains("TAXONOMY · DECISION EVIDENCE")
                .contains(report.title())
                .contains(report.requirement())
                .contains("Executive summary")
                .doesNotContain(DecisionRationaleTemplateContract.BODY_MARKER);

        String headerXml = text(entries, "word/header1.xml");
        assertThat(headerXml)
                .contains(report.title())
                .contains(report.metadata().taxonomyDataVersion());

        String footerXml = text(entries, "word/footer1.xml");
        assertThat(footerXml)
                .contains(report.metadata().generatedBy())
                .doesNotContain(TEMPLATE_COMMIT)
                .doesNotContain(TEMPLATE_COMMIT.substring(0, 12))
                .contains("PAGE")
                .contains("NUMPAGES");

        String customProperties = text(entries, "docProps/custom.xml");
        assertThat(customProperties)
                .contains("Taxonomy.Template.Id")
                .contains(DecisionRationaleTemplateContract.TEMPLATE_ID)
                .contains("Taxonomy.Template.Commit")
                .contains(TEMPLATE_COMMIT)
                .contains("Taxonomy.Template.PackageSha256")
                .contains(TEMPLATE_SHA256)
                .contains("Taxonomy.Template.SchemaVersion")
                .contains(">1<");

        entries.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith(".xml")
                        || entry.getKey().endsWith(".rels"))
                .forEach(entry -> assertThat(
                        new String(entry.getValue(), StandardCharsets.UTF_8))
                        .as(entry.getKey())
                        .doesNotContain("{{taxonomy."));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"en","de","too-small","note-collision"})
    void enrichedBodyPreservesCustomStylesLogoStoriesAndDecisionIdentity(String language) throws Exception {
        byte[] logo;
        var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);
        try(var output=new java.io.ByteArrayOutputStream()){javax.imageio.ImageIO.write(image,"png",output);logo=output.toByteArray();}
        byte[] template;
        try(var doc=new org.apache.poi.xwpf.usermodel.XWPFDocument();var output=new java.io.ByteArrayOutputStream()) {
            var styles=doc.createStyles();
            var defaultFonts=org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFonts.Factory.newInstance();
            defaultFonts.setAscii("Georgia");defaultFonts.setHAnsi("Georgia");styles.setDefaultFonts(defaultFonts);
            var footnote=doc.createFootnote();var fp=footnote.createParagraph();
            fp.createRun().setText("Administrator footnote");
            var fb=fp.getCTP().addNewBookmarkStart();fb.setId(java.math.BigInteger.ZERO);
            fb.setName(language.equals("note-collision")?"decision_tree":"administrator_footnote");
            fp.getCTP().addNewBookmarkEnd().setId(java.math.BigInteger.ZERO);
            var endnote=doc.createEndnote();var ep=endnote.createParagraph();ep.createRun().setText("Administrator endnote");
            var eb=ep.getCTP().addNewBookmarkStart();eb.setId(java.math.BigInteger.ONE);eb.setName("administrator_endnote");
            ep.getCTP().addNewBookmarkEnd().setId(java.math.BigInteger.ONE);
            var noteLink=fp.getCTP().addNewHyperlink();noteLink.setAnchor("administrator_endnote");noteLink.addNewR().addNewT().setStringValue("See saved endnote");
            var references=doc.createParagraph();references.createRun().setText("Administrator note references");
            references.addFootnoteReference(footnote);references.createRun().getCTR().addNewEndnoteReference().setId(endnote.getId());
            var style=org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle.Factory.newInstance();
            style.setStyleId("Heading1");style.addNewName().setVal("Custom Heading");style.addNewRPr().addNewColor().setVal("AA22BB");
            styles.addStyle(new org.apache.poi.xwpf.usermodel.XWPFStyle(style));
            doc.createParagraph().createRun().setText(DecisionRationaleTemplateContract.TITLE_TOKEN);
            doc.createParagraph().createRun().setText(DecisionRationaleTemplateContract.REQUIREMENT_TOKEN);
            doc.createParagraph().createRun().setText(DecisionRationaleTemplateContract.BODY_MARKER);
            var header=doc.createHeader(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT);
            var run=header.createParagraph().createRun();run.setText("Administrator header");
            run.addPicture(new ByteArrayInputStream(logo),org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG,"logo.png",100000,100000);
            doc.createFooter(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT).createParagraph().createRun().setText("Administrator footer");
            if(language.equals("too-small")) {
                var section=doc.getDocument().getBody().getSectPr();if(section==null)section=doc.getDocument().getBody().addNewSectPr();
                var size=section.isSetPgSz()?section.getPgSz():section.addNewPgSz();size.setW(java.math.BigInteger.valueOf(15840));size.setH(java.math.BigInteger.valueOf(4320));
                var margins=section.isSetPgMar()?section.getPgMar():section.addNewPgMar();margins.setTop(java.math.BigInteger.valueOf(1440));margins.setBottom(java.math.BigInteger.valueOf(1440));margins.setLeft(java.math.BigInteger.valueOf(1440));margins.setRight(java.math.BigInteger.valueOf(1440));
            }
            doc.write(output);template=output.toByteArray();
        }
        var codec=new OoxmlTemplatePackageCodec();var parts=new LinkedHashMap<>(unzip(template));
        parts.put("[Content_Types].xml",new String(parts.get("[Content_Types].xml"),StandardCharsets.UTF_8)
                .replace("wordprocessingml.document.main+xml","wordprocessingml.template.main+xml").getBytes(StandardCharsets.UTF_8));
        template=codec.pack(parts);
        var manifest=new TemplateManifest(1,DecisionRationaleTemplateContract.TEMPLATE_ID,"Custom","custom.dotx",OoxmlTemplatePackageCodec.DOTX_MEDIA_TYPE,"2026-08-22T16:00:00Z","administrator",template.length,parts.size(),TEMPLATE_SHA256);
        when(templates.downloadCurrentValidated(DecisionRationaleTemplateContract.TEMPLATE_ID))
                .thenReturn(new TemplateFile(manifest,TEMPLATE_COMMIT,template,Instant.EPOCH));
        var base=report();
        var chapter=new DecisionRationaleReport.DecisionChapter(1,"BP","Saved parent","",100,0,true,"Saved decision","Saved comparison",List.of(),List.of());
        var decision=new DecisionRationaleReport(base.title(),language,base.requirement(),base.status(),base.metadata(),base.executiveSummary(),List.of(chapter),List.of(),List.of(),List.of(),List.of(),null);
        var graph=new com.taxonomy.diagram.DiagramModel("Saved graph",List.of(new com.taxonomy.diagram.DiagramNode("A","Saved A","Capability",1,true,0),new com.taxonomy.diagram.DiagramNode("B","Saved B","Service",.5,false,1)),List.of(new com.taxonomy.diagram.DiagramEdge("E1","A","B","serves",.5)),null);
        var evidence=new com.taxonomy.architecture.report.ArchitectureReportDocument.SnapshotEvidence(1L,2L,3L,4,"snapshot","repository","workspace","main","based-on-commit","MOCK","model","fingerprint",com.taxonomy.architecture.report.ArchitectureReportDocument.graphSha256(graph));
        var architecture=com.taxonomy.architecture.report.ArchitectureReportDocument.from("Architecture title",language,decision.requirement(),"Saved scope","Saved recommendation",List.of(),graph,new com.taxonomy.export.LayeredDiagramLayoutService().layout(graph),com.taxonomy.architecture.report.DecisionTreeOverview.from(decision.chapters()),evidence);
        var renderer=new DecisionRationaleTemplateRenderer(templates,new DecisionRationaleTemplateContract());
        if(language.equals("note-collision")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(()->renderer.render(new DecisionRationaleDocxRenderer(new DecisionChapterDiagramRenderer()),decision.withArchitecture(architecture)))
                    .hasStackTraceContaining("Duplicate Word bookmark: decision_tree");
            return;
        }
        if(language.equals("too-small")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(()->renderer.render(new DecisionRationaleDocxRenderer(new DecisionChapterDiagramRenderer()),decision.withArchitecture(architecture)))
                .isInstanceOf(com.taxonomy.architecture.report.WordReportLayoutException.class).hasMessageContaining("8pt");
            return;
        }
        byte[] result=renderer.render(new DecisionRationaleDocxRenderer(new DecisionChapterDiagramRenderer()),decision.withArchitecture(architecture));
        try(var doc=new org.apache.poi.xwpf.usermodel.XWPFDocument(new ByteArrayInputStream(result))) {
            var ids=new java.util.HashSet<String>();var names=new java.util.HashSet<String>();
            var stories=new java.util.ArrayList<org.apache.xmlbeans.XmlObject>();stories.add(doc.getDocument());
            doc.getHeaderList().forEach(h->stories.add(h._getHdrFtr()));doc.getFooterList().forEach(f->stories.add(f._getHdrFtr()));
            doc.getFootnotes().forEach(n->stories.add(n.getCTFtnEdn()));doc.getEndnotes().forEach(n->stories.add(n.getCTFtnEdn()));
            for(var story:stories)for(var bookmark:story.selectPath("declare namespace w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'; .//w:bookmarkStart")){
                var attrs=bookmark.getDomNode().getAttributes();
                assertThat(ids.add(attrs.getNamedItemNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","id").getNodeValue())).isTrue();
                assertThat(names.add(attrs.getNamedItemNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","name").getNodeValue())).isTrue();
            }
            assertThat(names).contains("administrator_footnote","administrator_endnote","decision_chapter_1_BP");
            assertThat(text(unzip(result),"word/footnotes.xml")).contains("Administrator footnote","administrator_endnote","See saved endnote");
            assertThat(text(unzip(result),"word/endnotes.xml")).contains("Administrator endnote");
            assertThat(text(unzip(result),"word/styles.xml")).contains("Georgia");
            assertThat(doc.getDocument().xmlText()).contains("w:anchor=\"decision_chapter_1_BP\"");
            java.nio.file.Path qa=java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/final-word-review"));
            java.nio.file.Files.write(qa.resolve("custom-notes-"+language+".docx"),result);
            assertThat(doc.getHeaderList().getFirst().getText()).contains("Administrator header");
            assertThat(doc.getFooterList().getFirst().getText()).contains("Administrator footer");
            assertThat(unzip(result).values()).anyMatch(bytes->java.util.Arrays.equals(logo,bytes));
            assertThat(doc.getStyles().getStyle("Heading1").getCTStyle().xmlText()).contains("AA22BB");
            assertThat(doc.getProperties().getCoreProperties().getTitle()).isEqualTo(decision.title());
            assertThat(doc.getProperties().getCustomProperties().getProperty("Taxonomy.Template.Commit").getLpwstr()).isEqualTo(TEMPLATE_COMMIT);
            assertThat(doc.getProperties().getCustomProperties().getProperty("taxonomy.graph.sha256").getLpwstr()).isEqualTo(evidence.graphSha256());
            String text=new org.apache.poi.xwpf.extractor.XWPFWordExtractor(doc).getText();
            assertThat(text).contains("Saved A","Saved B","E1","Saved parent","Saved decision","Saved recommendation");
            assertThat(text).contains(language.equals("de")?"Vollständiger Entscheidungsbaum":"Complete decision tree");
            assertThat(doc.getDocument().xmlText()).contains("decision_chapter_1_BP","architecture-detail-1");
        }
    }

    private static final class RecordingDecisionRationaleDocxRenderer
            extends DecisionRationaleDocxRenderer {

        private int reportBodyWrites;

        private RecordingDecisionRationaleDocxRenderer(
                DecisionChapterDiagramRenderer diagrams) {
            super(diagrams);
        }

        @Override
        void writeReportBody(
                org.apache.poi.xwpf.usermodel.XWPFDocument document,
                DecisionRationaleReport report,
                DecisionReportLabels labels) throws Exception {
            reportBodyWrites++;
            super.writeReportBody(document, report, labels);
        }

        private int reportBodyWrites() {
            return reportBodyWrites;
        }
    }

    static DecisionRationaleReport report() {
        Instant generatedAt = Instant.parse("2026-08-22T14:30:00Z");
        DecisionRationaleReport.ReportMetadata metadata =
                new DecisionRationaleReport.ReportMetadata(
                        generatedAt,
                        "template-user",
                        "1.4.0",
                        "build-commit",
                        "catalogue.xlsx",
                        "2026-08",
                        "source-sha",
                        "data-sha",
                        "analysis-sha",
                        "Bundled test catalogue",
                        10,
                        2,
                        "repository",
                        "workspace",
                        "main",
                        "based-on-commit",
                        generatedAt,
                        false,
                        false,
                        "MOCK",
                        "SUCCESS",
                        "mock-model",
                        "snapshot",
                        1L,
                        2L,
                        3L,
                        4,
                        generatedAt,
                        "analysis-author",
                        "recorded-taxonomy-sha",
                        "prompt-sha",
                        true,
                        "Europe/Berlin",
                        4,
                        10,
                        3,
                        100.0);
        return new DecisionRationaleReport(
                "Template-backed decision report",
                "en",
                "Provide a secure architecture decision.",
                DecisionRationaleReport.ReportStatus.FINAL,
                metadata,
                new DecisionRationaleReport.ExecutiveSummary(
                        null,
                        List.of(),
                        "No leading leaf is needed for this template contract test.",
                        "The dynamic report body is rendered after the editable cover."),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null);
    }

    private static Map<String, byte[]> unzip(byte[] archive) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    entries.put(entry.getName(), zip.readAllBytes());
                }
                zip.closeEntry();
            }
        }
        return entries;
    }

    private static String text(Map<String, byte[]> entries, String path) {
        assertThat(entries).containsKey(path);
        return new String(entries.get(path), StandardCharsets.UTF_8);
    }
}
