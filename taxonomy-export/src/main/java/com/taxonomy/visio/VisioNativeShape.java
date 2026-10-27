package com.taxonomy.visio;

import java.util.Set;
import java.util.TreeSet;

/** Original editable masters, not redistributed Microsoft stencil assets. */
public enum VisioNativeShape {
    BLOCK(1, "Taxonomy Block"),
    PROCESS(2, "Taxonomy Process"),
    ROLE(3, "Taxonomy Role"),
    APPLICATION(4, "Taxonomy Application"),
    INFORMATION(5, "Taxonomy Information"),
    SERVICE(6, "Taxonomy Service"),
    CONTAINER(7, "Taxonomy Container"),
    CONNECTOR(8, "Taxonomy Connector");

    private final int id;
    private final String label;

    VisioNativeShape(int id, String label) {
        this.id = id;
        this.label = label;
    }

    public int id() { return id; }
    public String label() { return label; }

    public static boolean isPresentationOnly(VisioShape shape) {
        return VisioProperty.bool(true).equals(shape.getProperties().get("taxonomy.presentationOnly"));
    }

    public static boolean isContainer(VisioShape shape) {
        return !isPresentationOnly(shape) && VisioProperty.bool(true).equals(shape.getProperties().get("taxonomy.container"));
    }

    public static VisioNativeShape of(VisioShape shape) {
        if (isPresentationOnly(shape)) return null;
        if (isContainer(shape)) return CONTAINER;
        if (shape.getType() == null) return BLOCK;
        return switch (shape.getType()) {
            case "Business Processes", "BP", "Process" -> PROCESS;
            case "Business Roles", "BR", "BusinessRole" -> ROLE;
            case "User Applications", "Applications", "UA", "UserApplication" -> APPLICATION;
            case "Information Products", "IP", "InformationProduct" -> INFORMATION;
            case "Core Services", "COI Services", "Communications Services", "Services", "Service",
                    "CR", "CI", "CO", "CoreService", "COIService", "CommunicationsService" -> SERVICE;
            default -> BLOCK;
        };
    }

    public static Set<Integer> usedOn(VisioPage page) {
        Set<Integer> result = new TreeSet<>();
        for (VisioShape shape : page.getShapes()) {
            VisioNativeShape kind = of(shape);
            if (kind != null) result.add(kind.id());
        }
        if (!page.getConnects().isEmpty()) result.add(CONNECTOR.id());
        return result;
    }
}
