package com.taxonomy;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.ObjectMapper;
import com.taxonomy.dto.SavedAnalysis;
import com.taxonomy.model.TaxonomyNode;
import com.taxonomy.repository.TaxonomyNodeRepository;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;

/**
 * Generates the three mock-score JSON files under {@code src/main/resources/mock-scores/}.
 *
 * <p>Each file contains scores for <em>all</em> taxonomy nodes (roots + every child at every
 * depth level), using a hierarchical distribution where children scores always sum to their
 * parent's score.  This satisfies the correct scoring model: each root taxonomy is rated
 * independently on a 0–100 scale ("how well does this taxonomy cover the requirement?") and
 * the score is then subdivided down the hierarchy.
 *
 * <p><b>Opt-in:</b> only runs when the {@code generateMockScores} system property is set.
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

    @Autowired
    private TaxonomyNodeRepository repository;

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    // ── Scenario 1: Secure voice communications ───────────────────────────────

    /** Root scores for the "secure voice communications" scenario. */
    private static final Map<String, Integer> VOICE_COMMS_ROOT_SCORES = Map.of(
            "CO", 90,
            "CR", 70,
            "CP", 55,
            "IP", 30,
            "BP", 25,
            "CI", 15,
            "UA", 10,
            "BR",  0
    );

    /** Root-level reason texts for the "secure voice communications" scenario. */
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

    // ── Scenario 2: Logistics and supply chain ───────────────────────────────

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

    // ── Scenario 3: Cyber defence monitoring ─────────────────────────────────

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

    // ── Generator tests ───────────────────────────────────────────────────────

    @Test
    @Order(1)
    void generateSecureVoiceComms() throws IOException {
        generateAndSave(
                "Provide secure voice communications between HQ and deployed forces",
                VOICE_COMMS_ROOT_SCORES,
                VOICE_COMMS_REASONS,
                "secure-voice-comms.json"
        );
    }

    @Test
    @Order(2)
    void generateLogisticsSupplyChain() throws IOException {
        generateAndSave(
                "Manage logistics and supply chain operations for deployed forces including inventory tracking and distribution",
                LOGISTICS_ROOT_SCORES,
                LOGISTICS_REASONS,
                "logistics-supply-chain.json"
        );
    }

    @Test
    @Order(3)
    void generateCyberDefenceMonitoring() throws IOException {
        generateAndSave(
                "Implement continuous cyber defence monitoring, threat detection, and incident response for IT infrastructure",
                CYBER_DEFENCE_ROOT_SCORES,
                CYBER_DEFENCE_REASONS,
                "cyber-defence-monitoring.json"
        );
    }

    // ── Core generation logic ─────────────────────────────────────────────────

    /**
     * Loads the full taxonomy, computes hierarchical scores for the given root-score map,
     * and writes a {@link SavedAnalysis} JSON file with scores for <em>all</em> nodes.
     */
    private void generateAndSave(String requirement,
                                 Map<String, Integer> rootScores,
                                 Map<String, String> rootReasons,
                                 String filename) throws IOException {

        List<TaxonomyNode> allNodes = repository.findAll();

        // Build parent → sorted-children map for deterministic traversal
        Map<String, List<TaxonomyNode>> childrenMap = buildChildrenMap(allNodes);

        // Collect root nodes (level 0) sorted by code
        List<TaxonomyNode> roots = allNodes.stream()
                .filter(n -> n.getLevel() == 0)
                .sorted(Comparator.comparing(TaxonomyNode::getCode))
                .toList();

        // Output maps (insertion-ordered: roots first, then children breadth-first)
        Map<String, Integer> scores  = new LinkedHashMap<>();
        Map<String, String>  reasons = new LinkedHashMap<>();

        for (TaxonomyNode root : roots) {
            int rootScore = rootScores.getOrDefault(root.getCode(), 0);
            scores.put(root.getCode(), rootScore);
            reasons.put(root.getCode(), rootReasons.getOrDefault(root.getCode(), ""));
            distributeScores(root.getCode(), rootScore, childrenMap, scores, reasons, rootReasons);
        }

        SavedAnalysis analysis = new SavedAnalysis();
        analysis.setVersion(1);
        analysis.setRequirement(requirement);
        analysis.setTimestamp(Instant.now().toString());
        analysis.setProvider("MOCK");
        analysis.setScores(scores);
        analysis.setReasons(reasons);

        Path output = Path.of("src/main/resources/mock-scores/" + filename);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), analysis);

        System.out.printf("[MockScoreGenerator] %s: %d nodes written to %s%n",
                filename, scores.size(), output);
    }

    /**
     * Recursively distributes {@code parentScore} across the children of {@code parentCode},
     * guaranteeing that the sum of all child scores equals {@code parentScore}.
     *
     * <p>Each child's weight is a deterministic positive integer derived from its node code
     * (using {@code (Math.abs(code.hashCode()) % 100) + 1}), so the distribution is stable
     * across runs but varies across sibling nodes.
     */
    private void distributeScores(String parentCode,
                                  int parentScore,
                                  Map<String, List<TaxonomyNode>> childrenMap,
                                  Map<String, Integer> scores,
                                  Map<String, String> reasons,
                                  Map<String, String> rootReasonsByRoot) {

        List<TaxonomyNode> children = childrenMap.getOrDefault(parentCode, List.of());
        if (children.isEmpty()) {
            return;
        }

        if (parentScore == 0) {
            // All children get 0 — the taxonomy is not relevant for this requirement
            for (TaxonomyNode child : children) {
                scores.put(child.getCode(), 0);
                String root = child.getTaxonomyRoot() != null ? child.getTaxonomyRoot() : "";
                reasons.put(child.getCode(), rootReasonsByRoot.getOrDefault(root, ""));
                distributeScores(child.getCode(), 0, childrenMap, scores, reasons, rootReasonsByRoot);
            }
            return;
        }

        // Compute deterministic weights from each child's node code
        int[] weights = children.stream()
                .mapToInt(c -> (Math.abs(c.getCode().hashCode()) % 100) + 1)
                .toArray();
        int totalWeight = Arrays.stream(weights).sum();

        // Floor-based proportional distribution
        int[] childScores = new int[children.size()];
        double[] fractions = new double[children.size()];
        int distributed = 0;
        for (int i = 0; i < children.size(); i++) {
            double raw = (double) parentScore * weights[i] / totalWeight;
            childScores[i] = (int) raw;           // floor
            fractions[i]   = raw - childScores[i]; // fractional part
            distributed   += childScores[i];
        }

        // Distribute remainder (parentScore - floor_sum) to nodes with largest fractions
        int remainder = parentScore - distributed;
        Integer[] byFraction = IntStream.range(0, children.size())
                .boxed()
                .sorted((a, b) -> Double.compare(fractions[b], fractions[a]))
                .toArray(Integer[]::new);
        for (int i = 0; i < remainder; i++) {
            childScores[byFraction[i]]++;
        }

        // Store scores and recurse
        for (int i = 0; i < children.size(); i++) {
            TaxonomyNode child = children.get(i);
            scores.put(child.getCode(), childScores[i]);
            String root = child.getTaxonomyRoot() != null ? child.getTaxonomyRoot() : "";
            reasons.put(child.getCode(), rootReasonsByRoot.getOrDefault(root, ""));
            distributeScores(child.getCode(), childScores[i], childrenMap, scores, reasons, rootReasonsByRoot);
        }
    }

    /**
     * Builds a parent-code → sorted-children list map from all loaded nodes.
     * Children are sorted by code for deterministic traversal order.
     */
    private Map<String, List<TaxonomyNode>> buildChildrenMap(List<TaxonomyNode> allNodes) {
        Map<String, List<TaxonomyNode>> map = new HashMap<>();
        for (TaxonomyNode node : allNodes) {
            if (node.getParentCode() != null) {
                map.computeIfAbsent(node.getParentCode(), k -> new ArrayList<>())
                   .add(node);
            }
        }
        // Sort each child list by code for determinism
        map.values().forEach(list -> list.sort(Comparator.comparing(TaxonomyNode::getCode)));
        return map;
    }
}
