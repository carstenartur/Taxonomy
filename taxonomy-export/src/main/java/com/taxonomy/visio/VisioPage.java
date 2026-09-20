package com.taxonomy.visio;

import java.util.ArrayList;
import java.util.List;

public class VisioPage {
    private final String id;
    private final String name;
    private boolean relationshipDetail;
    private final List<VisioShape> shapes = new ArrayList<>();
    private final List<VisioConnect> connects = new ArrayList<>();

    public VisioPage(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public boolean isRelationshipDetail() { return relationshipDetail; }
    public void setRelationshipDetail(boolean relationshipDetail) { this.relationshipDetail = relationshipDetail; }

    public String getId() { return id; }
    public String getName() { return name; }
    public List<VisioShape> getShapes() { return shapes; }
    public List<VisioConnect> getConnects() { return connects; }
}
