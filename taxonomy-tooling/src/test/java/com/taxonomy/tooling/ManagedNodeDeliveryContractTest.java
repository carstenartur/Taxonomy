package com.taxonomy.tooling;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
class ManagedNodeDeliveryContractTest {
 @TempDir Path emptyPath;
 @Test void realDeliveryGuardUsesManagedNodeWhenAmbientNodeIsUnresolvable() throws Exception {
  List<String> command=new ArrayList<>(List.of("/bin/bash","--noprofile","--norc","-c","exec \"$@\"","managed-node-contract"));
  command.addAll(CoreDeliveryContractsTest.buildCacheCommand());
  Path log=emptyPath.resolve("result.log");
  var builder=new ProcessBuilder(command).directory(JavascriptJUnitBridge.root().toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
  builder.environment().put("PATH",emptyPath.toString());
  var process=builder.start();
  assertTrue(process.waitFor(30,TimeUnit.SECONDS));
  assertEquals(0,process.exitValue(),Files.readString(log));
 }
}
