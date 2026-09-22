package com.taxonomy.portfolio.reformulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Two new application processes, with real persistence, authorization and explicit HTTP commands. */
class ReformulationAdoptionTest {
    @TempDir Path directory;
    @Test void explicitVersionTransitionAndEvidenceSurviveRestart() throws Exception {
        run("write");run("read");
    }
    @Test void actualDialogAndApiNeverAdoptOnPreviewOrLoseRetryIdentity() throws Exception {
        var paths=new ArrayList<String>();
        for(String name:List.of("/reformulation/adoption-contract.cjs","/static/js/api/portfolio-api.js","/static/js/portfolio/reformulation-adoption.js")) {
            Path path=directory.resolve(Path.of(name).getFileName());
            try(var resource=getClass().getResourceAsStream(name)){assertNotNull(resource,name);Files.copy(resource,path);}paths.add(path.toString());
        }
        var command=new ArrayList<String>();command.add("node");command.addAll(paths);
        execute(command,"controls",30,"REFORMULATION_ADOPTION_CONTROLS_OK");
    }
    @Test void readOnlyDetailRefreshKeepsUnsavedFieldsAndRejectsLateReads() throws Exception {
        Path script=directory.resolve("detail-refresh.cjs"), source=directory.resolve("requirement-detail.js");
        try(var resource=getClass().getResourceAsStream("/reformulation/adoption-detail-refresh-contract.cjs")){assertNotNull(resource);Files.copy(resource,script);}
        try(var resource=getClass().getResourceAsStream("/static/js/portfolio/requirement-detail.js")){assertNotNull(resource);Files.copy(resource,source);}
        execute(List.of("node",script.toString(),source.toString()),"detail-refresh",30,"REFORMULATION_ADOPTION_DETAIL_REFRESH_OK");
    }
    private void run(String mode) throws Exception {
        var command=new ArrayList<String>();command.add(Path.of(System.getProperty("java.home"),"bin","java").toString());command.add("-Xmx768m");
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream().filter(s->s.startsWith("-javaagent:") && s.contains("jacoco")).forEach(command::add);
        command.addAll(List.of("-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),ReformulationAdoptionChecks.class.getName(),directory.resolve("db").toString(),mode));
        execute(command,mode,180,mode.equals("read")?"REFORMULATION_ADOPTION_RESTART_OK":"REFORMULATION_ADOPTION_HTTP_OK");
    }
    private void execute(List<String> command,String name,int seconds,String marker) throws Exception {
        Path log=directory.resolve(name+".log");var process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(seconds,TimeUnit.SECONDS),"Timeout: "+log);
            String output=Files.readString(log);assertEquals(0,process.exitValue(),output);assertTrue(output.contains(marker),output);
        } finally {if(process.isAlive()){process.destroyForcibly();assertTrue(process.waitFor(15,TimeUnit.SECONDS));}}
    }
}
