package com.taxonomy.portfolio.reformulation;

import com.taxonomy.analysis.service.PromptTemplateService;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class AdoptedLineageScoreReplyTest {
    private static PromptTemplateService templates() {
        var templates = new PromptTemplateService();
        templates.loadDefaults();
        return templates;
    }

    @Test
    void independentBpRootProvidesAnActualSourceWithoutPretendingToAllocateOneHundred() {
        var prompt = templates().renderRootPrompt("BP", "Adopted requirement", "BP: Business Processes\n");
        var scores = AdoptedLineageScoreReply.scores(prompt);
        assertEquals(20, ((Number) ((Map<?, ?>) scores.get("BP")).get("score")).intValue());
    }

    @Test
    void unrelatedRootRemainsExplicitlyZero() {
        var prompt = templates().renderRootPrompt("IP", "Adopted requirement", "IP: Information Products\n");
        var scores = AdoptedLineageScoreReply.scores(prompt);
        assertEquals(0, ((Number) ((Map<?, ?>) scores.get("IP")).get("score")).intValue());
    }

    @Test
    void childQuestionStillAllocatesItsParentBudget() {
        var prompt = templates().renderPrompt("BP", "Adopted requirement",
                "BP-1: Operational process\nBP-2: Other process\n", 20, "BP-1, BP-2");
        var scores = AdoptedLineageScoreReply.scores(prompt);
        assertEquals(20, ((Number) ((Map<?, ?>) scores.get("BP-1")).get("score")).intValue());
        assertEquals(0, ((Number) ((Map<?, ?>) scores.get("BP-2")).get("score")).intValue());
    }
}

/** Deterministic transport response for this lineage scenario, never a production scoring policy. */
final class AdoptedLineageScoreReply {
    private static final Pattern KEYS = Pattern.compile("EXACTLY these keys: ([^\\r\\n]+)");
    private static final Pattern BUDGET = Pattern.compile("distribute the parent relevance score of (\\d+)");

    private AdoptedLineageScoreReply() { }

    static Map<String, Object> scores(String prompt) {
        var match = KEYS.matcher(prompt);
        if (!match.find()) throw new AssertionError("Unexpected outbound model task: "
                + prompt.substring(0, Math.min(180, prompt.length())));
        List<String> keys = Arrays.stream(match.group(1).split(",")).map(String::strip).toList();
        var budget = BUDGET.matcher(prompt);
        Integer childBudget = budget.find() ? Integer.parseInt(budget.group(1)) : null;
        boolean independentRoot = prompt.startsWith("You assess the independent relevance of the offered C3 taxonomy root");
        if (independentRoot && (keys.size() != 1 || childBudget != null
                || !java.util.Set.of("BP", "BR", "CI", "CO", "CP", "CR", "IP", "UA").contains(keys.getFirst()))) {
            throw new AssertionError("Unexpected root assessment scope");
        }
        var scores = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keys.size(); i++) scores.put(keys.get(i),
                Map.of("score", independentRoot ? (keys.get(i).equals("BP") ? 20 : 0)
                                : childBudget != null && i == 0 ? childBudget : 0,
                        "reason", "Transport-only deterministic analysis reply"));
        return scores;
    }
}
