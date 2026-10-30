package com.taxonomy.portfolio.inventory;
import com.taxonomy.extension.api.inventory.InventoryComparisonContracts.CostAssumption;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
class InventoryCostPrecisionTest {
    @ParameterizedTest @ValueSource(strings={"9007199254740993","1234567890123456.78","0.0001"})
    void exactDecimalStringSurvivesWire(String amount) {
        var mapper=JsonMapper.builder().build();var cost=new CostAssumption(amount,"EUR","one-time","Budget owner quote");
        var json=mapper.writeValueAsString(cost);
        assertEquals(amount,mapper.readTree(json).get("amount").asText());assertTrue(mapper.readTree(json).get("amount").isString());
        assertEquals(cost,mapper.readValue(json,CostAssumption.class));
    }
    @ParameterizedTest @ValueSource(strings={"-1","1e3","1234567890123456789","0.00001"})
    void outsideContractIsRejected(String amount) {assertThrows(IllegalArgumentException.class,()->new CostAssumption(amount,"EUR","yearly","Quote"));}
}
