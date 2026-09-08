package com.taxonomy.visio;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class VisioDocument {
    private final List<VisioPage> pages = new ArrayList<>();
    private final Map<String, VisioProperty> properties = new TreeMap<>();
    private final List<VisioLoss> losses = new ArrayList<>();

    public List<VisioPage> getPages() { return pages; }
    public Map<String, VisioProperty> getProperties() { return properties; }
    public List<VisioLoss> getLosses() { return losses; }
}
