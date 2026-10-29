package com.taxonomy.build;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** Each real product round trip has its own JUnit outcome; product tools remain drivers. */
@EnabledIfSystemProperty(named="taxonomy.interoperability.skip",matches="false")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InteroperabilityProductsIT {
    private static final Set<String> REQUIRED = Set.of("StrictDoc/reqif-1.2/1", "Archi/archimate-3.1/1", "Archi/taxonomy-archimate-3.1-v2");
    private Map<String,JsonNode> outcomes;
    private Throwable failure;
    private JsonNode report;
    private ExternalDriverSupport.Result execution;
    @TestFactory Stream<DynamicTest> productRoundTrips() {
        Stream<DynamicTest> cases=REQUIRED.stream().sorted().map(product -> DynamicTest.dynamicTest(product,() -> {
            executeOnce();
            ExternalDriverSupport.assertObserved(outcomes,product,"result",failure,execution);
        }));
        return Stream.concat(cases,Stream.of(DynamicTest.dynamicTest("complete product driver and inventory",()-> {
            executeOnce();
            if(failure!=null) throw new AssertionError("Real product driver failed",failure);
            assertEquals(0,execution.exitCode(),"Product driver failed; see target/junit-drivers/interoperability-products.log");
            validateOutcomes(report);
        })));
    }
    private static Map<String,JsonNode> readOutcomes(JsonNode report) {
        Map<String,JsonNode> actual=new LinkedHashMap<>();
        for(JsonNode result:report.path("results")) {
            String key=result.path("product").asText()+"/"+result.path("profile").asText();
            assertNull(actual.put(key,result),"Duplicate product/profile outcome");
        }
        return actual;
    }
    static Map<String,JsonNode> validateOutcomes(JsonNode report) {
        var actual=readOutcomes(report);
        assertEquals(REQUIRED,actual.keySet(),"Missing/unexpected product/profile round trip");
        assertEquals("passed",report.path("status").asText());
        for(var product:actual.values()) assertEquals("passed",product.path("result").asText());
        return actual;
    }
    private synchronized void executeOnce() {
        if(outcomes!=null || failure!=null) return;
        try {
            Path manifest=ExternalDriverSupport.root().resolve("target/ui-application/manifest.json");
            if(!Files.isRegularFile(manifest)) {
                assertNotEquals("true",System.getenv("CI"),"CI must download the independently built commit-bound application manifest");
                ExternalDriverSupport.command(List.of("bash",".github/scripts/stage-ui-application.sh"),"stage-interoperability-application.log",2);
            }
            Path evidence=ExternalDriverSupport.root().resolve("target/interoperability-products/evidence.json");
            Files.deleteIfExists(evidence);
            execution=ExternalDriverSupport.observe("verify-interoperability-products.mjs","interoperability-products.log",20);
            report=new ObjectMapper().readTree(Files.readString(evidence));
            outcomes=readOutcomes(report);
        } catch(Throwable error) { failure=error; }
    }
}
