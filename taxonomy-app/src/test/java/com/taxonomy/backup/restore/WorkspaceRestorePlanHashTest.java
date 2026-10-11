package com.taxonomy.backup.restore;

import com.taxonomy.backup.*;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** Separate JVMs have independently randomized immutable-set iteration order. Only canonical bytes bind restart. */
class WorkspaceRestorePlanHashTest {
    @Test void gitBindingHashIsIdenticalAcrossIndependentJvmSetIterationSeeds()throws Exception {
        var hashes=new HashSet<String>();
        for(int count=0;count<3;count++) {
            var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx64m","-cp",System.getProperty("java.class.path"),Probe.class.getName()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            try {
                assertThat(process.waitFor(30,TimeUnit.SECONDS)).as("bounded standalone canonical-byte probe").isTrue();
                String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).strip();
                assertThat(process.exitValue()).as(output).isZero();assertThat(output).matches("[a-f0-9]{64}");hashes.add(output);
            } finally{process.destroyForcibly();}
        }
        assertThat(hashes).as("durable owner bindings must not change with the JVM's Set.copyOf iteration seed").hasSize(1);
    }
    public static final class Probe {
        public static void main(String[] args)throws Exception {
            var required=new HashSet<String>();var mappings=new HashMap<String,String>();for(int index=1;index<=16;index++){String commit=String.format("%040x",index);required.add(commit);mappings.put(commit,commit);}
            var source=new BackupRepositoryKey("source-repository",null);var target=new RestoreRepositoryAllocation(new BackupRepositoryKey("target-repository",null),"approved-target-storage");
            var state=new SnapshotContext.RepositoryState(Map.of("refs/heads/main",String.format("%040x",1)),"refs/heads/main",Map.of(),required);
            var proof=new GitRestoreImporter.RepositoryProof(target.targetKey(),target.physicalStorageName(),state,mappings,"a".repeat(64));
            System.out.print(WorkspaceRestoreImporter.gitBinding(new GitRestoreImporter.Result(Map.of(source,proof)),Map.of(source,target)));
        }
    }
}
