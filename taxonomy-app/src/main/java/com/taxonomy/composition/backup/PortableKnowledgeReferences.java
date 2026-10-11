package com.taxonomy.composition.backup;

import com.fasterxml.jackson.databind.JsonNode;
import com.taxonomy.backup.*;
import java.io.IOException;
import java.util.*;

/** Catalogue references in typed portable payload fields; never treats labels or arbitrary strings as identities. */
final class PortableKnowledgeReferences {
    final Set<String> codes=new TreeSet<>();
    final Set<SourceRecordId> hypotheses=new HashSet<>();
    void code(String code)throws IOException {
        if(code==null)return;
        if(code.isBlank() || !code.equals(code.strip()) || code.length()>256 || code.chars().anyMatch(Character::isISOControl))throw new IOException("Invalid catalogue reference");
        codes.add(code);if(codes.size()>100_000)throw new IOException("Catalogue reference limit exceeded");
    }
    void value(JsonNode value)throws IOException {if(value==null || value.isNull())return;if(!value.isTextual())throw new IOException("Invalid catalogue reference field");code(value.textValue());}
    void array(JsonNode value)throws IOException {if(value==null || value.isNull())return;if(!value.isArray())throw new IOException("Invalid catalogue reference list");for(var item:value)value(item);}
    void payload(JsonNode value)throws IOException {
        if(value==null || value.isNull())return;
        if(value.isArray()){for(var child:value)payload(child);return;}
        if(!value.isObject())return;
        // These names belong to AnalysisResult / architecture views / catalogue trees / analysis questions.
        for(String field:List.of("code","nodeCode","sourceCode","targetCode","parentCode","parentNodeCode","productFamilyCode","sourceNodeCode","taxonomyRoot","sourceRoot","expectedTargetRoot"))value(value.get(field));
        for(String field:List.of("scores","rawScores","effectiveScores","productSuitabilityScores","reasons","scoreDetails","scoreSemanticsContext")) {
            var map=value.get(field);if(map!=null && !map.isNull()) {
                if(!map.isObject())throw new IOException("Invalid catalogue-indexed payload");
                var names=map.fieldNames();while(names.hasNext())code(names.next());
            }
        }
        for(String field:List.of("evaluatedNodes","candidateCodes"))array(value.get(field));
        var coverage=value.get("analysisCoverage");if(coverage!=null && !coverage.isNull()) {
            var nodes=coverage.get("nodes");if(nodes==null || !nodes.isObject())throw new IOException("Invalid analysis coverage references");
            var names=nodes.fieldNames();while(names.hasNext())code(names.next());
        }
        // Traverse only declared structured evidence, never diagnostics, free text or provider prompts.
        for(String field:List.of("tree","children","discrepancies","architectureView","anchors","includedElements","includedRelationships","provisionalRelations","productCoverageGaps","confirmedElements","proposedElements","suggestedRelations","missingRelations","incompletePatterns","coverageGaps","matchedPatterns","explanationTrace","analysisResult"))payload(value.get(field));
        var report=value.get("relationSearchReport");if(report!=null && !report.isNull())relationReport(report);
        var edges=value.get("requirementEvidence");if(edges!=null && !edges.isNull()) {
            if(!edges.isArray())throw new IOException("Invalid architecture relation evidence");
            for(var edge:edges)edge(com.taxonomy.exchange.backup.PortableRows.json().treeToValue(edge,com.taxonomy.dto.RelationSearchModel.Edge.class));
        }
        var provisional=value.get("provisionalRelations");if(provisional!=null && !provisional.isNull()) {
            if(!provisional.isArray())throw new IOException("Invalid provisional relation references");
            for(var item:provisional) {
                var id=item.get("hypothesisId");if(id!=null && !id.isNull()) {
                    if(!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue()<1)throw new IOException("Invalid hypothesis reference");
                    hypotheses.add(new SourceRecordId("knowledge.hypothesis",id.asText()));
                }
            }
        }
    }
    private void edge(com.taxonomy.dto.RelationSearchModel.Edge edge)throws IOException {
        code(edge.contribution().source().id());code(edge.target().id());code(edge.contribution().source().root());code(edge.target().root());
        if(edge.evidence()!=null)code(edge.evidence().targetId());
    }
    private void relationReport(JsonNode document)throws IOException {
        var report=com.taxonomy.exchange.backup.PortableRows.json().treeToValue(document,com.taxonomy.dto.RelationSearchReport.class);
        for(var source:report.sources()) {
            code(source.node().id());code(source.node().root());
            for(var contribution:source.contributions()){code(contribution.source().id());code(contribution.source().root());}
        }
        for(var edge:report.result().edges())edge(edge);
        for(var unfinished:report.result().unfinished()){code(unfinished.sourceId());for(var target:unfinished.targets())code(target);}
        for(var trace:report.result().trace()){code(trace.sourceId());code(trace.targetId());}
        for(var task:report.tasks()){code(task.sourceId());code(task.targetRoot());}
        if(report.progress()!=null) {
            for(var taxonomy:report.progress().taxonomies())code(taxonomy.root());
            var current=report.progress().current();if(current!=null){code(current.sourceId());code(current.targetRoot());for(var candidate:current.candidates())code(candidate);}
        }
    }
    void encoded(JsonNode record,String field)throws IOException {
        var value=record.get(field);if(value==null || value.isNull())return;
        if(!value.isTextual())throw new IOException("Invalid encoded analysis references");
        payload(com.taxonomy.exchange.backup.PortableRows.json().readTree(value.textValue()));
    }
}
