package com.taxonomy.build;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Runs real browser/product drivers; JUnit retains every outcome and timeout. */
final class ExternalDriverSupport {
    static Path root() {
        Path root=Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while(root!=null && !Files.isRegularFile(root.resolve(".github/scripts/run-ui-suite.mjs"))) root=root.getParent();
        if(root==null) throw new IllegalStateException("Repository root missing");
        return root;
    }
    static String run(String script, List<String> arguments, String logName, int minutes) throws Exception {
        String node=System.getenv().getOrDefault("TAXONOMY_NODE_EXECUTABLE",System.getProperty("taxonomy.node.executable","node"));
        if(node.contains("/") && !Files.isExecutable(Path.of(node))) node="node";
        List<String> command=new ArrayList<>(List.of(node,root().resolve(".github/scripts/"+script).toString())); command.addAll(arguments);
        return command(command,logName,minutes);
    }
    static void assertObserved(java.util.Map<String,tools.jackson.databind.JsonNode> outcomes,String name,String field,Throwable failure,Result execution) {
        if(outcomes==null || !outcomes.containsKey(name)) {
            if(failure!=null || execution.exitCode()!=0) throw new org.opentest4j.TestAbortedException("Not executed after shared driver failure: "+name,failure);
            fail("Missing required driver outcome: "+name);
        }
        assertEquals("passed",outcomes.get(name).path(field).asText(),outcomes.get(name).toString());
    }
    record Result(int exitCode,String output) {}
    static Result observe(String script,String logName,int minutes) throws Exception {
        String node=System.getenv().getOrDefault("TAXONOMY_NODE_EXECUTABLE",System.getProperty("taxonomy.node.executable","node"));
        if(node.contains("/") && !Files.isExecutable(Path.of(node))) node="node";
        return observeCommand(List.of(node,root().resolve(".github/scripts/"+script).toString()),logName,minutes);
    }
    static String command(List<String> command,String logName,int minutes) throws Exception {
        Result result=observeCommand(command,logName,minutes);
        assertEquals(0,result.exitCode(),"Driver failed: "+logName+"\n"+result.output().substring(Math.max(0,result.output().length()-12000)));
        return result.output();
    }
    private static Result observeCommand(List<String> command,String logName,int minutes) throws Exception {
        Path root=root(), log=root.resolve("target/junit-drivers/"+logName);
        Files.createDirectories(log.getParent());
        var builder=new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        if(!builder.environment().containsKey("GITHUB_SHA")) {
            var revision=new ProcessBuilder("git","rev-parse","HEAD").directory(root.toFile()).start();
            assertTrue(revision.waitFor(10,TimeUnit.SECONDS),"Cannot resolve source commit");
            assertEquals(0,revision.exitValue(),"Cannot resolve source commit");
            builder.environment().put("GITHUB_SHA",new String(revision.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).strip());
        }
        builder.environment().putIfAbsent("TAXONOMY_UI_SOURCE_SHA",builder.environment().get("GITHUB_SHA"));
        builder.environment().putIfAbsent("TAXONOMY_UI_SUITE",System.getProperty("taxonomy.ui.suite","all"));
        builder.environment().putIfAbsent("TAXONOMY_UI_PROFILE_FILTER",System.getProperty("taxonomy.ui.profile.filter",""));
        Process process=builder.start();
        boolean completed=process.waitFor(minutes,TimeUnit.MINUTES);
        if(!completed) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); }
        return new Result(completed ? process.exitValue() : 124,Files.readString(log));
    }
}
