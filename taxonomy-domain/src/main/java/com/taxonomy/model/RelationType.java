package com.taxonomy.model;

public enum RelationType {
    REALIZES,           // Capability → Service (NAF NCV-2, TOGAF SBB)
    SUPPORTS,           // Service → Business Process (TOGAF Business Architecture)
    CONSUMES,           // Business Process → Information Product (TOGAF Data Architecture)
    USES,               // User Application → Core Service (NAF NSV-1)
    FULFILLS,           // COI Service → Capability (NAF NCV-5)
    ASSIGNED_TO,        // Business Role → Business Process (TOGAF Org mapping)
    DEPENDS_ON,         // Service → Service (Technical dependency)
    PRODUCES,           // Business Process → Information Product (Data flow)
    COMMUNICATES_WITH,  // Communications Service → Core Service (NAF NSOV)
    CONTAINS,           // System → Component containment (C4 model)
    RELATED_TO          // Generic fallback relation
}
