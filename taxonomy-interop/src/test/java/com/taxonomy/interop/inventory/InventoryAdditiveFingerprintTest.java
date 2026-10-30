package com.taxonomy.interop.inventory;

import com.taxonomy.extension.api.inventory.InventoryComparisonContracts.*;
import com.taxonomy.extension.api.inventory.InventoryReadContracts.AssertionKind;
import com.taxonomy.interop.IntegrationJson;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InventoryAdditiveFingerprintTest {
    @Test void absentAdditivePropertiesKeepHistoricalCanonicalFingerprints() throws Exception {
        var json=new IntegrationJson(JsonMapper.builder().build());
        var assertion=new Assertion("claim","product:1",Criterion.AVAILABILITY,null,true,null,null,"org",null,AssertionKind.DECLARED,"Evidence",Instant.EPOCH);
        var review=new Review(UUID.randomUUID(),"fingerprint","product:1","Reason",null,false);
        var profile=new InventorySourceProfiles.Profile("1","source","instance",java.net.URI.create("https://inventory.example"),"repo","org",Set.of("alice"),Set.of("one"),List.of(),false,null,Map.of(),"SOURCE_SECRET",null,false,List.of());
        for(Object value:List.of(assertion,review,profile)) {
            var legacy=new TreeMap<String,Object>();
            for(var component:value.getClass().getRecordComponents())
                if(!Set.of("relationship","variantDecision","relationships").contains(component.getName()))legacy.put(component.getName(),component.getAccessor().invoke(value));
            assertEquals(json.fingerprint(legacy),json.fingerprint(value),"Empty additive fields cannot change a retained review/assertion fingerprint");
        }
    }
}
