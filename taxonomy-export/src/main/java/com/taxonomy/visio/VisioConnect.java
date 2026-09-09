package com.taxonomy.visio;

import java.util.Map;
import java.util.TreeMap;

public class VisioConnect {
    private final String fromShape;
    private final String toShape;
    private final String relationType;
    private final Map<String, VisioProperty> properties = new TreeMap<>();

    public VisioConnect(String fromShape, String toShape, String relationType) {
        this.fromShape = fromShape;
        this.toShape = toShape;
        this.relationType = relationType;
    }

    public String getFromShape() { return fromShape; }
    public String getToShape() { return toShape; }
    public String getRelationType() { return relationType; }
    public Map<String, VisioProperty> getProperties() { return properties; }
}
