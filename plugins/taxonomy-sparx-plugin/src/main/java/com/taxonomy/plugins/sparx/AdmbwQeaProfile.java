package com.taxonomy.plugins.sparx;

import com.taxonomy.extension.api.integration.AdmbwQeaExport.Connector;
import com.taxonomy.extension.api.integration.AdmbwQeaExport.Element;

import java.util.Map;
import java.util.Set;

import static com.taxonomy.plugins.sparx.QeaFailure.fail;

/** EA base types and direction from the ADMBw MDG 2026.05 and native MASC reference. */
final class AdmbwQeaProfile {
    static final Map<String, String> DIAGRAM_TYPES = Map.of(
            "C1-S1", "NAFv4-ADMBw Service Specification Viewpoints::C1-S1 - Capability to Service Mapping",
            "R7", "NAFv4-ADMBw Requirement Viewpoints::R7 - Requirement Derivation");
    static final Map<String, String> ELEMENT_TYPES = Map.of(
            "Capability", "Class", "ServiceSpecification", "Class",
            "FunctionalRequirement", "Requirement", "NonfunctionalRequirement", "Requirement",
            "OperationalConstraint", "Class", "OperationalArchitecture", "Class", "OperationalRole", "Part",
            "CapabilityConfiguration", "Class", "ResourceRole", "Part");
    private static final Set<String> CATALOGUE_TYPES = Set.of("Capability", "OperationalArchitecture", "OperationalRole", "ResourceRole");
    private static final Set<String> C1_S1_TYPES = Set.of("Capability", "ServiceSpecification");

    static boolean catalogue(String stereotype) { return CATALOGUE_TYPES.contains(stereotype); }

    static boolean requirement(String stereotype) { return "Requirement".equals(ELEMENT_TYPES.get(stereotype)); }

    static void validateElement(String view, Element element) {
        String stereotype = element.stereotype();
        if (!ELEMENT_TYPES.containsKey(stereotype) || view.equals("C1-S1") != C1_S1_TYPES.contains(stereotype))
            throw fail("QEA_ADMBW_ELEMENT", "The element stereotype is unsupported in this ADMBw viewpoint");
        if (catalogue(stereotype) && element.catalogueGuid() == null) {
            if (stereotype.equals("ResourceRole"))
                throw fail("QEA_ADMBW_PART_OWNER", "A ResourceRole needs the GUID of an existing owned EA Part in the supplied QEA basis");
            throw fail("QEA_ADMBW_CATALOGUE", "This ADMBw stereotype requires the GUID of an existing catalogue element");
        }
        if (!catalogue(stereotype) && element.catalogueGuid() != null)
            throw fail("QEA_ADMBW_ELEMENT", "Only supported catalogue elements can refer to an existing EA GUID");
        if (requirement(stereotype) && (element.requirementVersion() == null || element.requirementVersion() < 1
                || element.requirementContentHash() == null || !element.requirementContentHash().matches("[a-f0-9]{64}")))
            throw fail("QEA_ADMBW_REQUIREMENT", "The requirement needs a retained version and content SHA-256");
    }

    static String connectorType(Connector connector, Element source, Element target) {
        if (source == null || target == null)
            throw fail("QEA_ADMBW_RELATION", "An ADMBw relation needs both endpoints on its diagram");
        String from = source.stereotype(), to = target.stereotype();
        boolean valid = switch (connector.stereotype()) {
            case "Exhibits" -> from.equals("ServiceSpecification") && to.equals("Capability");
            case "DerivedFrom" -> requirement(from) && to.equals("OperationalConstraint");
            case "Satisfy" -> Set.of("OperationalArchitecture", "OperationalRole").contains(from) && to.equals("OperationalConstraint");
            case "ToBeRealizedBy" -> requirement(from) && Set.of("CapabilityConfiguration", "ResourceRole").contains(to);
            default -> false;
        };
        if (!valid) throw fail("QEA_ADMBW_RELATION", "The connector stereotypes or direction do not match the ADMBw viewpoint");
        return connector.stereotype().equals("Exhibits") ? "Abstraction" : "Dependency";
    }

    private AdmbwQeaProfile() { }
}
