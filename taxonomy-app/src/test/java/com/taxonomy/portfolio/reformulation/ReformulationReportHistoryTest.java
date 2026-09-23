package com.taxonomy.portfolio.reformulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Normal JUnit entry points; isolated real application processes retain production authorization. */
class ReformulationReportHistoryTest {
    @TempDir Path directory;

    @Test void exactHistoricalReportsSurviveEditsAdoptionAndRestart() throws Exception {
        runApplication("write"); runApplication("read");
    }
    @Test void downloadControlsUseReadOnlyCapturedIdentities() throws Exception {
        List<String> command=new ArrayList<>(List.of("node"));
        for(String resource:List.of("/reformulation/report-download-contract.cjs", "/static/js/api/portfolio-api.js", "/static/js/portfolio/reformulation-reports.js")) {
            Path target=directory.resolve(Path.of(resource).getFileName());
            try(var in=getClass().getResourceAsStream(resource)){assertNotNull(in,resource);Files.copy(in,target);}command.add(target.toString());
        }
        execute(command,"downloads",30,"REFORMULATION_REPORT_DOWNLOAD_OK");
    }
    private void runApplication(String mode) throws Exception {
        var command=new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"),"bin","java").toString());command.add("-Xmx768m");
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(a->a.startsWith("-javaagent:") && a.contains("jacoco")).forEach(command::add);
        command.addAll(List.of("-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
                ReformulationReportChecks.class.getName(),directory.resolve("application").toString(),mode));
        execute(command,mode,180,mode.equals("read")?"REFORMULATION_REPORT_RESTART_OK":"REFORMULATION_REPORT_HTTP_OK");
    }
    private void execute(List<String> command,String phase,int seconds,String marker) throws Exception {
        Path log=directory.resolve(phase+".log");
        var process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(seconds,TimeUnit.SECONDS),"Test process timed out: "+log);
            String text=Files.readString(log);assertEquals(0,process.exitValue(),text);assertTrue(text.contains(marker),text);
        } finally {
            if(process.isAlive()){process.destroyForcibly();assertTrue(process.waitFor(15,TimeUnit.SECONDS));}
        }
    }
}
