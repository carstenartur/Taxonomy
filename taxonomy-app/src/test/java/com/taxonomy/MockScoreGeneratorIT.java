package com.taxonomy;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.BudgetDistribution;
import com.taxonomy.catalog.service.CatalogueOverlayService;
import com.taxonomy.catalog.service.DeterministicNodeScorer;
import com.taxonomy.catalog.service.HierarchyScoreDistributor;
import com.taxonomy.catalog.service.IndependentScoring;
import com.taxonomy.catalog.service.NodeScorer;
import com.taxonomy.dto.SavedAnalysis;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates the three mock-score JSON files under
 * {@code src/main/resources/mock-scores/}.
 *
 * <p>Each file contains scores for every taxonomy node. Ordinary taxonomy
 * categories use hierarchical parent-budget distribution. Concrete Information
 * Product leaves use independent suitability scores with the same default
 * threshold as live product analysis, so they neither consume nor dilute the
 * category budget. This mirrors the mixed-sibling runtime contract introduced
 * for the Information Products overlay.
 *
 * <p>The distribution remains deterministic: category weights and raw product
 * suitability values are derived from stable node-code hashes. Product scores
 * below the default suitability threshold are stored as explicit zeroes.
 *
 * <p><b>Opt-in:</b> only runs when the {@code generateMockScores} system
 * property is set.
 * <pre>{@code
 * mvn test -DgenerateMockScores -Dtest=MockScoreGeneratorIT
 * }</pre>
 *
 * <p>After running, commit the updated JSON files in
 * {@code src/main/resources/mock-scores/}.
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "generateMockScores", matches = ".*")
class MockScoreGeneratorIT {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final int DEFAULT_PRODUCT_MINIMUM_SCORE = 50;

    private static final NodeScorer PRODUCT_SCORER =
            (requirementText, products, parentScore) -> {
                Map<String, Integer> rawScores = DeterministicNodeScorer.INSTANCE.score(
                        requirementText, products, 100);
                Map<String, Integer> thresholdedScores = new LinkedHashMap<>();
                for (TaxonomyNode product : products) {
                    int rawScore = rawScores.getOrDefault(product.getCode(), 0);
                    thresholdedScores.put(
                            product.getCode(),
                            rawScore >= DEFAULT_PRODUCT_MINIMUM_SCORE ? rawScore : 0);
                }
                return thresholdedScores;
            };

    @Autowired
    private HierarchyScoreDistributor distributor;

    @Autowired
    private CatalogueOverlayService catalogueOverlayService;

    // ── Scenario 1: Secure voice communications ────────────────────────────

    private static final Map<String, Integer> VOICE_COMMS_ROOT_SCORES = Map.of(
            "CO", 90,
            "CR", 70,
            "CP", 55,
            "IP", 30,
            "BP", 25,
            "CI", 15,
            "UA", 10,
            "BR", 0
    );

    private static final Map<String, String> VOICE_COMMS_REASONS = Map.of(
            "CO", "Communications Services directly covers voice communication systems, protocols, and infrastructure for tactical and strategic networks.",
            "CR", "Core Services provide the foundational transport and switching capabilities required to carry voice traffic.",
            "CP", "Capabilities packages include the planning and deployment of voice communication systems for operational use.",
            "IP", "Information Products such as voice recordings, message logs, and call records are produced by voice communication systems.",
            "BP", "Business Processes govern the procedures and workflows for requesting and using secure voice channels.",
            "CI", "COI Services may leverage secure voice channels as part of their community information-sharing activities.",
            "UA", "User Applications provide the interfaces (softphones, terminals) that deployed personnel use for voice communications.",
            "BR", "Business Roles were evaluated but are not relevant — the taxonomy does not cover voice communication systems through roles alone."
    );

    // ── Scenario 2: Logistics and supply chain ─────────────────────────────

    private static final Map<String, Integer> LOGISTICS_ROOT_SCORES = Map.of(
            "BP", 85,
            "CP", 70,
            "IP", 65,
            "UA", 50,
            "CR", 45,
            "BR", 30,
            "CO", 20,
            "CI", 15
    );

