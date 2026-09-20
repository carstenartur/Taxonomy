package com.taxonomy.reformulation;
import java.util.List;
/** Layout references shared statements, never owns duplicate text. */
public record Section(String id, String taxonomyCode, String title, String summary, List<String> children,
        List<String> statementIds, List<String> questionIds) {
    public Section { children=List.copyOf(children); statementIds=List.copyOf(statementIds); questionIds=List.copyOf(questionIds); }
}
