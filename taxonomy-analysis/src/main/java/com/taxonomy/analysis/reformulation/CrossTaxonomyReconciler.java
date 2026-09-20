package com.taxonomy.analysis.reformulation;

import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.reformulation.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.math.BigDecimal;

/** Phase B: immutable round snapshots, deterministic decision identity and bounded affected synthesis. */
@Service
public class CrossTaxonomyReconciler {
    private final NodeReformulationService nodes;
    private final ObjectMapper json;
    public CrossTaxonomyReconciler(NodeReformulationService nodes,ObjectMapper json){this.nodes=nodes;this.json=json;}
    public ReformulationDocument reconcile(ReformulationBaseline baseline,ReformulationDocument phaseA,List<DecisionAnswer> answers,List<DecisionQuestion> priorQuestions) {
        // Standalone callers freeze once here; production dispatch persists these bytes before Phase A.
        var context=new TreeMap<>(baseline.frozenContext());context.putAll(ReconcilePromptBuilder.freeze(context));
        baseline=new ReformulationBaseline(baseline.scope(),baseline.sourceVersionId(),baseline.originalText(),baseline.originalTextHash(),baseline.snapshotId(),baseline.snapshotPayload(),context,baseline.language(),baseline.algorithmVersion());
        var findings=new ArrayList<>(phaseA.validation().findings());
        var current=canonicalize(preserveSource(baseline,phaseA),answers,priorQuestions,findings);
        int rounds=0;var touched=new LinkedHashSet<String>();
        var boundary=boundaries(baseline,current);
        for(int round=1;round<=2;round++) {
            rounds=round;var input=input(baseline,round,current,answers,boundary);
            var review=nodes.reconcile(input);findings.addAll(review.findings());
            current=resolve(current,review.sourceResolutions(),answers,findings);
            if(review.affectedSectionIds().isEmpty())break;
            current=reword(baseline,current,review,answers,boundary,touched);
            current.nodeResults().forEach(n->{findings.addAll(n.conflictCandidates());n.uncoveredSourceRefs().forEach(span->findings.add(new ValidationReport.Finding(ValidationReport.Kind.UNMAPPED_SOURCE,"RECONCILED_UNCOVERED","Original remains visible for review",List.of(),List.of(span))));});
            current=canonicalize(current,answers,priorQuestions,findings);
            if(round==2)findings.add(new ValidationReport.Finding(ValidationReport.Kind.CONFLICT,"RECONCILIATION_LIMIT","Two reconciliation rounds exhausted; proposed changes and residual differences require human review",List.of(),List.of()));
        }
        current=layout(baseline,current,findings,boundary,answers);
        findings.addAll(new ReformulationCoverageValidator().validate(baseline,phaseA,current).findings());
        return new ReformulationDocument(current.text(),current.sections(),current.statements(),current.questions(),new ValidationReport(distinct(findings)),current.nodeResults(),new ReformulationDocument.ReconciliationTrace(phaseA.nodeResults(),rounds,List.copyOf(touched)));
    }
    private ReformulationDocument canonicalize(ReformulationDocument doc,List<DecisionAnswer> answers,List<DecisionQuestion> prior,List<ValidationReport.Finding> findings) {
        var groups=new ArrayList<List<DecisionQuestion>>();
        var priorIds=new HashSet<String>();prior.forEach(q->priorIds.add(q.id()));
        var answered=new HashSet<String>();answers.forEach(a->answered.add(a.questionId()));
        var sorted=doc.questions().stream().sorted(Comparator.<DecisionQuestion>comparingInt(q->priorIds.contains(q.id())?0:1)
            .thenComparingInt(q->q.state()==DecisionQuestion.State.ANSWERED || q.referenceIds().stream().anyMatch(answered::contains)?0:1).thenComparing(DecisionQuestion::id)).toList();
        for(var q:sorted) {
            var sameKey=groups.stream().filter(g->g.getFirst().key().equals(q.key())).toList();
            var group=sameKey.stream().filter(g->DecisionQuestion.compatible(g.getFirst().answerSchema(),q.answerSchema())).findFirst();
            if(group.isPresent())group.get().add(q);
            else {if(!sameKey.isEmpty())findings.add(new ValidationReport.Finding(ValidationReport.Kind.CONFLICT,"INCOMPATIBLE_ANSWER_CONTRACTS","Same semantic key has incompatible answer contracts; retained separately",q.affectedStatementIds(),List.of()));groups.add(new ArrayList<>(List.of(q)));}
        }
        var canonical=new ArrayList<DecisionQuestion>();
        for(var group:groups) {
            var first=group.getFirst();var ds=new ArrayList<DecisionQuestion.Discovery>();var affected=new ArrayList<String>();var aliases=new LinkedHashSet<String>();
            var origins=new ArrayList<DecisionQuestion.Origin>();var resolutions=new ArrayList<DecisionQuestion.SourceResolution>();var pre=new ArrayList<String>();var dep=new ArrayList<String>();
            for(var q:group){ds.addAll(q.discoveries());affected.addAll(q.affectedStatementIds());aliases.addAll(q.referenceIds());origins.addAll(q.origins());resolutions.addAll(q.sourceResolutions());pre.addAll(q.prerequisites());dep.addAll(q.dependentQuestionIds());}
            if(group.size()>1)group.forEach(q->origins.add(q.origin()));aliases.remove(first.id());
            var q=new DecisionQuestion(first.id(),first.key(),first.wording(),distinct(ds),distinct(affected),first.answerSchema(),distinct(pre),distinct(dep),first.consequences(),first.state(),List.copyOf(aliases),distinct(origins),distinct(resolutions));
            canonical.add(withState(q,answers,findings));
        }
        return replaceQuestions(doc,canonical);
    }
    private static DecisionQuestion withState(DecisionQuestion q,List<DecisionAnswer> answers,List<ValidationReport.Finding> findings) {
        var relevant=DecisionAnswer.active(answers).stream().filter(a->q.referenceIds().contains(a.questionId())).toList();
        // Multiple origins of the same value are not independent evidence; only disagreement matters.
        var values=new HashSet<Set<?>>();relevant.stream().filter(a->a.state()==DecisionQuestion.State.ANSWERED).forEach(a->values.add(humanAnswerComparisonKey(q.answerSchema(),a)));
        q.sourceResolutions().forEach(r->values.add(answerComparisonKey(q.answerSchema(),r.values())));
        boolean conflict=values.size()>1 || q.state()==DecisionQuestion.State.CONFLICT || relevant.stream().anyMatch(a->a.state()==DecisionQuestion.State.CONFLICT);
        var state=conflict?DecisionQuestion.State.CONFLICT:!q.sourceResolutions().isEmpty()?DecisionQuestion.State.ANSWERED:
            !values.isEmpty()?DecisionQuestion.State.ANSWERED:relevant.isEmpty()?q.state():relevant.getLast().state();
        if(conflict)findings.add(new ValidationReport.Finding(ValidationReport.Kind.CONFLICT,"CONFLICTING_ANSWERS","Conflicting human/source evidence retained; no last-writer resolution",q.affectedStatementIds(),q.sourceResolutions().stream().flatMap(r->r.sourceSpans().stream()).distinct().toList()));
        if(state==q.state())return q;
        var origins=new ArrayList<>(q.origins());origins.add(q.origin());
        return new DecisionQuestion(q.id(),q.key(),q.wording(),q.discoveries(),q.affectedStatementIds(),q.answerSchema(),q.prerequisites(),q.dependentQuestionIds(),q.consequences(),state,q.aliases(),distinct(origins),q.sourceResolutions());
    }
    private static Set<?> humanAnswerComparisonKey(DecisionQuestion.AnswerSchema schema,DecisionAnswer answer) {
        var key=new HashSet<Object>(answerComparisonKey(schema,answer.values()));
        if(answer.otherText()!=null && !answer.otherText().isBlank())key.add(List.of("OTHER_TEXT",answer.otherText()));
        return Set.copyOf(key);
    }
    /** Normalize comparison keys only: stored human/source strings and provenance stay exact. */
    private static Set<?> answerComparisonKey(DecisionQuestion.AnswerSchema schema,List<String> values) {
        if(schema.kind()!=DecisionQuestion.AnswerSchema.Kind.NUMBER)return Set.copyOf(values);
        return values.stream().map(CrossTaxonomyReconciler::numericComparisonKey)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    private static BigDecimal numericComparisonKey(String raw) {
        String value=raw.trim();
        // Preserve finite literal forms already accepted by the existing source-resolution parser.
        if(value.matches(".*[dDfF]$"))value=value.substring(0,value.length()-1);
        if(value.matches("[+-]?0[xX].*"))return new BigDecimal(Double.parseDouble(value)).stripTrailingZeros();
        // Decimal/exponent values stay exact; a double conversion would erase real differences.
        return new BigDecimal(value).stripTrailingZeros();
    }
    private static ReformulationDocument resolve(ReformulationDocument doc,Map<String,DecisionQuestion.SourceResolution> resolutions,List<DecisionAnswer> answers,List<ValidationReport.Finding> findings) {
        var questions=new ArrayList<DecisionQuestion>();
        for(var q:doc.questions()) {
            var r=resolutions.get(q.id());if(r!=null){var evidence=new ArrayList<>(q.sourceResolutions());evidence.add(r);var origins=new ArrayList<>(q.origins());origins.add(q.origin());
                q=new DecisionQuestion(q.id(),q.key(),q.wording(),q.discoveries(),q.affectedStatementIds(),q.answerSchema(),q.prerequisites(),q.dependentQuestionIds(),q.consequences(),q.state(),q.aliases(),distinct(origins),distinct(evidence));}
            questions.add(withState(q,answers,findings));
        }
        return replaceQuestions(doc,questions);
    }
    private static ReformulationDocument replaceQuestions(ReformulationDocument doc,List<DecisionQuestion> questions) {
        var byId=new HashMap<String,DecisionQuestion>();questions.forEach(q->q.referenceIds().forEach(id->byId.put(id,q)));
        var sections=doc.sections().stream().map(s->new Section(s.id(),s.taxonomyCode(),s.title(),s.summary(),s.children(),s.statementIds(),s.questionIds().stream().map(id->required(byId,id).id()).distinct().toList())).toList();
        var results=doc.nodeResults().stream().map(n->new NodeSynthesisResult(n.nodeId(),n.summary(),n.statementProposals(),n.preservedStatementIds(),
            n.questionProposals().stream().map(q->required(byId,q.id())).distinct().toList(),n.preservedQuestionIds().stream().map(id->required(byId,id).id()).distinct().toList(),n.uncoveredSourceRefs(),n.conflictCandidates())).toList();
        // Statements retain exact provenance and dependencies; aliases keep their original references valid.
        return new ReformulationDocument(doc.text(),sections,doc.statements(),questions,doc.validation(),results);
    }
    private static DecisionQuestion required(Map<String,DecisionQuestion> byId,String id){var q=byId.get(id);if(q==null)throw new IllegalArgumentException("Unknown question reference");return q;}
    private Map<String,String> boundaries(ReformulationBaseline b,ReformulationDocument d) {
        var relevant=new HashSet<String>();d.sections().forEach(s->relevant.add(s.id()));d.statements().forEach(s->relevant.addAll(s.architectureLinks()));
        d.questions().forEach(q->q.discoveries().forEach(v->relevant.addAll(v.nodeIds())));var edges=new TreeMap<String,String>();
        for(var edge:json.readTree(b.frozenContext().getOrDefault("relationMappings","[]"))) {
            if(relevant.contains(edge.path("sourceCode").asText()) || relevant.contains(edge.path("targetCode").asText())) {
                String id="edge-"+edge.path("id").asText();if(edges.put(id,edge.toString())!=null)throw new IllegalArgumentException("Duplicate boundary edge");
            }
        }return edges;
    }
    private static ReconciliationInput input(ReformulationBaseline b,int round,ReformulationDocument d,List<DecisionAnswer> answers,Map<String,String> boundaries) {
        var shared=new TreeMap<String,List<String>>();for(var s:d.statements())for(String link:s.architectureLinks())shared.computeIfAbsent(link,k->new ArrayList<>()).add(s.id());
        shared.entrySet().removeIf(e->e.getValue().size()<2);
        return new ReconciliationInput(b,round,d.sections(),d.statements(),d.questions(),answers,boundaries,shared,
            d.statements().stream().filter(s->s.provenance()==Statement.Provenance.ORIGINAL).map(Statement::id).toList());
    }
    private ReformulationDocument reword(ReformulationBaseline baseline,ReformulationDocument before,ReconciliationResult review,List<DecisionAnswer> answers,Map<String,String> boundary,Set<String> touched) {
        var affected=new LinkedHashSet<>(review.affectedSectionIds());boolean changed;
        do {changed=false;for(var s:before.sections())if(s.children().stream().anyMatch(affected::contains))changed|=affected.add(s.id());}while(changed);
        touched.addAll(affected);
        var sections=new LinkedHashMap<String,Section>();before.sections().forEach(s->sections.put(s.id(),s));
        var results=new LinkedHashMap<String,NodeSynthesisResult>();before.nodeResults().forEach(n->results.put(n.nodeId(),n));
        var statements=new LinkedHashMap<String,Statement>();before.statements().forEach(s->statements.put(s.id(),s));
        var questions=new LinkedHashMap<String,DecisionQuestion>();before.questions().forEach(q->questions.put(q.id(),q));
        // Peers remain frozen for the round; ancestors receive completed child results in DAG order.
        var ordered=new ArrayList<String>();var visiting=new HashSet<String>();for(String id:affected)order(id,sections,affected,visiting,ordered);
        for(String id:ordered) {
            var section=sections.get(id);var localStatements=before.statements().stream().filter(s->section.statementIds().contains(s.id()) || s.provenance()==Statement.Provenance.ORIGINAL).toList();
            var localQuestions=before.questions().stream().filter(q->!Collections.disjoint(q.referenceIds(),section.questionIds()) || !Collections.disjoint(q.affectedStatementIds(),section.statementIds()) || q.key().scope().equals("global")).toList();
            var localQIds=new HashSet<String>();localQuestions.forEach(q->localQIds.addAll(q.referenceIds()));
            var localAnswers=answers.stream().filter(a->localQIds.contains(a.questionId())).toList();
            var children=section.children().stream().map(results::get).filter(Objects::nonNull).toList();
            String parent=before.sections().stream().filter(s->s.children().contains(id)).map(Section::id).sorted().findFirst().orElse(null);
            var selected=new TreeSet<String>();selected.add(id);selected.addAll(section.children());if(parent!=null)selected.add(parent);
            localStatements.forEach(v->selected.addAll(v.architectureLinks()));localQuestions.forEach(q->q.discoveries().forEach(d->selected.addAll(d.nodeIds())));
            children.forEach(c->c.statementProposals().forEach(v->selected.addAll(v.architectureLinks())));
            localQuestions.forEach(q->q.discoveries().forEach(d->selected.addAll(d.edgeIds())));
            var localEdges=new TreeMap<String,String>();boundary.forEach((k,v)->{var e=json.readTree(v);
                if(selected.contains(e.path("sourceCode").asText()) || selected.contains(e.path("targetCode").asText()) || selected.contains(k))localEdges.put(k,v);});
            var frozenContext=new ReconcilePromptBuilder(json).scopedContext(baseline,selected);
            var in=new NodeSynthesisInput(baseline,id,parent,json.writeValueAsString(Map.of("section",section,"frozenNodeContext",frozenContext,"reconciliationFindings",review.findings().stream().filter(f->f.statementIds().isEmpty() || !Collections.disjoint(f.statementIds(),section.statementIds())).toList())),sourceSpans(baseline),localStatements,children,localEdges,localAnswers,localQuestions,"Preserve exact original and prior evidence. Re-synthesize only this affected section, keeping peer outputs frozen within the round; summarize completed child details. Resolve findings visibly; do not overwrite human/source wording.");
            var result=nodes.synthesize(in);result.statementProposals().forEach(s->statements.putIfAbsent(s.id(),s));result.questionProposals().forEach(q->questions.putIfAbsent(q.id(),q));
            var sids=new ArrayList<>(section.statementIds());sids.addAll(result.preservedStatementIds());result.statementProposals().forEach(s->sids.add(s.id()));
            var qids=new ArrayList<>(section.questionIds());qids.addAll(result.preservedQuestionIds());result.questionProposals().forEach(q->qids.add(q.id()));
            sections.put(id,new Section(id,section.taxonomyCode(),section.title(),result.summary(),section.children(),distinct(sids),distinct(qids)));
            results.put(id,new NodeSynthesisResult(id,result.summary(),distinct(sids).stream().map(statements::get).toList(),result.preservedStatementIds(),
                distinct(qids).stream().map(qid->questions.values().stream().filter(q->q.referenceIds().contains(qid)).findFirst().orElseThrow()).distinct().toList(),result.preservedQuestionIds(),result.uncoveredSourceRefs(),result.conflictCandidates()));
        }
        return new ReformulationDocument(before.text(),List.copyOf(sections.values()),List.copyOf(statements.values()),List.copyOf(questions.values()),before.validation(),List.copyOf(results.values()));
    }
    private static void order(String id,Map<String,Section> sections,Set<String> affected,Set<String> visiting,List<String> ordered) {
        if(ordered.contains(id))return;if(!visiting.add(id))throw new IllegalArgumentException("Section cycle: "+visiting);
        var s=sections.get(id);if(s==null)throw new IllegalArgumentException("Unknown section");for(String child:s.children())if(affected.contains(child))order(child,sections,affected,visiting,ordered);visiting.remove(id);ordered.add(id);
    }
    private static List<Statement.SourceSpan> sourceSpans(ReformulationBaseline b){return b.originalText().isEmpty()?List.of():List.of(new Statement.SourceSpan(0,b.originalText().length(),b.originalText()));}
    private static ReformulationDocument preserveSource(ReformulationBaseline baseline,ReformulationDocument doc) {
        var statements=new ArrayList<>(doc.statements());var source=statements.stream().filter(s->s.provenance()==Statement.Provenance.ORIGINAL && s.wording().equals(baseline.originalText())).findFirst();
        if(source.isEmpty() && !baseline.originalText().isBlank()){var s=new Statement("source-"+StableIdentityHash.sha256(baseline.originalTextHash()+":0").substring(0,24),baseline.originalText(),sourceSpans(baseline),Statement.Provenance.ORIGINAL,List.of(),List.of(),null,Statement.EditingOrigin.SOURCE,"UNREVIEWED");statements.add(s);source=Optional.of(s);}
        return new ReformulationDocument(doc.text(),doc.sections(),statements,doc.questions(),doc.validation(),doc.nodeResults());
    }
    private ReformulationDocument layout(ReformulationBaseline baseline,ReformulationDocument doc,List<ValidationReport.Finding> findings,Map<String,String> boundary,List<DecisionAnswer> answers) {
        var statements=doc.statements();
        var sourceIds=statements.stream().filter(s->s.provenance()==Statement.Provenance.ORIGINAL).map(Statement::id).toList();
        var bySection=new LinkedHashMap<String,Section>();doc.sections().forEach(v->bySection.put(v.id(),v));
        var nested=new HashSet<String>();doc.sections().forEach(v->nested.addAll(v.children()));
        var sorted=doc.sections().stream().sorted(Comparator.comparingInt(CrossTaxonomyReconciler::layoutPriority).thenComparing(Section::id)).toList();
        var sections=new ArrayList<Section>();var placed=new HashSet<String>();
        sorted.stream().filter(v->!nested.contains(v.id())).forEach(v->place(v,bySection,placed,sections));
        sorted.forEach(v->place(v,bySection,placed,sections));
        var interfaceText=new ArrayList<String>();boundary.values().forEach(v->{var e=json.readTree(v);interfaceText.add(e.path("sourceCode").asText()+" → "+e.path("targetCode").asText()+" ["+e.path("relationType").asText()+"; "+e.path("reviewStatus").asText()+"]");});
        sections.add(new Section("@interfaces",null,"Schnittstellen / Interfaces","Directed boundary relations: "+String.join("; ",interfaceText),List.of(),statements.stream().filter(s->s.architectureLinks().stream().anyMatch(boundary::containsKey)).map(Statement::id).toList(),List.of()));
        sections.add(new Section("@cross-cutting",null,"Übergreifende Vorgaben / Cross-cutting constraints","Original restrictions apply across taxonomy views.",List.of(),sourceIds,doc.questions().stream().filter(q->q.key().scope().equals("global")).map(DecisionQuestion::id).toList()));
        sections.add(new Section("@unmapped",null,"Original / Unmapped source","Original text retained verbatim; mapping and semantic completeness require review.",List.of(),sourceIds,List.of()));
        findings.add(new ValidationReport.Finding(ValidationReport.Kind.UNMAPPED_SOURCE,"ORIGINAL_REMAINDER","Original clauses remain visibly available independently of architecture scores",sourceIds,sourceSpans(baseline)));
        var byId=new HashMap<String,Statement>();statements.forEach(s->byId.put(s.id(),s));var rendered=new HashSet<String>();var text=new StringBuilder();
        for(var section:sections){text.append(section.title()).append("\n").append("[Unreviewed summary] ").append(section.summary()).append("\n");
            for(String id:section.statementIds()){var s=byId.get(id);if(s!=null && (!sourceIds.contains(id) || section.id().equals("@unmapped")) && rendered.add(id))text.append("[").append(s.provenance()).append("] ").append(s.wording()).append("\n");}
            text.append("\n");}
        for(var s:statements)if(rendered.add(s.id()))text.append("[").append(s.provenance()).append("] ").append(s.wording()).append("\n\n");
        for(var q:doc.questions()) {
            text.append("[").append(q.state()).append("] ").append(q.wording());
            if(q.state()==DecisionQuestion.State.OPEN)text.append(" — ").append(String.join(" / ",q.answerSchema().options()));
            for(var resolution:q.sourceResolutions())text.append(" — Original: ").append(String.join(", ",resolution.values())).append(" (").append(resolution.rationale()).append(")");
            answers.stream().filter(a->q.referenceIds().contains(a.questionId())).forEach(a->text.append(" — Human decision: ").append(String.join(", ",a.values())).append(a.otherText()==null?"":" — "+a.otherText()));
            text.append("\n");
        }
        findings.stream().filter(f->f.kind()==ValidationReport.Kind.CONFLICT).distinct().forEach(f->text.append("[CONFLICT] ").append(f.message()).append("\n"));
        return new ReformulationDocument(text.toString(),sections,statements,doc.questions(),doc.validation(),doc.nodeResults());
    }
    private static void place(Section s,Map<String,Section> byId,Set<String> placed,List<Section> result) {
        if(!placed.add(s.id()))return;result.add(s);s.children().stream().sorted().map(byId::get).filter(Objects::nonNull).forEach(child->place(child,byId,placed,result));
    }
    private static int layoutPriority(Section s){String code=Objects.toString(s.taxonomyCode(),"").toUpperCase(Locale.ROOT);return code.equals("BP") || code.startsWith("BP-") || code.equals("CP") || code.contains("CAPABILITY") || code.contains("PROCESS")?0:1;}
    private static <T> List<T> distinct(Collection<T> values){return List.copyOf(new LinkedHashSet<>(values));}
}
