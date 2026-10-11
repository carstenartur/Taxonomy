package com.taxonomy.exchange.backup;

import com.taxonomy.backup.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProjectedBackupRecordsTest {
    @Test void observesActualUtf8RecordsAndNeverTreatsHeaderAsBusinessData()throws Exception {
        var values=new ArrayList<String>();
        ProjectedBackupRecords.inspect(owner("{\"value\":\"Überprüfung\"}\n{\"value\":\"second\"}\n"),snapshot(),()->{},(kind,row)->values.add(kind+":"+row.path("value").asText()));
        assertThat(values).containsExactly("fixture:Überprüfung","fixture:second");
    }
    @Test void rejectsIncompleteRecordsAndHeaderForAnotherCapture() {
        assertThatThrownBy(()->ProjectedBackupRecords.inspect(owner("{}"),snapshot(),()->{},(k,r)->{})).isInstanceOf(IOException.class).hasMessageContaining("Incomplete");
        assertThatThrownBy(()->ProjectedBackupRecords.inspect(owner("{}\n","SELECTED_VERSION"),snapshot(),()->{},(k,r)->{})).isInstanceOf(IOException.class).hasMessageContaining("header");
    }
    @Test void referenceFailureStopsReadingAndCancellationRemainsLive() {
        assertThatThrownBy(()->ProjectedBackupRecords.inspect(owner("{}\n{}\n"),snapshot(),()->{},(k,r)->{throw new IOException("unresolved fixture reference");})).isInstanceOf(IOException.class).hasMessage("unresolved fixture reference");
        assertThatThrownBy(()->ProjectedBackupRecords.inspect(owner("{}\n"),snapshot(),()->{throw new InterruptedIOException("cancelled");},(k,r)->{})).isInstanceOf(InterruptedIOException.class);
    }
    private BackupDataContributor owner(String records){return owner(records,"CURRENT_STATE");}
    private BackupDataContributor owner(String records,String profile){return new BackupDataContributor(){
        public BackupComponentId componentId(){return new BackupComponentId("fixture");}public int schemaVersion(){return 1;}public Set<String> categories(){return Set.of("fixture-records");}public List<String> omissions(BackupProfile profile){return List.of();}
        public void write(SnapshotContext snapshot,ComponentSink sink)throws IOException {
            byte[] bytes=("{\"schemaVersion\":1,\"kind\":\"fixture\",\"profile\":\""+profile+"\"}\n"+records).getBytes(StandardCharsets.UTF_8);
            try(var input=new ByteArrayInputStream(bytes)){sink.write("data/fixture/fixture.ndjson",input);}
        }
    };}
    private SnapshotContext snapshot(){var request=new BackupRequest(BackupProfile.CURRENT_STATE,new BackupScope.Workspace("fixture-repo","fixture-workspace"),new BackupTime.Current(),GitRepresentation.NONE,SecretsSelection.EXCLUDE);
        var authorization=new AuthorizedBackupRequest(request,PrincipalId.create(),"fixture-decision",Instant.EPOCH,Set.of(BackupCapability.EXPORT_CURRENT));
        return new SnapshotContext(BackupId.create(),authorization,Instant.EPOCH,Instant.EPOCH,1,Map.of(new BackupRepositoryKey("fixture-repo","fixture-workspace"),new SnapshotContext.RepositoryState(Map.of(),null,Map.of(),Set.of())),Map.of(new BackupComponentId("fixture"),1));}
}
