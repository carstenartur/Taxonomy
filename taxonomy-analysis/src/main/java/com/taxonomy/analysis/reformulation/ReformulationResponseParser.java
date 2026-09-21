package com.taxonomy.analysis.reformulation;

import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.reformulation.*;
import tools.jackson.databind.*;
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
        nodes.add(input.nodeId());if(input.parentId()!=null) nodes.add(input.parentId());
        input.directContributions().forEach(s->{statementIds.add(s.id());nodes.addAll(s.architectureLinks());});
        input.children().forEach(c->{nodes.add(c.nodeId());c.statementProposals().forEach(s->{statementIds.add(s.id());nodes.addAll(s.architectureLinks());});statementIds.addAll(c.preservedStatementIds());c.questionProposals().forEach(q->questionIds.add(q.id()));questionIds.addAll(c.preservedQuestionIds());});
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
        var statements=new ArrayList<Statement>();int index=0;
        for(JsonNode node:array(root,"statementProposals")) {
            fields(node,"wording","provenance","sourceSpans","architectureLinks","questionDependencies","conditionalValidity");
            String wording=text(node,"wording");var spans=spans(node,"sourceSpans",input);
            var provenance=Statement.Provenance.valueOf(text(node,"provenance"));
            if(provenance==Statement.Provenance.HUMAN_DECISION) throw invalid("Model cannot create human decisions");
            if(provenance==Statement.Provenance.ORIGINAL && (spans.isEmpty() || !wording.equals(String.join("",spans.stream().map(Statement.SourceSpan::exactText).toList())))) throw invalid("ORIGINAL wording must exactly match source spans");
            statements.add(new Statement(id("s",input,index++,node),wording,spans,provenance,refs(node,"architectureLinks",links),refs(node,"questionDependencies",questionRefs),nullableText(node,"conditionalValidity"),Statement.EditingOrigin.MODEL,"UNREVIEWED"));
        }
        var questions=new ArrayList<DecisionQuestion>();index=0;
        for(JsonNode node:array(root,"questionProposals")) {
            fields(node,"subject","dimension","scope","wording","rationale","affectedStatementIds","sourceSpans","nodeIds","edgeIds","answerSchema","prerequisites","consequences");
            var schema=node.get("answerSchema");fields(schema,"kind","options","unit","minimum","maximum");
            var kind=DecisionQuestion.AnswerSchema.Kind.valueOf(text(schema,"kind"));var options=strings(schema,"options");
            if((kind==DecisionQuestion.AnswerSchema.Kind.SINGLE_CHOICE || kind==DecisionQuestion.AnswerSchema.Kind.MULTIPLE_CHOICE) && options.size()<2) throw invalid("Choice question needs at least two options");
            Double min=number(schema,"minimum"),max=number(schema,"maximum");if(min!=null && max!=null && min>max) throw invalid("Invalid numeric range");
            var discovery=new DecisionQuestion.Discovery(input.nodeId(),input.nodeDescription(),text(node,"rationale"),spans(node,"sourceSpans",input),refs(node,"nodeIds",nodes),refs(node,"edgeIds",input.boundaryEdges().keySet()));
            questions.add(new DecisionQuestion(id("q",input,index++,node),new DecisionQuestion.Key(text(node,"subject"),text(node,"dimension"),text(node,"scope")),text(node,"wording"),List.of(discovery),refs(node,"affectedStatementIds",statementRefs),new DecisionQuestion.AnswerSchema(kind,options,nullableText(schema,"unit"),min,max),refs(node,"prerequisites",questionRefs),List.of(),text(node,"consequences"),DecisionQuestion.State.OPEN));
        }
        var conflicts=new ArrayList<ValidationReport.Finding>();
        for(JsonNode node:array(root,"conflictCandidates")) {
            fields(node,"kind","code","message","statementIds","sourceSpans");
            conflicts.add(new ValidationReport.Finding(ValidationReport.Kind.valueOf(text(node,"kind")),text(node,"code"),text(node,"message"),refs(node,"statementIds",statementRefs),spans(node,"sourceSpans",input)));
        }
        return new NodeSynthesisResult(input.nodeId(),text(root,"summary"),statements,preservedStatements,questions,preservedQuestions,spans(root,"uncoveredSourceRefs",input),conflicts);
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
