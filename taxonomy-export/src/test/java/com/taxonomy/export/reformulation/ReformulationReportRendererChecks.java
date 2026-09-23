package com.taxonomy.export.reformulation;

import com.taxonomy.reformulation.*;
import java.time.Instant;
import java.util.*;

/** Executable renderer contracts; no browser, persistence, model or clock dependencies. */
public final class ReformulationReportRendererChecks {
    private ReformulationReportRendererChecks() {}
    public static void main(String[] args) {
        literalMarkup(); historyAndOrigins(); immutableInputs(); literalTildesInHeadings();
        System.out.println("REFORMULATION_REPORT_RENDERER_OK 4");
    }
    static void literalMarkup() {
        String text="alpha\n``````\n<script>fail()</script>\n# forged heading & ÄÖÜ";
        var in=input("de",false,text,List.of(),List.of(),List.of(),Map.of());
        String md=ReformulationReportRenderer.markdown(in),html=ReformulationReportRenderer.html(in);
        require(md.contains("```````text\n"+text+"\n```````"),"A literal code fence could terminate report text");
        require(!html.contains("<script>") && html.contains("&lt;script&gt;fail()&lt;/script&gt;"),"Unsafe HTML text");
        require(html.contains("default-src 'none'") && html.contains("lang=\"de\""),"Missing isolated HTML policy/language");
        require(md.contains("keine Übernahmebestätigung"),"Proposal falsely certifies adoption");
        var en=input("en",true,text,List.of(),List.of(),List.of(),Map.of());
        require(ReformulationReportRenderer.html(en).contains("not a claim")
                || ReformulationReportRenderer.html(en).contains("Not a claim"),"Receipt claims current authority");
    }
    static void historyAndOrigins() {
        var discovery=new DecisionQuestion.Discovery("BP","literal history\n<script>x</script>","why",List.of(),List.of("BP"),List.of("edge-7"));
        var q=new DecisionQuestion("q-1",new DecisionQuestion.Key("time","channel","BP"),"Question [link](javascript:x)\n# bad",
                List.of(discovery),List.of(),new DecisionQuestion.AnswerSchema(DecisionQuestion.AnswerSchema.Kind.TEXT,List.of(),null,null,null),List.of(),List.of(),"consequence",DecisionQuestion.State.OPEN);
        q=new DecisionQuestion(q.id(),q.key(),q.wording(),q.discoveries(),q.affectedStatementIds(),q.answerSchema(),q.prerequisites(),q.dependentQuestionIds(),q.consequences(),q.state(),List.of("old-q"),List.of(q.origin()),List.of());
        var one=new DecisionAnswer("answer-1","q-1","offer",2,List.of("Browser"),DecisionQuestion.State.ANSWERED,"Alice",Instant.EPOCH,"old");
        var two=new DecisionAnswer("answer-2","q-1","offer",3,List.of("Terminal"),DecisionQuestion.State.ANSWERED,"Bob",Instant.EPOCH.plusSeconds(1),"chosen",null,"ANSWER",List.of("answer-1"));
        String md=ReformulationReportRenderer.markdown(input("en",false,"text",List.of(q),List.of(one,two),List.of(),Map.of()));
        require(md.contains("superseded") && md.contains("active in this record") && md.contains("Browser") && md.contains("Terminal"),"Decision history erased");
        require(md.contains("Original question:") && md.contains("edge-7") && md.contains("old-q"),"Merged discovery origin lost");
        int headingStart=md.indexOf("### q-1");
        require(headingStart>=0,"Missing question heading");
        String heading=md.substring(headingStart,md.indexOf("\n\n",headingStart));
        require(!heading.contains("\n") && heading.contains("\\[link\\]"),"Heading text injects markup");
    }
    static void immutableInputs() {
        Map<String,String> metadata=new HashMap<>();metadata.put("b","second");metadata.put("a","first");
        var in=input("en",false,"text",List.of(),List.of(),List.of(),metadata);
        String before=ReformulationReportRenderer.markdown(in);metadata.put("a","changed");
        require(before.equals(ReformulationReportRenderer.markdown(in)),"Caller mutation changed saved report input");
        require(before.indexOf("a: first")<before.indexOf("b: second"),"Unstable metadata order");
        try{input("xx",false,"x",List.of(),List.of(),List.of(),Map.of());throw new AssertionError("Invalid language accepted");}
        catch(IllegalArgumentException expected){/* required */}
    }
    static void literalTildesInHeadings() {
        String title = "Erfassung ~~nicht freigegeben~~ und ~optional~";
        var section = new Section("BP", "BP", title, "Keep the exact wording", List.of(), List.of(), List.of());
        var question = new DecisionQuestion("q-tilde", new DecisionQuestion.Key("time", "channel", "BP"),
                "~~Browser~~ oder ~Terminal~?", List.of(), List.of(),
                new DecisionQuestion.AnswerSchema(DecisionQuestion.AnswerSchema.Kind.TEXT, List.of(), null, null, null),
                List.of(), List.of(), "No decision made", DecisionQuestion.State.OPEN);
        for (String language : List.of("de", "en")) {
            var report = new ReformulationReportRenderer.Input(language, false, Map.of(), "original", "proposal",
                    List.of(section), List.of(), List.of(question), List.of(), new ValidationReport(List.of()), "{}");
            String markdown = ReformulationReportRenderer.markdown(report);
            String literalTitle = title.replace("~", "\\~");
            String literalQuestion = question.wording().replace("~", "\\~");
            require(markdown.contains("### BP — " + literalTitle + "\n\n"),
                    "Section title can be interpreted as strikethrough instead of literal text");
            require(markdown.contains("### q-tilde — " + literalQuestion + "\n\n"),
                    "Question wording can be interpreted as strikethrough instead of literal text");
            require(ReformulationReportRenderer.html(report).contains(title), "HTML literal tildes changed");
            require(report.sections().getFirst().title().equals(title)
                    && report.questions().getFirst().wording().equals(question.wording()), "Persisted report text changed");
        }
    }
    private static ReformulationReportRenderer.Input input(String language,boolean adopted,String text,List<DecisionQuestion> qs,List<DecisionAnswer> as,List<Statement> ss,Map<String,String> metadata){
        return new ReformulationReportRenderer.Input(language,adopted,metadata,"original",text,List.of(),ss,qs,as,new ValidationReport(List.of()),"{\"untrusted\":\"<script>\"}");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
