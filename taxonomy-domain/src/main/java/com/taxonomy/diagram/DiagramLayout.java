package com.taxonomy.diagram;

public record DiagramLayout(
    String direction,
    boolean groupByLayer
) {
    /** Explicit server presentation policy; never changes architecture semantics. */
    public enum Strategy {
        STABLE("stable"), REDUCE_CROSSINGS("reduce-crossings");
        private final String id;
        Strategy(String id) { this.id = id; }
        public String id() { return id; }
        public static Strategy fromId(String id) {
            if (id == null || id.isBlank()) return STABLE;
            for (Strategy strategy : values()) if (strategy.id.equals(id)) return strategy;
            throw new IllegalArgumentException("Unknown layout strategy: " + id);
        }
    }
}
