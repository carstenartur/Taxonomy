package com.taxonomy.visio;

public class VisioShape {
    private final String id;
    private final String text;
    private final double x;
    private final double y;
    private final double width;
    private final double height;
    private final String type;
    private final boolean anchor;

    public VisioShape(String id, String text, double x, double y, double width, double height,
                      String type, boolean anchor) {
        this.id = id;
        this.text = text;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.type = type;
        this.anchor = anchor;
    }

    public String getId() { return id; }
    public String getText() { return text; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getWidth() { return width; }
    public double getHeight() { return height; }
    public String getType() { return type; }
    public boolean isAnchor() { return anchor; }
}
