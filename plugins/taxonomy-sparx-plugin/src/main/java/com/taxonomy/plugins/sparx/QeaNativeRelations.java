package com.taxonomy.plugins.sparx;

/** EA's physical connector representation; canonical semantics remain in the shared mapping library. */
final class QeaNativeRelations {
    private QeaNativeRelations() { }
    static String nativeType(String transport) { return "Composition".equals(transport) ? "Aggregation" : transport; }
    static int sourceAggregation(String transport, String direction) {
        return "Composition".equals(transport) && "Source -> Destination".equals(direction) ? 2 : 0;
    }
    static int destinationAggregation(String transport, String direction) {
        return "Composition".equals(transport) && "Destination -> Source".equals(direction) ? 2 : 0;
    }
    static String transport(QeaDatabase.Row row) {
        if (row.text("connector_type").equals("Aggregation")) {
            String direction = row.text("direction");
            if ((direction.equals("Source -> Destination") || direction.equals("Destination -> Source"))
                    && row.number("sourceisaggregate") == sourceAggregation("Composition", direction)
                    && row.number("destisaggregate") == destinationAggregation("Composition", direction)) return "Composition";
        }
        return row.text("connector_type");
    }
}
