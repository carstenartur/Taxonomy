package com.taxonomy.architecture.decision;

import com.taxonomy.architecture.decision.DecisionRationaleReport.*;
import com.taxonomy.architecture.report.WordDocumentWriter;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import java.util.*;
import java.time.Instant;

/** Small authored report over real catalogue roots and their direct children. */
public final class DecisionReadingChecks {
    public static void main(String[] args) throws Exception {
        int failures=0;
        for (String locale : List.of("de", "en")) {
            try { contents(locale); System.out.println("PASS TOC " + locale); }
            catch (AssertionError e) { failures++; System.err.println(e.getMessage()); }
        }
        if (failures > 0) throw new AssertionError(failures + " report navigation checks failed");
    }
    public static void contents(String locale) throws Exception {
        var labels = new DecisionReportLabels(locale);
        var metadata = new ReportMetadata(Instant.EPOCH,"qa","test","test","catalogue.xlsx","fixture","fixture","fixture","fixture","Authored test",
            4,2,"test-repository","test-workspace","draft","test-commit",Instant.EPOCH,false,false,"MOCK","SUCCESS","MOCK","fixture",null,null,null,null,
            Instant.EPOCH,"qa","fixture","fixture",true,"UTC",2,4,4,100.0,123456L);
        require(metadata.withAnalysisSnapshotFingerprintSha256("new-fingerprint").analysisDurationMillis().equals(123456L),
            "Snapshot fingerprinting must preserve measured duration");
        require(labels.durationMillis(null).equals(locale.equals("de") ? "Nicht aufgezeichnet" : "Not recorded"),
            "Unknown historical duration is explicit");
        var chapters = new ArrayList<DecisionChapter>();
        // BP -> BP-1000 and BR -> BR-1000 are direct parent/child pairs in the real catalogue.
        for (String code : List.of("BP", "BR")) chapters.add(new DecisionChapter(chapters.size()+1,code,code,"",100,0,true,"Authored decision","Authored comparison",
            List.of(new ChildDecision(code+"-1000",code,"",100,100.0,1,true,Disposition.CONTINUED,"Authored evidence",ReasonSource.AI_SCORING,false)),List.of()));
        var report = new DecisionRationaleReport("Reading QA",locale,"Arbeitszeiterfassung",ReportStatus.FINAL,metadata,
            new ExecutiveSummary(null,List.of(),"Authored conclusion","Authored method"),chapters,List.of(),List.of(),List.of(),List.of(),null);
        String html = new DecisionRationaleHtmlRenderer(new DecisionChapterDiagramRenderer()).render(report);
        require(html.contains(labels.durationMillis(123456L)), "HTML report must show measured duration");
        try (var document = new XWPFDocument()) {
            new DecisionRationaleDocxRenderer(new DecisionChapterDiagramRenderer()).writeReportBody(document,report,labels);
            var peers = document.getParagraphs().stream().filter(p->"Heading2".equals(p.getStyle())).map(p->p.getText()).toList();
            require(peers.stream().noneMatch(labels.alternatives()::equals), "Alternatives must not appear as repeated peer chapters: " + peers);
            require(document.getParagraphs().stream().filter(p->"Heading3".equals(p.getStyle())).filter(p->p.getText().startsWith(labels.alternatives())).count()==2,
                "Both contextual alternatives headings remain in the document");
            String xml=document.getDocument().xmlText();
            require(xml.contains(labels.durationMillis(123456L)), "Word report must show measured duration");
            require(xml.contains("TOC \\o &quot;1-2&quot;") || xml.contains("TOC \\o \"1-2\""), "Decision TOC must include only two levels");
            require(document.getParagraphs().stream().noneMatch(p->labels.contents().equals(p.getText())&&"Heading1".equals(p.getStyle())), "TOC must not index itself");
            var links=document.getTables().getFirst().getRows().stream().skip(1).flatMap(row->row.getTableCells().get(1).getParagraphs().stream()).flatMap(p->p.getCTP().getHyperlinkList().stream()).flatMap(h->h.getRList().stream()).flatMap(run->run.getTList().stream()).map(text->text.getStringValue()).toList();
            require(links.stream().allMatch(labels.openChapter()::equals), "Fallback navigation must not repeat each title as its action label: " + links);
            var directory=java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/decision-reading-qa"));
            try(var stream=java.nio.file.Files.newOutputStream(directory.resolve("decision-reading-"+locale+".docx"))){document.write(stream);}
        }
    }
    static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
