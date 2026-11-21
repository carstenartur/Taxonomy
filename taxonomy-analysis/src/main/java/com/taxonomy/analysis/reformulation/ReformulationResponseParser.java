package com.taxonomy.analysis.reformulation;

import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.reformulation.*;
import tools.jackson.databind.*;
import java.text.BreakIterator;
import java.util.*;

/** Strict schema and reference boundary; never accepts model-supplied business IDs. */
public class ReformulationResponseParser {
    private final ObjectMapper json;
    public ReformulationResponseParser(ObjectMapper json) { this.json=json; }
    public NodeSynthesisResult parse(String response,NodeSynthesisInput input) {
        try { return checked(response,input); }
        catch(IllegalArgumentException failure) { throw failure; }
        catch(RuntimeException failure) { throw new IllegalArgumentException("Invalid/truncated response JSON",failure); }
    }
    private NodeSynthesisResult checked(String response,NodeSynthesisInput input) {
        if(response==null || response.isBlank()) throw invalid("Empty response");
        JsonNode root=json.reader().with(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(response);
        fields(root,"summary","statementProposals","preservedStatementIds","questionProposals","preservedQuestionIds","uncoveredSourceRefs","conflictCandidates");
        Set<String> statementIds=new TreeSet<>(),questionIds=new TreeSet<>(),nodes=new TreeSet<>();
        var priorStatements=new ArrayList<Statement>();
        nodes.add(input.nodeId());if(input.parentId()!=null) nodes.add(input.parentId());
        input.directContributions().forEach(s->{priorStatements.add(s);statementIds.add(s.id());nodes.addAll(s.architectureLinks());});
        input.children().forEach(c->{nodes.add(c.nodeId());c.statementProposals().forEach(s->{priorStatements.add(s);statementIds.add(s.id());nodes.addAll(s.architectureLinks());});statementIds.addAll(c.preservedStatementIds());c.questionProposals().forEach(q->questionIds.add(q.id()));questionIds.addAll(c.preservedQuestionIds());});
        var rejected=new LinkedHashSet<>(RejectedWordingGuard.from(priorStatements));
        rejected.addAll(InheritedDecisionContext.rejected(input,json));
        if(RejectedWordingGuard.repeats(text(root,"summary"),rejected))throw invalid("Rejected wording repeated in summary");
        input.openDecisions().forEach(q->questionIds.add(q.id()));
        var catalogue=input.baseline().frozenContext().get("catalogue");
        if(catalogue!=null) catalogueIds(json.readTree(catalogue),nodes);
        for(String edge:input.boundaryEdges().values()) {
            var value=json.readTree(edge);
            for(String key:List.of("sourceCode","targetCode")) if(value.path(key).isString()) nodes.add(value.path(key).asText());
        }
        Set<String> links=new TreeSet<>(nodes);links.addAll(input.boundaryEdges().keySet());
        var preservedStatements=refs(root,"preservedStatementIds",statementIds);
        var preservedQuestions=refs(root,"preservedQuestionIds",questionIds);
        if(!new HashSet<>(preservedStatements).equals(statementIds) || !new HashSet<>(preservedQuestions).equals(questionIds)) throw invalid("Child/direct statement or question loss");
        var statementRefs=localRefs(statementIds,root,"statementProposals","new-statement:","s",input);
        var questionRefs=localRefs(questionIds,root,"questionProposals","new-question:","q",input);
        var conflicts=new ArrayList<ValidationReport.Finding>();
        var statements=new ArrayList<Statement>();int index=0;
        for(JsonNode node:array(root,"statementProposals")) {
            fields(node,"wording","provenance","sourceSpans","architectureLinks","questionDependencies","conditionalValidity");
            String wording=text(node,"wording");var spans=spans(node,"sourceSpans",input);
            if(RejectedWordingGuard.repeats(wording,rejected))throw invalid("Rejected wording repeated in statement");
            var provenance=Statement.Provenance.valueOf(text(node,"provenance"));
            if(provenance==Statement.Provenance.HUMAN_DECISION || provenance==Statement.Provenance.ADOPTED_SOURCE)
                throw invalid("Model cannot create human decisions or adopted source");
            if(provenance==Statement.Provenance.ORIGINAL && input.baseline().frozenContext().containsKey("adoptedLineage"))
                throw invalid("Model cannot create ORIGINAL for adopted source");
            boolean quotesSource = quotesContiguousSource(wording, spans, input.baseline().originalText());
            if(provenance==Statement.Provenance.ORIGINAL && !quotesSource)
                throw invalid("ORIGINAL wording must exactly match one contiguous source passage");
            boolean wholeSourceUnit = quotesSource && quotesCompleteSourceUnit(spans, input.baseline());
            var dependencies=refs(node,"questionDependencies",questionRefs);
            String condition=nullableText(node,"conditionalValidity");
            // An exact fragment may cut away a governing qualifier. Likewise, attaching a
            // new qualifier or decision gate to a complete quotation makes it a proposal.
            if (provenance == Statement.Provenance.ORIGINAL && (!wholeSourceUnit || condition != null || !dependencies.isEmpty()))
                provenance = Statement.Provenance.MODEL_ADDITION;
            String id=id("s",input,index++,node);
            if (!provenance.isSource() && !spans.isEmpty() && (!wholeSourceUnit || condition != null || !dependencies.isEmpty()))
                conflicts.add(new ValidationReport.Finding(ValidationReport.Kind.SEMANTIC_REVIEW, "SOURCE_FORMULATION_REVIEW",
                        "Proposed wording or conditions cite the source. Review the actor, object, scope and modality against the exact source passage.",
                        List.of(id), spans));
            statements.add(new Statement(id,wording,spans,provenance,refs(node,"architectureLinks",links),dependencies,condition,Statement.EditingOrigin.MODEL,"UNREVIEWED"));
        }
        var questions=new ArrayList<DecisionQuestion>();index=0;
        for(JsonNode node:array(root,"questionProposals")) {
            fields(node,"subject","dimension","scope","wording","rationale","affectedStatementIds","sourceSpans","nodeIds","edgeIds","answerSchema","prerequisites","consequences");
            var schema=node.get("answerSchema");
            boolean typed="reformulation-response-v2".equals(input.baseline().frozenContext().get("reformulationSchemaVersion"));
            if(typed) {
                var legacy=schema.deepCopy();
                ((tools.jackson.databind.node.ObjectNode)legacy).remove("optionMeanings");
                ((tools.jackson.databind.node.ObjectNode)legacy).remove("incompatibleOptions");
                ((tools.jackson.databind.node.ObjectNode)legacy).remove("applicability");
                fields(legacy,"kind","options","unit","minimum","maximum");
            } else fields(schema,"kind","options","unit","minimum","maximum");
            var kind=DecisionQuestion.AnswerSchema.Kind.valueOf(text(schema,"kind"));var options=strings(schema,"options");
            if((kind==DecisionQuestion.AnswerSchema.Kind.SINGLE_CHOICE || kind==DecisionQuestion.AnswerSchema.Kind.MULTIPLE_CHOICE) && options.size()<2) throw invalid("Choice question needs at least two options");
            Double min=number(schema,"minimum"),max=number(schema,"maximum");if(min!=null && max!=null && min>max) throw invalid("Invalid numeric range");
            var discovery=new DecisionQuestion.Discovery(input.nodeId(),input.nodeDescription(),text(node,"rationale"),spans(node,"sourceSpans",input),refs(node,"nodeIds",nodes),refs(node,"edgeIds",input.boundaryEdges().keySet()));
            questions.add(new DecisionQuestion(id("q",input,index++,node),new DecisionQuestion.Key(text(node,"subject"),text(node,"dimension"),text(node,"scope")),text(node,"wording"),List.of(discovery),refs(node,"affectedStatementIds",statementRefs),answerSchema(schema,kind,options,min,max,questionRefs),refs(node,"prerequisites",questionRefs),List.of(),text(node,"consequences"),DecisionQuestion.State.OPEN));
        }
        var contracts=new HashMap<String,DecisionQuestion>();
        input.openDecisions().forEach(q->q.referenceIds().forEach(id->contracts.put(id,q)));
        input.children().forEach(c->c.questionProposals().forEach(q->q.referenceIds().forEach(id->contracts.put(id,q))));
        questions.forEach(q->contracts.put(q.id(),q));
        for(var q:questions)for(var condition:q.answerSchema().applicability()) {
            var prerequisite=contracts.get(condition.questionId());
            if(prerequisite==null || prerequisite.id().equals(q.id()) || !q.prerequisites().contains(condition.questionId()))throw invalid("Invalid conditional prerequisite");
            var allowed=prerequisite.answerSchema().kind()==DecisionQuestion.AnswerSchema.Kind.BOOLEAN?List.of("true","false"):prerequisite.answerSchema().options();
            if(!allowed.containsAll(condition.anyOf()) || condition.anyOf().isEmpty())throw invalid("Unknown conditional choice");
        }
        for(JsonNode node:array(root,"conflictCandidates")) {
            fields(node,"kind","code","message","statementIds","sourceSpans");
            conflicts.add(new ValidationReport.Finding(ValidationReport.Kind.valueOf(text(node,"kind")),text(node,"code"),text(node,"message"),refs(node,"statementIds",statementRefs),spans(node,"sourceSpans",input)));
        }
        return new NodeSynthesisResult(input.nodeId(),text(root,"summary"),statements,preservedStatements,questions,preservedQuestions,spans(root,"uncoveredSourceRefs",input),conflicts);
    }
    private static boolean quotesContiguousSource(String wording, List<Statement.SourceSpan> spans, String source) {
        if (spans.isEmpty()) return false;
        int start = spans.getFirst().start();
        int end = start;
        for (var span : spans) {
            if (span.start() != end) return false;
            end = span.end();
        }
        return wording.equals(source.substring(start, end));
    }
    /** A sentence boundary protects against clipped clauses without certifying semantic entailment. */
    private static boolean quotesCompleteSourceUnit(List<Statement.SourceSpan> spans, ReformulationBaseline baseline) {
        String source = baseline.originalText();
        int start = spans.getFirst().start();
        int end = spans.getLast().end();
        if (start == 0 && end == source.length()) return true;
        while (start < end && Character.isWhitespace(source.charAt(start))) start++;
        while (end > start && Character.isWhitespace(source.charAt(end - 1))) end--;
        var sentences = BreakIterator.getSentenceInstance(Locale.forLanguageTag(baseline.language()));
        sentences.setText(source);
        boolean alignedStart = false;
        for (int left = sentences.first(), right = sentences.next(); right != BreakIterator.DONE;
             left = right, right = sentences.next()) {
            int visibleStart = left;
            int visibleEnd = right;
            while (visibleStart < visibleEnd && Character.isWhitespace(source.charAt(visibleStart))) visibleStart++;
            while (visibleEnd > visibleStart && Character.isWhitespace(source.charAt(visibleEnd - 1))) visibleEnd--;
            alignedStart |= start == visibleStart;
            if (end <= visibleEnd) return alignedStart && end == visibleEnd;
        }
        return false;
    }
    private static DecisionQuestion.AnswerSchema answerSchema(JsonNode schema,DecisionQuestion.AnswerSchema.Kind kind,
            List<String> options,Double min,Double max,Map<String,String> questionRefs) {
        var meanings=new LinkedHashMap<String,DecisionQuestion.AnswerSchema.OptionMeaning>();
        if(schema.has("optionMeanings")) {
            if(!schema.path("optionMeanings").isObject())throw invalid("Expected option meanings object");
            for(var entry:schema.path("optionMeanings").properties()) {
                if(!options.contains(entry.getKey()) || !entry.getValue().isString())throw invalid("Unknown semantic option");
                meanings.put(entry.getKey(),DecisionQuestion.AnswerSchema.OptionMeaning.valueOf(entry.getValue().asText()));
            }
        }
        var incompatible=new ArrayList<List<String>>();
        if(schema.has("incompatibleOptions"))for(var group:array(schema,"incompatibleOptions")) {
            if(!group.isArray() || group.size()<2)throw invalid("Incompatibility requires at least two options");
            var values=new ArrayList<String>();
            for(var option:group)if(!option.isString() || !options.contains(option.asText()) || !values.add(option.asText()))throw invalid("Invalid incompatible option");
            if(new HashSet<>(values).size()!=values.size())throw invalid("Duplicate incompatible option");
            incompatible.add(List.copyOf(values));
        }
        var conditions=new ArrayList<DecisionQuestion.AnswerSchema.AnswerCondition>();
        if(schema.has("applicability"))for(var condition:array(schema,"applicability")) {
            fields(condition,"questionId","anyOf");String id=questionRefs.get(text(condition,"questionId"));
            if(id==null)throw invalid("Unknown conditional question");
            conditions.add(new DecisionQuestion.AnswerSchema.AnswerCondition(id,strings(condition,"anyOf")));
        }
        return new DecisionQuestion.AnswerSchema(kind,options,nullableText(schema,"unit"),min,max,meanings,incompatible,conditions);
    }
    private Map<String,String> localRefs(Set<String> existing,JsonNode root,String field,String prefix,String idPrefix,NodeSynthesisInput input) {
        var refs=new TreeMap<String,String>();existing.forEach(id->refs.put(id,id));int index=0;
        for(var node:array(root,field)) {refs.put(prefix+index,id(idPrefix,input,index,node));index++;}
        return refs;
    }
    private static List<String> refs(JsonNode node,String key,Map<String,String> allowed) {
        return refs(node,key,allowed.keySet()).stream().map(allowed::get).toList();
    }
    private static void catalogueIds(JsonNode tree,Set<String> ids) {
        if(!tree.isArray())return;
        for(var node:tree) {if(node.path("code").isString())ids.add(node.path("code").asText());catalogueIds(node.path("children"),ids);}
    }
    private static String id(String prefix,NodeSynthesisInput input,int index,JsonNode node) {
        return prefix+"-"+StableIdentityHash.sha256(input.baseline().scope()+"|"+input.baseline().sourceVersionId()+"|"+input.baseline().originalTextHash()+"|"+input.nodeId()+"|"+index+"|"+node).substring(0,24);
    }
    private static void fields(JsonNode node,String... expected) {
        if(node==null || !node.isObject()) throw invalid("Expected object");
        var actual=new HashSet<String>();node.properties().forEach(e->actual.add(e.getKey()));
        if(!actual.equals(Set.of(expected))) throw invalid("Unexpected or missing schema fields");
    }
    private static String text(JsonNode node,String key) {
        var value=node.get(key);if(value==null || !value.isString() || value.asText().isBlank()) throw invalid("Expected nonblank string: "+key);return value.asText();
    }
    private static String nullableText(JsonNode node,String key) { return node.get(key).isNull()?null:text(node,key); }
    private static Double number(JsonNode node,String key) {var value=node.get(key);if(value.isNull())return null;if(!value.isNumber() || !Double.isFinite(value.asDouble()))throw invalid("Expected number: "+key);return value.asDouble();}
    private static JsonNode array(JsonNode node,String key) {var value=node.get(key);if(value==null || !value.isArray())throw invalid("Expected array: "+key);return value;}
    private static List<String> strings(JsonNode node,String key) {
        var result=new ArrayList<String>();for(var item:array(node,key)) {if(!item.isString() || item.asText().isBlank())throw invalid("Invalid ID/option");result.add(item.asText());}
        if(new HashSet<>(result).size()!=result.size())throw invalid("Duplicate ID/option");return List.copyOf(result);
    }
    private static List<String> refs(JsonNode node,String key,Set<String> allowed) {var result=strings(node,key);if(!allowed.containsAll(result))throw invalid("Unknown reference: "+key);return result;}
    private static List<Statement.SourceSpan> spans(JsonNode node,String key,NodeSynthesisInput input) {
        var result=new ArrayList<Statement.SourceSpan>();for(var item:array(node,key)) {
            fields(item,"start","end","exactText");if(!item.get("start").isIntegralNumber() || !item.get("end").isIntegralNumber() || !item.get("start").canConvertToInt() || !item.get("end").canConvertToInt())throw invalid("Invalid span offsets");
            var span=new Statement.SourceSpan(item.get("start").asInt(),item.get("end").asInt(),text(item,"exactText"));if(!span.matches(input.baseline().originalText()))throw invalid("Fabricated source span");result.add(span);
        }return List.copyOf(result);
    }
    private static IllegalArgumentException invalid(String message) {return new IllegalArgumentException(message);}
}
