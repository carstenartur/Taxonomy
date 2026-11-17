package com.taxonomy.visio;

import com.taxonomy.diagram.VisioDrawingOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class VisioDocument {
    private final List<VisioPage> pages = new ArrayList<>();
    private final Map<String, VisioProperty> properties = new TreeMap<>();
    private final List<VisioLoss> losses = new ArrayList<>();

    private VisioDrawingOptions drawingOptions;

    public VisioDrawingOptions getDrawingOptions() { return drawingOptions; }
    public void setDrawingOptions(VisioDrawingOptions drawingOptions) { this.drawingOptions = drawingOptions; }

    public List<VisioPage> getPages() { return pages; }
    public Map<String, VisioProperty> getProperties() { return properties; }
    public List<VisioLoss> getLosses() { return losses; }
}
