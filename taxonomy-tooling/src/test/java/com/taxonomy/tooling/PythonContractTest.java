package com.taxonomy.tooling;

import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** Legacy Python assertion functions are scheduled individually by JUnit, never a Python test runner. */
class PythonContractTest {
    @TestFactory Stream<DynamicNode> legacyAssertions() throws Exception {
        List<DynamicNode> sources=new ArrayList<>();
        for(String file:List.of("test-generate-quality-site.py","test-verify-quality-publication.py","test-verify-deployment.py")) {
            String module=JavascriptJUnitBridge.root().resolve(".github/scripts/"+file).toString();
            String bootstrap="import importlib.util, inspect\ns=importlib.util.spec_from_file_location('legacy_contract',"+quote(module)+"); m=importlib.util.module_from_spec(s); s.loader.exec_module(m)\n";
            var discovery=run(bootstrap+"for n,c in inspect.getmembers(m,inspect.isclass):\n if c.__module__==m.__name__:\n  for method in dir(c):\n   if method.startswith('test_'): print(n+'.'+method)\n");
            assertEquals(0,discovery.code,discovery.output);
            var names=discovery.output.lines().filter(line -> line.matches("[A-Za-z_0-9]+\\.test_[A-Za-z_0-9]+" )).toList();
            assertFalse(names.isEmpty(),"No owned Python assertions: "+file);
            sources.add(DynamicContainer.dynamicContainer(file,names.stream().map(name -> DynamicTest.dynamicTest(name, () -> {
                String[] identity=name.split("\\.");
                var result=run(bootstrap+"c=getattr(m,"+quote(identity[0])+"); c.setUpClass(); t=c("+quote(identity[1])+")\ntry:\n t.setUp()\n try: getattr(t,"+quote(identity[1])+")()\n finally: t.tearDown()\nfinally:\n t.doCleanups(); c.tearDownClass()\n");
                assertEquals(0,result.code,result.output);
            }))));
        }
        return sources.stream();
    }
    private record Result(int code,String output) {}
    private Result run(String script) throws Exception {
        Path log=Files.createTempFile("junit-python-contract-", ".log");
        try {
            var process=new ProcessBuilder("python3","-c",script).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if(!process.waitFor(120,java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Python callback timed out"); }
            return new Result(process.exitValue(),Files.readString(log));
        } finally { Files.deleteIfExists(log); }
    }
    private static String quote(String value) { return "'"+value.replace("\\","\\\\").replace("'","\\'")+"'"; }
}
