package com.taxonomy.analysis.reformulation;
import com.taxonomy.reformulation.*;
import tools.jackson.databind.*;
import java.util.*;
/** Strict boundary for suggestions; merging/structural certification never belong to the model. */
public class ReconcileResponseParser {
    private final ObjectMapper json;
    public ReconcileResponseParser(ObjectMapper json){this.json=json;}
    public ReconciliationResult parse(String raw,ReconciliationInput in) {
        try {
            var root=json.reader().with(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
            fields(root,"affectedSectionIds","sourceResolutions","findings");
            var sections=new HashSet<String>();in.sections().forEach(s->sections.add(s.id()));
            var statements=new HashSet<String>();in.statements().forEach(s->statements.add(s.id()));
            var questions=new HashMap<String,DecisionQuestion>();in.questions().forEach(q->q.referenceIds().forEach(id->questions.put(id,q)));
            var affected=refs(root,"affectedSectionIds",sections);var resolutions=new LinkedHashMap<String,DecisionQuestion.SourceResolution>();
            for(var n:array(root,"sourceResolutions")) {
                fields(n,"questionId","values","rationale","sourceSpans");String id=text(n,"questionId");var q=questions.get(id);if(q==null)throw invalid("Unknown question");
                var values=strings(n,"values");validateValues(q.answerSchema(),values);var spans=spans(n,"sourceSpans",in.baseline().originalText());if(spans.isEmpty())throw invalid("Source resolution requires evidence");
                if(resolutions.put(q.id(),new DecisionQuestion.SourceResolution(values,spans,text(n,"rationale")))!=null)throw invalid("Duplicate resolution");
            }
            var findings=new ArrayList<ValidationReport.Finding>();
            for(var n:array(root,"findings")) {
                fields(n,"kind","code","message","statementIds","sourceSpans");var kind=ValidationReport.Kind.valueOf(text(n,"kind"));
                if(kind!=ValidationReport.Kind.CONFLICT && kind!=ValidationReport.Kind.SEMANTIC_REVIEW)throw invalid("Model cannot certify structural coverage");
                findings.add(new ValidationReport.Finding(kind,text(n,"code"),text(n,"message"),refs(n,"statementIds",statements),spans(n,"sourceSpans",in.baseline().originalText())));
            }
            if(!affected.isEmpty() && findings.isEmpty())throw invalid("Affected sections require a review finding");
            return new ReconciliationResult(affected,resolutions,findings);
        }catch(IllegalArgumentException e){throw e;}catch(RuntimeException e){throw invalid("Invalid reconcile JSON");}
    }
    static void validateValues(DecisionQuestion.AnswerSchema schema,List<String> values) {
        if(values.isEmpty())throw invalid("Empty resolution");
        switch(schema.kind()) {
            case SINGLE_CHOICE -> {if(values.size()!=1 || !schema.options().containsAll(values))throw invalid("Invalid choice");}
            case MULTIPLE_CHOICE -> {if(!schema.options().containsAll(values))throw invalid("Invalid choices");}
            case BOOLEAN -> {if(values.size()!=1 || !Set.of("true","false").contains(values.getFirst()))throw invalid("Invalid boolean");}
            case TEXT -> {if(values.size()!=1)throw invalid("Invalid text");}
            case NUMBER -> {if(values.size()!=1)throw invalid("Invalid number");double v=Double.parseDouble(values.getFirst());if(!Double.isFinite(v) || schema.minimum()!=null && v<schema.minimum() || schema.maximum()!=null && v>schema.maximum())throw invalid("Invalid range");}
        }
    }
    static List<Statement.SourceSpan> spans(JsonNode n,String key,String original) {
        var result=new ArrayList<Statement.SourceSpan>();for(var s:array(n,key)){fields(s,"start","end","exactText");
            if(!s.path("start").isIntegralNumber() || !s.path("end").isIntegralNumber() || !s.path("start").canConvertToInt() || !s.path("end").canConvertToInt())throw invalid("Invalid span offset");
            var span=new Statement.SourceSpan(s.path("start").asInt(),s.path("end").asInt(),text(s,"exactText"));if(!span.matches(original))throw invalid("Fabricated source span");result.add(span);}return List.copyOf(result);
    }
    private static void fields(JsonNode n,String... keys){if(n==null || !n.isObject())throw invalid("Expected object");var actual=new HashSet<String>();n.properties().forEach(e->actual.add(e.getKey()));if(!actual.equals(Set.of(keys)))throw invalid("Unexpected schema fields");}
    private static JsonNode array(JsonNode n,String key){var a=n.path(key);if(!a.isArray())throw invalid("Expected array");return a;}
    private static String text(JsonNode n,String key){var v=n.path(key);if(!v.isString() || v.asText().isBlank())throw invalid("Expected text");return v.asText();}
    private static List<String> strings(JsonNode n,String key){var values=new ArrayList<String>();for(var v:array(n,key)){if(!v.isString() || v.asText().isBlank() || values.contains(v.asText()))throw invalid("Invalid value");values.add(v.asText());}return List.copyOf(values);}
    private static List<String> refs(JsonNode n,String key,Set<String> allowed){var ids=strings(n,key);if(!allowed.containsAll(ids))throw invalid("Unknown reference");return ids;}
    private static IllegalArgumentException invalid(String m){return new IllegalArgumentException(m);}
}
