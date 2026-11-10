package com.taxonomy.tooling;

import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** The same required repository guards run in root Maven and CI. */
class CoreDeliveryContractsTest {
    static List<String> buildCacheCommand() { return List.of(JavascriptJUnitBridge.nodeExecutable(),".github/scripts/check-maven-build-cache.mjs"); }
    @TestFactory Stream<DynamicTest> deliveryContracts() {
        List<List<String>> commands=new ArrayList<>();
        for(String script:List.of("release.sh","install-helm.sh","download-embedding-model.sh")) commands.add(List.of("bash","-n",".github/scripts/"+script));
        commands.add(List.of("bash","-n","deploy/helm/taxonomy/verify.sh"));
        for(String command:List.of("check-release-delivery-contract","check-delivery-hardening")) commands.add(List.of("bash",".github/scripts/taxonomy-tooling.sh",command));
        commands.add(buildCacheCommand());
        return commands.stream().map(command -> DynamicTest.dynamicTest(String.join(" ",command), () -> {
            Path log=Files.createTempFile("junit-delivery-contract-", ".log");
            try {
                var process=new ProcessBuilder(command).directory(JavascriptJUnitBridge.root().toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
                assertTrue(process.waitFor(120, TimeUnit.SECONDS),"Repository guard timed out: "+command);
                assertEquals(0,process.exitValue(),Files.readString(log));
            } finally { Files.deleteIfExists(log); }
        }));
    }
}
