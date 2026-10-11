package com.taxonomy.backup.restore;

import com.taxonomy.backup.*;
import java.io.IOException;
import java.util.*;

/** Closed production import/matrix registry until complete owner combinations have actual acceptance. */
public final class RestoreSupportRegistry {
    private RestoreSupportRegistry() { }
    public static RestoreSupportRegistry closed(){return new RestoreSupportRegistry();}
    public String revision(){return "closed-owner-matrix-v1";}
    List<RestoreDiagnostic> inspect(BackupManifest manifest) {
        var diagnostics=new ArrayList<RestoreDiagnostic>();
        for(var component:manifest.components())if(!component.id().value().equals("capture-proof"))
            diagnostics.add(new RestoreDiagnostic("UNREGISTERED_OWNER",RestoreDiagnostic.Severity.BLOCKER,component.id().value(),null));
        diagnostics.add(new RestoreDiagnostic("UNSUPPORTED_MATRIX",RestoreDiagnostic.Severity.BLOCKER,null,null));return diagnostics;
    }
    void requireSupported(RestorePlan plan)throws IOException {
        // No enum, source-family witness, schema fixture or available parser grants import readiness.
        throw new IOException("Restore owner/database combination is unsupported");
    }
}
