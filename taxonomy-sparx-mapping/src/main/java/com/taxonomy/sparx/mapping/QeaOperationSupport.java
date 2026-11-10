package com.taxonomy.sparx.mapping;

import java.util.Map;

/** Declared editing effects for the bounded source-copy writer; no native EA acceptance claim. */
public final class QeaOperationSupport {
    public static final String ID = "sparx-qea@2:operations/1";
    public static final Map<String, String> OUTCOMES = Map.of(
            "rename", "faithful", "move-package", "faithful", "repeated-occurrence", "faithful",
            "remove-occurrence", "unsupported", "geometry-move", "unsupported",
            "endpoint-change", "faithful", "concurrent-local-edit", "unsupported");
    private QeaOperationSupport() { }
}
