package com.taxonomy.visio;

public class VisioConnect {
    private final String fromShape;
    private final String toShape;
    private final String relationType;

    public VisioConnect(String fromShape, String toShape, String relationType) {
        this.fromShape = fromShape;
        this.toShape = toShape;
        this.relationType = relationType;
    }

    public String getFromShape() { return fromShape; }
    public String getToShape() { return toShape; }
    public String getRelationType() { return relationType; }
}
