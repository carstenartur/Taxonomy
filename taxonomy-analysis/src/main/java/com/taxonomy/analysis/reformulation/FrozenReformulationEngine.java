package com.taxonomy.analysis.reformulation;

import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.reformulation.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.*;
import java.util.*;

/** Reads only baseline content: never consults today's catalogue or workspace architecture. */
@Service
public class FrozenReformulationEngine {
    private final NodeReformulationService nodes;
    private final ObjectMapper json;
    public FrozenReformulationEngine(NodeReformulationService nodes,ObjectMapper json) {this.nodes=nodes;this.json=json;}
    public ReformulationDocument synthesize(ReformulationBaseline baseline,List<DecisionAnswer> answers,List<DecisionQuestion> openDecisions) {
        return synthesize(baseline,List.of(),answers,openDecisions);
    }
    /** Existing statements remain evidence for retained questions until deliberate reconciliation. */
    public ReformulationDocument synthesize(ReformulationBaseline baseline,List<Statement> priorStatements,List<DecisionAnswer> answers,List<DecisionQuestion> openDecisions) {
        var catalogue=json.readTree(baseline.frozenContext().getOrDefault("catalogue","[]"));
        if(!catalogue.isArray()) throw new IllegalArgumentException("Malformed frozen catalogue");
        var descriptions=new TreeMap<String,String>();var parents=new TreeMap<String,Set<String>>();
        collect(catalogue,null,descriptions,parents,new LinkedHashSet<>());
        var payload=json.readTree(baseline.snapshotPayload());
        var raw=payload.has("rawScores")?payload.path("rawScores"):payload.path("scores");
        var scores=new HashMap<String,Integer>();raw.properties().forEach(e->scores.put(e.getKey(),e.getValue().asInt()));
        var mappings=json.readTree(baseline.frozenContext().getOrDefault("elementMappings","[]"));
        for(var mapping:mappings) if(mapping.has("directScore")) scores.put(mapping.path("nodeCode").asText(),mapping.path("directScore").asInt());
        var hierarchy=descriptions.keySet().stream().map(id->new WalkUpPlanner.Node(id,parents.get(id).stream().sorted().toList(),descriptions.get(id),scores.getOrDefault(id,0),List.<Statement>of())).toList();
        var plan=new WalkUpPlanner().plan(hierarchy);var byId=new HashMap<String,WalkUpPlanner.Node>();hierarchy.forEach(n->byId.put(n.id(),n));
        var sourceSpans=anchors(baseline.originalText());var sourceStatements=new ArrayList<Statement>();
        var verbatimSource=baseline.originalText().isEmpty()?List.<Statement.SourceSpan>of():List.of(new Statement.SourceSpan(0,baseline.originalText().length(),baseline.originalText()));
        for(var span:verbatimSource) sourceStatements.add(new Statement("source-"+StableIdentityHash.sha256(baseline.originalTextHash()+":"+span.start()).substring(0,24),span.exactText(),List.of(span),Statement.Provenance.ORIGINAL,List.of(),List.of(),null,Statement.EditingOrigin.SOURCE,"UNREVIEWED"));
        var boundary=new TreeMap<String,String>();
        for(var edge:json.readTree(baseline.frozenContext().getOrDefault("relationMappings","[]"))) {
            String id="edge-"+edge.path("id").asText();if(boundary.put(id,edge.toString())!=null) throw new IllegalArgumentException("Duplicate frozen relation ID");
        }
        var statements=new LinkedHashMap<String,Statement>();sourceStatements.forEach(s->statements.put(s.id(),s));
        priorStatements.forEach(s->statements.put(s.id(),s));
        var retainedStatements=List.copyOf(statements.values());
        var results=new LinkedHashMap<String,NodeSynthesisResult>();
        var questions=new LinkedHashMap<String,DecisionQuestion>();openDecisions.forEach(q->questions.put(q.id(),q));
        var sections=new ArrayList<Section>();var findings=new ArrayList<ValidationReport.Finding>();
        for(var step:plan) {
            var node=byId.get(step.nodeId());var children=step.childTaskIds().stream().map(results::get).toList();
            var data=new LinkedHashMap<String,Object>();data.put("current",node==null?"Synthetic source-based document root":node);
            data.put("terminalContributions",step.terminalIds().stream().map(byId::get).toList());data.put("directParentContributions",step.directNodeIds().stream().map(byId::get).toList());
            var input=new NodeSynthesisInput(baseline,step.nodeId(),node==null || node.parentIds().isEmpty()?null:node.parentIds().getFirst(),json.writeValueAsString(data),sourceSpans,retainedStatements,children,boundary,answers,openDecisions,"Preserve all original anchors and child IDs verbatim; additions are unreviewed.");
            var result=nodes.synthesize(input);
            var carriedStatements=new LinkedHashMap<String,Statement>();var carriedQuestions=new LinkedHashMap<String,DecisionQuestion>();
            retainedStatements.forEach(s->carriedStatements.put(s.id(),s));openDecisions.forEach(q->carriedQuestions.put(q.id(),q));
            children.forEach(c->{c.statementProposals().forEach(s->carriedStatements.put(s.id(),s));c.questionProposals().forEach(q->carriedQuestions.put(q.id(),q));});
            result.statementProposals().forEach(s->{carriedStatements.put(s.id(),s);statements.putIfAbsent(s.id(),s);});
            result.questionProposals().forEach(q->{carriedQuestions.put(q.id(),q);questions.putIfAbsent(q.id(),q);});
            var complete=new NodeSynthesisResult(result.nodeId(),result.summary(),List.copyOf(carriedStatements.values()),result.preservedStatementIds(),List.copyOf(carriedQuestions.values()),result.preservedQuestionIds(),result.uncoveredSourceRefs(),result.conflictCandidates());
            results.put(step.nodeId(),complete);
            sections.add(new Section(step.nodeId(),node==null?null:root(node.id(),parents),node==null?"Unmapped source":node.id(),result.summary(),step.childTaskIds(),List.copyOf(carriedStatements.keySet()),List.copyOf(carriedQuestions.keySet())));
            findings.addAll(result.conflictCandidates());
            for(var span:result.uncoveredSourceRefs()) findings.add(new ValidationReport.Finding(ValidationReport.Kind.UNMAPPED_SOURCE,"MODEL_UNCOVERED","Source remains visible for review",List.of(),List.of(span)));
        }
        // Coverage is structurally preserved, not semantically certified; no-match remainder stays explicit.
        if(plan.getFirst().nodeId().equals(WalkUpPlanner.DOCUMENT_ROOT)) findings.add(new ValidationReport.Finding(ValidationReport.Kind.UNMAPPED_SOURCE,"NO_TAXONOMY_MATCH","Source-based offer without an invented taxonomy path",sourceStatements.stream().map(Statement::id).toList(),sourceSpans));
        findings.add(new ValidationReport.Finding(ValidationReport.Kind.SEMANTIC_REVIEW,"UNREVIEWED_GENERATION","Generated offer requires human semantic review",List.of(),List.of()));
        String text=String.join("\n\n",statements.values().stream().map(Statement::wording).toList());
        return new ReformulationDocument(text,sections,List.copyOf(statements.values()),List.copyOf(questions.values()),new ValidationReport(findings),List.copyOf(results.values()));
    }
    private static String root(String id,Map<String,Set<String>> parents) {while(!parents.get(id).isEmpty())id=parents.get(id).stream().sorted().findFirst().orElseThrow();return id;}
    /** Exact clause anchors preserve punctuation, negation and numbers/units without splitting decimals. */
    private static List<Statement.SourceSpan> anchors(String text) {
        var spans=new ArrayList<Statement.SourceSpan>();int start=0;
        var boundaries=java.util.regex.Pattern.compile("[.!?;,](?=\\s|$)").matcher(text);
        while(boundaries.find()) {int end=boundaries.end();spans.add(new Statement.SourceSpan(start,end,text.substring(start,end)));start=end;}
        if(start<text.length())spans.add(new Statement.SourceSpan(start,text.length(),text.substring(start)));
        return List.copyOf(spans);
    }
    private static void collect(JsonNode array,String nestedParent,Map<String,String> descriptions,Map<String,Set<String>> parents,Set<String> path) {
        for(var item:array) {
            String id=item.path("code").asText();if(id.isBlank() || id.equals(WalkUpPlanner.DOCUMENT_ROOT))throw new IllegalArgumentException("Malformed frozen node ID");
            if(!path.add(id))throw new IllegalArgumentException("Hierarchy cycle: "+path+" -> "+id);
            String declared=item.path("parentCode").isString()?item.path("parentCode").asText():null;
            if(nestedParent!=null && declared!=null && !declared.equals(nestedParent))throw new IllegalArgumentException("Conflicting hierarchy parent for "+id);
            var description=item.deepCopy();((tools.jackson.databind.node.ObjectNode)description).remove("children");
            String previous=descriptions.putIfAbsent(id,description.toString());
            if(previous!=null && !previous.equals(description.toString()))throw new IllegalArgumentException("Conflicting duplicate catalogue node "+id);
            parents.computeIfAbsent(id,k->new TreeSet<>());String parent=nestedParent==null?declared:nestedParent;if(parent!=null)parents.get(id).add(parent);
            var children=item.path("children");if(!children.isMissingNode() && !children.isNull() && !children.isArray())throw new IllegalArgumentException("Malformed children for "+id);
            if(children.isArray())collect(children,id,descriptions,parents,path);path.remove(id);
        }
    }
}
