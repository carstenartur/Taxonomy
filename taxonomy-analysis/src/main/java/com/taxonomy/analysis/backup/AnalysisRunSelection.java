package com.taxonomy.analysis.backup;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/** Bounded metadata only; database collation must not decide which exact identity wins. */
final class AnalysisRunSelection {
    private final boolean history;
    private final Map<String, Run> runs = new HashMap<>();
    private final Map<Key, Run> latest = new HashMap<>();

    AnalysisRunSelection(boolean history) { this.history = history; }

    void accept(Run run) throws IOException {
        if (!history) {
            var previous = latest.get(run.key());
            if (previous != null) {
                if (run.updatedAt() < previous.updatedAt()
                        || (run.updatedAt() == previous.updatedAt() && run.id().compareTo(previous.id()) <= 0)) return;
                runs.remove(previous.id());
            }
        }
        if (runs.size() >= 100_000) throw new IOException("Analysis continuation selection limit exceeded");
        runs.put(run.id(), run);
        if (!history) latest.put(run.key(), run);
    }

    Map<String, Run> selected() { return new TreeMap<>(runs); }

    record Key(String owner, String repository, String workspace, String branch) { }
    record Run(String id, Key key, String inputHash, long updatedAt, long version, String state, String currentNode) { }
}
