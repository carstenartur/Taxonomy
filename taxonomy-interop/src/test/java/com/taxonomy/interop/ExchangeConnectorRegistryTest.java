package com.taxonomy.interop;

import com.taxonomy.interop.sparx.*;
import com.taxonomy.exchange.sparx.SparxMappingProfile;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ExchangeConnectorRegistryTest {
    @Test void exactVersionLookupKeepsLegacyDefaultAndRejectsUnknownVersion() {
        var registry = new ExchangeConnectorRegistry(List.of(new SparxXmiConnector(), new SparxXmiV2Connector()));
        assertEquals("1", registry.require(SparxMappingProfile.PROFILE).descriptor().version());
        assertEquals("2", registry.require(SparxMappingProfile.PROFILE, "2").descriptor().version());
        assertThrows(IntegrationProblem.class, () -> registry.require(SparxMappingProfile.PROFILE, "3"));
        assertThrows(IllegalStateException.class, () -> new ExchangeConnectorRegistry(List.of(new SparxXmiConnector(), new SparxXmiConnector())));
    }

    @Test void omittedJsonVersionAndOldConstructorRemainVersionOne() throws Exception {
        String json = "{\"id\":null,\"name\":\"Legacy\",\"connectorId\":\"sparx-xmi-2.1\",\"authority\":\"IMPORT_COPY\",\"externalScope\":{\"systemType\":\"EA\",\"repository\":\"fixture\"}}";
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals("1", mapper.readValue(json, IntegrationService.CreateConnection.class).profileVersion());
        assertEquals("2", mapper.readValue(json.replace("{\"id\"", "{\"profileVersion\":\"2\",\"id\""), IntegrationService.CreateConnection.class).profileVersion());
        assertEquals("1", new IntegrationService.CreateConnection(null, "Legacy", SparxMappingProfile.PROFILE, null, null, null, null).profileVersion());
    }
}
