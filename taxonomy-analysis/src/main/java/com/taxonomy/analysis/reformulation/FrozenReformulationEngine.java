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
        return synthesize(baseline,priorStatements,answers,openDecisions,ReformulationStepExecutor.direct());
    }
    public ReformulationDocument synthesize(ReformulationBaseline baseline,List<Statement> priorStatements,List<DecisionAnswer> answers,List<DecisionQuestion> openDecisions,ReformulationStepExecutor steps) {
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
            var input=new NodeSynthesisInput(baseline,step.nodeId(),node==null || node.parentIds().isEmpty()?null:node.parentIds().getFirst(),json.writeValueAsString(data),sourceSpans,retainedStatements,children,boundariesFor(step,node,children,boundary),answers,openDecisions,"Preserve all original anchors and child IDs verbatim; additions are unreviewed.");
            var result=steps.execute("NODE",input,NodeSynthesisResult.class,()->nodes.synthesize(input));
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
    /** Reword only invalidated sections, bottom-up. Independent section records remain byte-stable. */
    public ReformulationDocument synthesizeAffected(ReformulationBaseline baseline, ReformulationDocument before,
            List<DecisionAnswer> answers, ReformulationImpact impact) {
        return synthesizeAffected(baseline,before,answers,impact,ReformulationStepExecutor.direct());
    }
    public ReformulationDocument synthesizeAffected(ReformulationBaseline baseline, ReformulationDocument before,
            List<DecisionAnswer> answers, ReformulationImpact impact, ReformulationStepExecutor steps) {
        var descriptions=new TreeMap<String,String>();var parents=new TreeMap<String,Set<String>>();
        collect(json.readTree(baseline.frozenContext().getOrDefault("catalogue","[]")),null,descriptions,parents,new LinkedHashSet<>());
        var sections=new LinkedHashMap<String,Section>();before.sections().forEach(s->sections.put(s.id(),s));
        var statements=new LinkedHashMap<String,Statement>();before.statements().forEach(s->statements.put(s.id(),s));
        var questions=new LinkedHashMap<String,DecisionQuestion>();before.questions().forEach(q->questions.put(q.id(),q));
        var boundaries=new TreeMap<String,String>();
        for(var edge:json.readTree(baseline.frozenContext().getOrDefault("relationMappings","[]")))boundaries.put("edge-"+edge.path("id").asText(),edge.toString());
        var results=new LinkedHashMap<String,NodeSynthesisResult>();
        var findings=new ArrayList<>(before.validation().findings());
        var visiting=new HashSet<String>();
        for(var section:before.sections()) rewordSection(section.id(),baseline,sections,statements,questions,descriptions,parents,boundaries,answers,impact,results,findings,visiting,steps);
        StringBuilder text=new StringBuilder();var rendered=new HashSet<String>();
        for(var section:sections.values()) {
            text.append(section.title()).append("\n").append(section.summary()).append("\n\n");
            for(String id:section.statementIds()) {
                var statement=statements.get(id);
                if(statement!=null && !"REJECTED".equals(statement.reviewState()) && rendered.add(id))
                    text.append(statement.wording()).append(" [").append(statement.provenance()).append("]\n\n");
            }
        }
        for(var statement:statements.values())if(!"REJECTED".equals(statement.reviewState()) && rendered.add(statement.id()))
            text.append(statement.wording()).append(" [").append(statement.provenance()).append("]\n\n");
        text.append(baseline.language().equals("de")?"Original (unverändert)\n":"Original (unchanged)\n").append(baseline.originalText());
        return new ReformulationDocument(text.toString(),List.copyOf(sections.values()),List.copyOf(statements.values()),List.copyOf(questions.values()),
                new ValidationReport(findings),List.copyOf(results.values()),before.reconciliation());
    }
    private NodeSynthesisResult rewordSection(String id,ReformulationBaseline baseline,Map<String,Section> sections,
            Map<String,Statement> statements,Map<String,DecisionQuestion> questions,Map<String,String> descriptions,
            Map<String,Set<String>> parents,Map<String,String> boundaries,List<DecisionAnswer> answers,ReformulationImpact impact,
            Map<String,NodeSynthesisResult> results,List<ValidationReport.Finding> findings,Set<String> visiting,ReformulationStepExecutor steps) {
        if(results.containsKey(id))return results.get(id);
        if(!visiting.add(id))throw new IllegalArgumentException("Section cycle: "+id);
        var section=sections.get(id);if(section==null)throw new IllegalArgumentException("Unknown section: "+id);
        var children=new ArrayList<NodeSynthesisResult>();
        for(String child:section.children())children.add(rewordSection(child,baseline,sections,statements,questions,descriptions,parents,boundaries,answers,impact,results,findings,visiting,steps));
        var localStatementIds=new LinkedHashSet<>(section.statementIds());
        var localQuestions=questions.values().stream().filter(q->section.questionIds().contains(q.id()) || q.key().scope().equals(id)
                || Set.of("global","@document","*").contains(q.key().scope().toLowerCase(Locale.ROOT))
                || !Collections.disjoint(q.affectedStatementIds(),localStatementIds)).toList();
        var localQIds=new LinkedHashSet<String>();localQuestions.forEach(q->localQIds.addAll(q.referenceIds()));
        boolean changed;
        do {int size=localQIds.size();for(var q:questions.values())if(!Collections.disjoint(q.referenceIds(),localQIds))localQIds.addAll(q.prerequisites());changed=size!=localQIds.size();}while(changed);
        localQuestions=questions.values().stream().filter(q->!Collections.disjoint(q.referenceIds(),localQIds)).toList();
        localQuestions.forEach(q->localStatementIds.addAll(q.affectedStatementIds()));
        var direct=localStatementIds.stream().map(statements::get).filter(Objects::nonNull).toList();
        var localBoundary=new TreeMap<String,String>();boundaries.forEach((edgeId,value)->{
            var edge=json.readTree(value);if(id.equals(edge.path("sourceCode").asText()) || id.equals(edge.path("targetCode").asText()) || impact.boundaryEdgeIds().contains(edgeId))localBoundary.put(edgeId,value);
        });
        NodeSynthesisResult result;
        if(impact.sectionIds().contains(id)) {
            var metadata=new LinkedHashMap<String,Object>();metadata.put("section",section);metadata.put("frozenDescription",descriptions.getOrDefault(id,"Synthetic layout section"));
            var parent=sections.values().stream().filter(s->s.children().contains(id)).map(Section::id).findFirst().orElse(null);
            var input=new NodeSynthesisInput(baseline,id,parent,json.writeValueAsString(metadata),anchors(baseline.originalText()),direct,children,localBoundary,
                    answers.stream().filter(a->localQIds.contains(a.questionId())).toList(),localQuestions,
                    "Only reword this affected section. Preserve human wording and all retained evidence. REJECTED additions must not be reintroduced or paraphrased. Independent branch records stay unchanged.");
            var generated=steps.execute("REWORD",input,NodeSynthesisResult.class,()->nodes.synthesize(input));
            var rejected=statements.values().stream().filter(s->"REJECTED".equals(s.reviewState())).map(s->s.wording().strip()).collect(java.util.stream.Collectors.toSet());
            var additions=new ArrayList<Statement>();
            for(var statement:generated.statementProposals()) {
                if(rejected.contains(statement.wording().strip())) {
                    statement=new Statement(statement.id(),statement.wording(),statement.sourceSpans(),statement.provenance(),statement.architectureLinks(),statement.questionDependencies(),
                            statement.conditionalValidity(),statement.editingOrigin(),"REJECTED");
                    findings.add(new ValidationReport.Finding(ValidationReport.Kind.CONFLICT,"REJECTED_ADDITION_REINTRODUCED","Candidate repeated rejected wording; retained as rejected evidence",List.of(statement.id()),List.of()));
                }
                additions.add(statement);statements.put(statement.id(),statement);
            }
            generated.questionProposals().forEach(q->questions.put(q.id(),q));
            var sectionStatements=new LinkedHashSet<>(section.statementIds());additions.forEach(s->sectionStatements.add(s.id()));
            var sectionQuestions=new LinkedHashSet<>(section.questionIds());generated.questionProposals().forEach(q->sectionQuestions.add(q.id()));
            String summary=generated.summary();
            for(String wording:rejected)if(summary.contains(wording))summary=section.summary();
            sections.put(id,new Section(section.id(),section.taxonomyCode(),section.title(),summary,section.children(),List.copyOf(sectionStatements),List.copyOf(sectionQuestions)));
            findings.addAll(generated.conflictCandidates());
            result=new NodeSynthesisResult(id,summary,additions,generated.preservedStatementIds(),generated.questionProposals(),generated.preservedQuestionIds(),generated.uncoveredSourceRefs(),generated.conflictCandidates());
        } else result=new NodeSynthesisResult(id,section.summary(),direct,List.of(),localQuestions,List.of(),List.of(),List.of());
        // Ancestors consume completed details including original preserved evidence, not only newly generated additions.
        var allStatements=new LinkedHashMap<String,Statement>();direct.forEach(s->allStatements.put(s.id(),s));
        children.forEach(c->c.statementProposals().forEach(s->allStatements.put(s.id(),s)));result.statementProposals().forEach(s->allStatements.put(s.id(),s));
        var allQuestions=new LinkedHashMap<String,DecisionQuestion>();localQuestions.forEach(q->allQuestions.put(q.id(),q));
        children.forEach(c->c.questionProposals().forEach(q->allQuestions.put(q.id(),q)));result.questionProposals().forEach(q->allQuestions.put(q.id(),q));
        result=new NodeSynthesisResult(id,result.summary(),List.copyOf(allStatements.values()),result.preservedStatementIds(),List.copyOf(allQuestions.values()),result.preservedQuestionIds(),result.uncoveredSourceRefs(),result.conflictCandidates());
        results.put(id,result);visiting.remove(id);return result;
    }
    /** Include only directed edges touching this step, collapsed terminals or carried child evidence. */
    private Map<String,String> boundariesFor(WalkUpPlanner.Step step, WalkUpPlanner.Node node,
            List<NodeSynthesisResult> children, Map<String,String> boundary) {
        var selected = new HashSet<String>();
        selected.add(step.nodeId());
        selected.addAll(step.terminalIds());
        selected.addAll(step.directNodeIds());
        selected.addAll(step.childTaskIds());
        if (node != null) selected.addAll(node.parentIds());
        for (var child : children) {
            child.statementProposals().forEach(s -> selected.addAll(s.architectureLinks()));
            child.questionProposals().forEach(q -> q.discoveries().forEach(d -> {
                selected.addAll(d.nodeIds());
                selected.addAll(d.edgeIds());
            }));
        }
        var local = new TreeMap<String,String>();
        boundary.forEach((id, value) -> {
            var edge = json.readTree(value);
            if (selected.contains(id) || selected.contains(edge.path("sourceCode").asText())
                    || selected.contains(edge.path("targetCode").asText())) local.put(id, value);
        });
        return local;
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