    private static final Map<String, String> LOGISTICS_REASONS = Map.of(
            "BP", "Business Processes are central to logistics management, encompassing inventory control, distribution workflows, procurement, and supply chain coordination.",
            "CP", "Capabilities packages cover the logistics and supply chain management capabilities required for tracking assets and managing distribution across deployed forces.",
            "IP", "Information Products such as inventory reports, distribution manifests, asset tracking records, and supply chain status reports are core outputs of logistics operations.",
            "UA", "User Applications including inventory management systems, tracking dashboards, and ordering portals are the primary tools for logistics personnel.",
            "CR", "Core Services provide the data exchange and messaging infrastructure needed to synchronise inventory data across distributed logistics nodes.",
            "BR", "Business Roles define the responsibilities of logisticians, supply chain managers, and distribution officers within force structures.",
            "CO", "Communications Services support the real-time data links required to update inventory and coordinate distribution across geographically dispersed supply chain nodes.",
            "CI", "COI Services may expose logistics data feeds to operational communities that need situational awareness of supply availability."
    );

    // ── Scenario 3: Cyber defence monitoring ───────────────────────────────

    private static final Map<String, Integer> CYBER_DEFENCE_ROOT_SCORES = Map.of(
            "CO", 80,
            "CR", 75,
            "CP", 70,
            "IP", 55,
            "CI", 50,
            "BP", 45,
            "UA", 35,
            "BR", 20
    );

    private static final Map<String, String> CYBER_DEFENCE_REASONS = Map.of(
            "CO", "Communications Services include the network monitoring and security services required for cyber defence, including encrypted channels and intrusion detection traffic analysis.",
            "CR", "Core Services provide the fundamental platform security services such as identity management, access control, and security event logging underpinning cyber defence.",
            "CP", "Capabilities packages encompass the cyber defence capabilities including SIEM, vulnerability scanning, and incident response capabilities needed for continuous monitoring.",
            "IP", "Information Products such as threat intelligence reports, incident logs, vulnerability assessments, and security dashboards are produced by the cyber defence programme.",
            "CI", "COI Services include the cyber threat intelligence sharing communities that distribute indicators of compromise and threat actor information across networks.",
            "BP", "Business Processes cover the incident response procedures, threat hunting workflows, and escalation processes for handling cyber security events.",
            "UA", "User Applications include the SOC analyst toolsets, threat hunting platforms, and incident management portals used by cyber defence personnel.",
            "BR", "Business Roles define the responsibilities of SOC analysts, incident responders, and cyber defence coordinators within the cyber defence organisation."
    );

    // ── Generator tests ─────────────────────────────────────────────────────

    @Test
    @Order(1)
    void generateSecureVoiceComms() throws IOException {
        generateAndSave(
                "Provide secure voice communications between HQ and deployed forces",
                VOICE_COMMS_ROOT_SCORES,
                VOICE_COMMS_REASONS,
                "secure-voice-comms.json");
    }

    @Test
    @Order(2)
    void generateLogisticsSupplyChain() throws IOException {
        generateAndSave(
                "Manage logistics and supply chain operations for deployed forces including inventory tracking and distribution",
                LOGISTICS_ROOT_SCORES,
                LOGISTICS_REASONS,
                "logistics-supply-chain.json");
    }

    @Test
    @Order(3)
    void generateCyberDefenceMonitoring() throws IOException {
        generateAndSave(
                "Implement continuous cyber defence monitoring, threat detection, and incident response for IT infrastructure",
                CYBER_DEFENCE_ROOT_SCORES,
                CYBER_DEFENCE_REASONS,
                "cyber-defence-monitoring.json");
    }

    private void generateAndSave(
            String requirement,
            Map<String, Integer> rootScores,
            Map<String, String> rootReasons,
            String filename) throws IOException {

        HierarchyScoreDistributor.DistributionResult result = distributor.distribute(
                rootScores,
                rootReasons,
                requirement,
                DeterministicNodeScorer.INSTANCE,
                BudgetDistribution.INSTANCE,
                node -> catalogueOverlayService.isProduct(node.getCode()),
                PRODUCT_SCORER,
                IndependentScoring.INSTANCE);

        SavedAnalysis analysis = new SavedAnalysis();
        analysis.setVersion(1);
        analysis.setRequirement(requirement);
        analysis.setTimestamp(Instant.now().toString());
        analysis.setProvider("MOCK");
        analysis.setScores(result.scores());
        analysis.setReasons(result.reasons());

        Path output = Path.of("src/main/resources/mock-scores/" + filename);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), analysis);

        System.out.printf(
                "[MockScoreGenerator] %s: %d nodes written to %s%n",
                filename,
                result.scores().size(),
                output);
    }
}
