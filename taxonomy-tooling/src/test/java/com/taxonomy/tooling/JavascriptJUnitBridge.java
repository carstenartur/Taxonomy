package com.taxonomy.tooling;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Persistent JS callback driver; JUnit owns discovery, scheduling and assertions. */
final class JavascriptJUnitBridge implements AutoCloseable {
    record Outcome(String name, String status, String detail) {}
    private final Process process;
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final ExecutorService reads = Executors.newSingleThreadExecutor();
    private final List<String> names;
    private boolean closed;
    private final StringBuilder log = new StringBuilder();
    JavascriptJUnitBridge(Path source) throws Exception {
        Path root = root();
        String node = nodeExecutable();
        process = new ProcessBuilder(node, root.resolve("taxonomy-tooling/src/test/js/junit-driver.mjs").toString(), source.toAbsolutePath().toString())
                .directory(root.toFile()).redirectErrorStream(true).start();
        reader = process.inputReader(StandardCharsets.UTF_8);
        writer = process.outputWriter(StandardCharsets.UTF_8);
        try {
            var response = response();
            names = ((List<?>)response.get("names")).stream().map(String::valueOf).toList();
        } catch(Exception error) { process.destroyForcibly(); reads.shutdownNow(); throw error; }
    }
    static String nodeExecutable() {
        String node=System.getenv().getOrDefault("TAXONOMY_NODE_EXECUTABLE",System.getProperty("taxonomy.node.executable","node"));
        if(node.contains("/") && !Files.isExecutable(Path.of(node))) node="node";
        return node;
    }
    static Path root() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while(candidate != null && !Files.isRegularFile(candidate.resolve("taxonomy-tooling/src/test/js/junit-driver.mjs"))) candidate=candidate.getParent();
        if(candidate == null) throw new IllegalStateException("Cannot find JUnit JavaScript driver");
        return candidate;
    }
    List<String> names() { return names; }
    List<Outcome> execute(int index) throws Exception {
        writer.write(index + "\n"); writer.flush();
        var payload = response();
        return ((List<?>)payload.get("outcomes")).stream().map(value -> {
            var outcome=(Map<?,?>)value;
            return new Outcome((String)outcome.get("name"),(String)outcome.get("status"),(String)outcome.get("detail"));
        }).toList();
    }
    private Map<String,Object> response() throws Exception {
        Future<Map<String,Object>> result = reads.submit(() -> {
            String line;
            while((line=reader.readLine()) != null) {
                if(line.startsWith("JUNIT\t")) {
                    var payload=FlatJson.parseObject(line.substring(6));
                    if(payload.containsKey("fatal")) throw new IOException(String.valueOf(payload.get("fatal")));
                    return payload;
                }
                log.append(line).append('\n');
            }
            throw new IOException("JavaScript driver terminated without an outcome: " + log);
        });
        try { return result.get(150, TimeUnit.SECONDS); }
        catch(ExecutionException error) { throw new IOException(error.getCause().getMessage(),error.getCause()); }
        catch(TimeoutException error) { process.destroyForcibly(); throw new IOException("JavaScript driver timeout: " + log,error); }
    }
    public void close() throws Exception {
        if(closed) return;
        closed=true;
        try {
            writer.write("close\n"); writer.flush(); response();
            if(!process.waitFor(10,TimeUnit.SECONDS)) throw new IOException("JavaScript driver did not close: " + log);
            if(process.exitValue()!=0) throw new IOException("JavaScript driver failed: " + log);
        } finally { process.destroyForcibly(); reads.shutdownNow(); }
    }
}
