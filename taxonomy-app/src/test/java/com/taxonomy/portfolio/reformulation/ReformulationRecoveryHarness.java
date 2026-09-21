package com.taxonomy.portfolio.reformulation;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Fresh application JVMs with a shared file DB; no Spring/JGit static state crosses a restart. */
public final class ReformulationRecoveryHarness {
    private ReformulationRecoveryHarness() {}
    public static void main(String[] args) throws Exception {
        Path directory=Path.of(args[1]);Files.createDirectories(directory);
        if(args[0].equals("recovery"))recoverAfterKill(directory);
        else runToCompletion(directory,"boundaries","REFORMULATION_LEASE_FENCING_OK");
    }
    static void recoverAfterKill(Path directory) throws Exception {
        Path log=directory.resolve("produce.log");
        Process producer=start(directory,"produce",log);
        try {
            long deadline=System.nanoTime()+Duration.ofSeconds(180).toNanos();
            while(producer.isAlive() && System.nanoTime()<deadline
                    && !(Files.exists(directory.resolve("kill-ready")) && Files.exists(directory.resolve("identity")))) Thread.sleep(100);
            if(!producer.isAlive() || !Files.exists(directory.resolve("kill-ready")) || !Files.exists(directory.resolve("identity")))
                throw new AssertionError("Producer did not commit a child checkpoint before termination: "+Files.readString(log));
            producer.destroyForcibly();
            if(!producer.waitFor(15,TimeUnit.SECONDS) || producer.exitValue()==0)
                throw new AssertionError("Producer was not forcibly terminated");
            System.out.println("REFORMULATION_PRODUCER_KILLED exit="+producer.exitValue());
        } finally { stop(producer); }
        runToCompletion(directory,"recover","REFORMULATION_PROCESS_RECOVERY_OK");
    }
    static void runToCompletion(Path directory,String mode,String marker) throws Exception {
        Path log=directory.resolve(mode+".log");
        Process process=start(directory,mode,log);
        try {
            if(!process.waitFor(180,TimeUnit.SECONDS))throw new AssertionError("Application process timed out: "+mode);
            String output=Files.readString(log);
            if(process.exitValue()!=0 || !output.contains(marker))throw new AssertionError(output);
            System.out.println(marker);
        } finally {stop(process);}
    }
    private static Process start(Path directory,String mode,Path log) throws Exception {
        List<String> command=new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"),"bin","java").toString());command.add("-Xmx768m");
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(a->a.startsWith("-javaagent:") && a.contains("jacoco")).forEach(command::add);
        command.add("-cp");command.add(System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")));
        command.add(ReformulationProcessRecoveryDriver.class.getName());command.add(mode);command.add(directory.toAbsolutePath().toString());
        return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }
    private static void stop(Process process) throws Exception {
        if(process.isAlive()) {process.destroyForcibly();if(!process.waitFor(15,TimeUnit.SECONDS))throw new AssertionError("Child JVM failed to terminate");}
    }
}
