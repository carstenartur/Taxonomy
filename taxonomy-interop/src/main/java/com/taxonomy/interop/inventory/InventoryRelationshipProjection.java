package com.taxonomy.interop.inventory;

import com.taxonomy.extension.api.inventory.InventoryComparisonContracts.*;
import com.taxonomy.extension.api.inventory.InventoryReadContracts.*;
import com.taxonomy.interop.IntegrationJson;
import java.time.Instant;
import java.util.*;

/** Only operator-approved edge meanings become directed evidence; no validity interval is invented. */
final class InventoryRelationshipProjection {
    private final IntegrationJson json;
    InventoryRelationshipProjection(IntegrationJson json){this.json=json;}
    List<Assertion> project(List<ObservedRelation> relations,Set<ExternalKey> selected,List<InventorySourceProfiles.RelationshipProjection> rules,UUID observation,Instant recorded,String organization) {
        var assertions=new ArrayList<Assertion>();
        for(var edge:relations) {
            if(!selected.contains(edge.source()) || !selected.contains(edge.target()))continue;
            for(var rule:rules) {
                if(!rule.sourceRelation().equals(edge.meaning()))continue;
                String source="external:"+json.fingerprint(edge.source()),target="external:"+json.fingerprint(edge.target());
                assertions.add(new Assertion(observation+":relation:"+json.fingerprint(List.of(edge,rule)),source,Criterion.RELATIONSHIPS,null,true,null,null,organization,null,rule.assertionKind(),
                    "Observed relation "+edge.observationId()+" ("+edge.meaning()+"); validity interval not provided",recorded,new Relationship(target,rule.kind(),rule.qualifier())));
            }
        }
        return List.copyOf(assertions);
    }
}
