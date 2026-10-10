package com.taxonomy.backup.runtime;

import com.taxonomy.backup.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** Reads the explicitly reviewed ownership catalogue; unknown categories fail closed. */
public final class BackupCoverageInventory {
    private BackupCoverageInventory() { }

    public static BackupInventory load() throws IOException {
        var input = BackupCoverageInventory.class.getResourceAsStream("/backup/coverage-inventory.tsv");
        if (input == null) throw new IOException("Missing backup coverage inventory");
        try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            var categories = new ArrayList<BackupInventory.Category>();
            for (String line; (line = reader.readLine()) != null;) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] columns = line.split("\t", -1);
                if (columns.length != 4) throw new IOException("Malformed backup coverage inventory");
                categories.add(new BackupInventory.Category(columns[0], new BackupComponentId(columns[1]),
                        BackupStorageRule.valueOf(columns[2]), columns[3]));
            }
            return new BackupInventory(categories);
        }
    }
}
