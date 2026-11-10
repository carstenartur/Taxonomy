package com.taxonomy.sparx.mapping;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable, deliberately bounded interpretation of an exact external MDG revision. */
public record AdmbwSourceProfile(String id, String repository, String sourceRevision,
        String mdgPath, String mdgSha256, String referencePath, String referenceSha256,
        String profileVersion, String nativeNamespace, String guideVersion, String generatorVersion, String upstreamGeneratorVersion,
        Set<String> supportedSubset, Map<String, String> diagramTypes, Map<String, String> elementTypes,
        Map<String, String> connectorTypes, Map<String, ConnectorRule> connectorRules) {
    public record ConnectorRule(Set<String> clients, Set<String> suppliers) {
        public ConnectorRule { clients = Set.copyOf(clients); suppliers = Set.copyOf(suppliers); }
        public boolean accepts(String client, String supplier) { return clients.contains(client) && suppliers.contains(supplier); }
    }
    public static final AdmbwSourceProfile CURRENT = new AdmbwSourceProfile(
            "admbw-2026.05-fffmlv-2.0-c1-s1-r7", "https://github.com/Mitchel85/EAM-Toolfamilie",
            "6b1baa03bb097d3aa9afc8bde4d56e275e8a0e93", "packages/rules/NAFv4-ADMBw-MDG-2026.05.xml",
            "e2e8c69281dcc83ad4382bd1b196c23850a847defb33d0a0ffcb429d747f3643",
            "packages/core/fixtures/Musterarchitektur_MASC.qea",
            "d64506532debac09db8ada832b66f2798d28d6e77eb8e2b441d1883b7850d009",
            "2026.05", "NAFv4-ADMBw", "FFFmLV 2.0", "taxonomy-admbw-qea/1", "not recorded in source MDG",
            Set.of("C1-S1", "R7"),
            Map.of("C1-S1", "NAFv4-ADMBw Service Specification Viewpoints::C1-S1 - Capability to Service Mapping",
                    "R7", "NAFv4-ADMBw Requirement Viewpoints::R7 - Requirement Derivation"),
            Map.of("Capability", "Class", "ServiceSpecification", "Class", "FunctionalRequirement", "Requirement",
                    "NonfunctionalRequirement", "Requirement", "OperationalConstraint", "Class", "OperationalArchitecture", "Class",
                    "OperationalRole", "Part", "CapabilityConfiguration", "Class", "ResourceRole", "Part"),
            Map.of("Exhibits", "Abstraction", "DerivedFrom", "Dependency", "Satisfy", "Dependency", "ToBeRealizedBy", "Dependency"),
            Map.of("Exhibits", new ConnectorRule(Set.of("ServiceSpecification"), Set.of("Capability")),
                    "DerivedFrom", new ConnectorRule(Set.of("FunctionalRequirement", "NonfunctionalRequirement"), Set.of("OperationalConstraint")),
                    "Satisfy", new ConnectorRule(Set.of("OperationalArchitecture", "OperationalRole"), Set.of("OperationalConstraint")),
                    "ToBeRealizedBy", new ConnectorRule(Set.of("FunctionalRequirement", "NonfunctionalRequirement"), Set.of("CapabilityConfiguration", "ResourceRole"))));

    public AdmbwSourceProfile {
        supportedSubset = Set.copyOf(supportedSubset); diagramTypes = Map.copyOf(diagramTypes);
        elementTypes = Map.copyOf(elementTypes); connectorTypes = Map.copyOf(connectorTypes); connectorRules = Map.copyOf(connectorRules);
    }
    public void verifyMdg(byte[] exactSourceBytes) {
        try {
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(exactSourceBytes));
            if (!mdgSha256.equals(actual)) throw new IllegalArgumentException("ADMBw source fingerprint drift: " + actual);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public String sourceUrl() { return repository + "/blob/" + sourceRevision + "/" + mdgPath; }
    public List<String> authoringElementTypes() {
        return elementTypes.keySet().stream().filter(type -> !type.equals("Capability") && !elementTypes.get(type).equals("Requirement")).sorted().toList();
    }
}
