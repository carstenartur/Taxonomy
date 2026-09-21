package com.taxonomy.analysis.reformulation;

import com.sun.net.httpserver.HttpServer;
import com.taxonomy.analysis.dto.AiTargetDtos.*;
import com.taxonomy.analysis.service.*;
import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.reformulation.*;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Authored node-input contract, not a generated catalogue or a live-model quality test. */
public final class BoundedNodeSynthesisDriver {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ORIGINAL = "Ausschließlich Terminal, keine Browseroberfläche. Frist: 2 Sekunden.";
    private static final Map<String,String> EDGES = Map.of("edge-link", "{\"sourceCode\":\"BP\",\"targetCode\":\"CP\",\"relationType\":\"supports\"}");

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            switch (args[0]) {
                case "terminals" -> terminals();
                case "dependencies" -> dependencies();
                case "transaction" -> transaction();
                case "statement-dependency" -> statementDependency();
                case "bounds" -> bounds();
                default -> throw new IllegalArgumentException(args[0]);
            }
            return;
        }
        try (Fixture f = new Fixture(16000)) {
            var input = input(ORIGINAL, 6, 2500);
            var result = f.nodes.synthesize(input);
            check(f.prompts.size() > 1, "Oversized parent was not split");
            check(result.preservedStatementIds().containsAll(List.of("detail-0", "detail-1", "detail-2", "detail-3", "detail-4", "detail-5")), "Child detail identity lost");
            check(result.preservedQuestionIds().contains("existing-question"), "Existing child question lost");
            check(!result.questionProposals().isEmpty(), "Questions discovered during grouping lost");
            check(result.statementProposals().size() == f.prompts.size(), "New formulations from groups/aggregate were lost");
            var generatedIds = new HashSet<String>(); result.questionProposals().forEach(q -> generatedIds.add(q.id()));
            result.statementProposals().forEach(v -> check(generatedIds.containsAll(v.questionDependencies()), "New statement lost its decision"));
            for (String prompt : f.prompts) {
                var body = data(prompt);
                check(body.at("/baseline/originalText").asText().equals(ORIGINAL), "Original was summarized or truncated");
                check(body.path("boundaryEdges").has("edge-link"), "Directed cross-boundary relation lost");
                f.policy.requireWithinBudget(prompt, "CUSTOM_OPENAI");
            }
            check(f.failure.get() == null, "Provider fixture failed: " + f.failure.get());
            System.out.println("BOUNDED_PARENT_OK calls=" + f.prompts.size() + " questions=" + result.questionProposals().size());
        }
        try (Fixture f = new Fixture(16000)) {
            var result = f.nodes.synthesize(input(ORIGINAL, 1, 100));
            check(f.prompts.size() == 1, "Small parent must keep the single-call path");
            check(result.preservedStatementIds().contains("detail-0"), "Small input lost details");
            System.out.println("SMALL_PARENT_OK");
        }
        try (Fixture f = new Fixture(16000)) {
            try {
                f.nodes.synthesize(input(ORIGINAL + " Full original.".repeat(5000), 6, 2500));
                throw new AssertionError("Oversized original unexpectedly accepted");
            } catch (IllegalStateException expected) {
                check(expected.getMessage().startsWith("INPUT_TOO_LARGE_FOR_PROVIDER"), "Wrong error: " + expected);
            }
            check(f.prompts.isEmpty(), "Oversized original spent remote requests before rejecting input");
            System.out.println("OVERSIZED_ORIGINAL_OK");
        }
    }

    static void terminals() throws Exception {
        try (Fixture f = new Fixture(16000)) {
            var base = input(ORIGINAL, 0, 0);
            var terminals = new ArrayList<Map<String,Object>>();
            for (int i=0;i<6;i++) terminals.add(Map.of("id", "terminal-"+i, "description", "Frozen terminal " + i + " " + "z".repeat(2800)));
            var node = new NodeSynthesisInput(base.baseline(),base.nodeId(),base.parentId(),
                    JSON.writeValueAsString(Map.of("current", "Parent", "directParentContributions",List.of("parent contribution"),"terminalContributions",terminals)),
                    base.sourceAnchors(),base.directContributions(),List.of(),base.boundaryEdges(),base.answers(),base.openDecisions(),base.preservationContract());
            var parts = new ArrayList<NodeSynthesisResult>();
            var steps = ReformulationStepExecutor.of((kind, request, type, action) -> {
                Object part = action.get();
                if ("NODE_GROUP".equals(kind)) parts.add((NodeSynthesisResult) part);
                return part;
            });
            try {
                f.nodes.synthesize(node, steps);
                throw new AssertionError("Unique terminal contexts exceeded the budget but aggregate was accepted");
            } catch (IllegalStateException expected) {
                check(expected.getMessage().startsWith("INPUT_TOO_LARGE_FOR_PROVIDER"), "Wrong aggregate failure");
            }
            check(parts.size() == 2 && f.prompts.size() == 2, "Keep complete groups; do not send an oversized aggregate");
            var seen = new HashSet<String>();
            for (String prompt : f.prompts) {
                var description = JSON.readTree(data(prompt).path("nodeDescription").asText());
                description.path("terminalContributions").forEach(t -> {
                    check(seen.add(t.path("id").asText()), "Terminal processed twice");
                    check(t.path("description").asText().endsWith("z".repeat(2800)), "Terminal description truncated");
                });
                check(description.path("directParentContributions").size()==1,"Direct parent contribution dropped");
            }
            check(seen.size()==6,"Terminal contribution lost");
            check(parts.stream().allMatch(p -> !p.questionProposals().isEmpty()), "Discovered decisions lost");
            check(parts.stream().flatMap(p -> p.questionProposals().stream()).flatMap(q -> q.discoveries().stream())
                    .allMatch(d -> d.context().contains("z".repeat(2800))),
                    "Stored question discovery was replaced by its prompt projection");
            System.out.println("TERMINAL_GROUPS_OK calls="+f.prompts.size());
        }
    }

    static void dependencies() throws Exception {
        try (Fixture f = new Fixture(16000)) {
            var base = input(ORIGINAL,6,2500);
            var children = new ArrayList<>(base.children());var last=children.getLast();
            var dependent = new DecisionQuestion("dependent-question",new DecisionQuestion.Key("time","follow-up","BP"),"Follow-up to correction decision",
                    List.of(new DecisionQuestion.Discovery("BP","Process","Depends on correction",List.of(),List.of("BP"),List.of())),
                    List.of("detail-5"),new DecisionQuestion.AnswerSchema(DecisionQuestion.AnswerSchema.Kind.TEXT,List.of(),null,null,null),
                    List.of("existing-question"),List.of(),"Clarify scope",DecisionQuestion.State.OPEN);
            children.set(children.size()-1,new NodeSynthesisResult(last.nodeId(),last.summary(),last.statementProposals(),last.preservedStatementIds(),
                    List.of(dependent),last.preservedQuestionIds(),last.uncoveredSourceRefs(),last.conflictCandidates()));
            var node=new NodeSynthesisInput(base.baseline(),base.nodeId(),base.parentId(),base.nodeDescription(),base.sourceAnchors(),base.directContributions(),
                    children,base.boundaryEdges(),base.answers(),base.openDecisions(),base.preservationContract());
            f.nodes.synthesize(node);
            for (String prompt:f.prompts) {
                var body=data(prompt);var ids=new HashSet<String>();var questions=new ArrayList<tools.jackson.databind.JsonNode>();
                body.path("openDecisions").forEach(questions::add);
                body.path("children").forEach(c->c.path("questionProposals").forEach(questions::add));
                questions.forEach(q->ids.add(q.path("id").asText()));
                questions.forEach(q->q.path("prerequisites").forEach(prerequisite->check(ids.contains(prerequisite.asText()),
                        "Cross-group prerequisite " + prerequisite.asText() + " omitted from bounded prompt")));
            }
            System.out.println("DEPENDENCY_CLOSURE_OK");
        }
    }

    static void statementDependency() throws Exception {
        try (Fixture f = new Fixture(16000)) {
            var base = input(ORIGINAL, 6, 2500);
            var children = new ArrayList<>(base.children()); var last = children.getLast(); var old = last.statementProposals().getFirst();
            var dependent = new Statement(old.id(), old.wording(), old.sourceSpans(), old.provenance(), old.architectureLinks(),
                    List.of("existing-question"), old.conditionalValidity(), old.editingOrigin(), old.reviewState());
            children.set(children.size()-1, new NodeSynthesisResult(last.nodeId(), last.summary(), List.of(dependent),
                    last.preservedStatementIds(), last.questionProposals(), last.preservedQuestionIds(), last.uncoveredSourceRefs(), last.conflictCandidates()));
            var node = new NodeSynthesisInput(base.baseline(), base.nodeId(), base.parentId(), base.nodeDescription(), base.sourceAnchors(),
                    base.directContributions(), children, base.boundaryEdges(), base.answers(), base.openDecisions(), base.preservationContract());
            f.nodes.synthesize(node);
            for (String prompt : f.prompts) {
                var body = data(prompt); var ids = new HashSet<String>(); var statements = new ArrayList<tools.jackson.databind.JsonNode>();
                body.path("openDecisions").forEach(q -> ids.add(q.path("id").asText()));
                body.path("directContributions").forEach(statements::add);
                body.path("children").forEach(c -> {
                    c.path("questionProposals").forEach(q -> ids.add(q.path("id").asText()));
                    c.path("statementProposals").forEach(statements::add);
                });
                statements.forEach(v -> v.path("questionDependencies").forEach(q ->
                        check(ids.contains(q.asText()), "Statement decision dependency missing from its grouped prompt: " + q.asText())));
            }
            System.out.println("STATEMENT_DEPENDENCY_OK");
        }
    }

    static void transaction() throws Exception {
        try (Fixture f=new Fixture(16000)) {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                f.nodes.synthesize(input(ORIGINAL+"a".repeat(50000),6,1000));
                throw new AssertionError("Transaction boundary was not rejected");
            } catch (IllegalStateException expected) {
                check(expected.getMessage().equals("LLM_CALL_INSIDE_TRANSACTION"),"Transaction violation hidden by batching: "+expected);
            } finally { org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false); }
            check(f.prompts.isEmpty(),"Transaction-held provider request");
            System.out.println("TRANSACTION_GUARD_OK");
        }
    }

    static void bounds() throws Exception {
        try (Fixture f=new Fixture(8000)) {
            try {
                f.nodes.synthesize(input(ORIGINAL,33,2700));
                throw new AssertionError("Group count limit ignored");
            } catch (IllegalStateException expected) { check(expected.getMessage().startsWith("INPUT_TOO_LARGE_FOR_PROVIDER"),"Wrong bounded rejection"); }
            check(f.prompts.isEmpty(),"Over-limit input spent model calls before complete preflight");
            System.out.println("GROUP_COUNT_BOUND_OK");
        }
    }

    static NodeSynthesisInput input(String original, int count, int detailLength) {
        var baseline = new ReformulationBaseline(new ReformulationBaseline.Scope("repo", "workspace", "draft", 1, 1), 1,
                original, StableIdentityHash.sha256(original), "snapshot", "{}", Map.of(), "de", "walk-up-v1");
        List<String> roots = List.of("CP", "BR", "CR", "CO", "IP", "UA");
        var children = new ArrayList<NodeSynthesisResult>();
        for (int i = 0; i < count; i++) {
            var statement = new Statement("detail-" + i, "Detail " + i + ": " + "x".repeat(detailLength), List.of(), Statement.Provenance.MODEL_ADDITION,
                    List.of(roots.get(i % roots.size())), List.of(), null, Statement.EditingOrigin.MODEL, "UNREVIEWED");
            List<DecisionQuestion> questions = i == 0 ? List.of(new DecisionQuestion("existing-question", new DecisionQuestion.Key("time", "correction", "BP"),
                    "Wer darf Zeitbuchungen korrigieren?", List.of(new DecisionQuestion.Discovery("BP", "Process", "Not specified", List.of(), List.of("BP"), List.of())),
                    List.of("detail-0"), new DecisionQuestion.AnswerSchema(DecisionQuestion.AnswerSchema.Kind.TEXT, List.of(), null, null, null),
                    List.of(), List.of(), "Clarify roles", DecisionQuestion.State.OPEN)) : List.of();
            children.add(new NodeSynthesisResult(roots.get(i % roots.size()), "Child " + i + " summary", List.of(statement), List.of(), questions, List.of(), List.of(), List.of()));
        }
        return new NodeSynthesisInput(baseline, "BP", null, "Frozen parent description", List.of(), List.of(), children, EDGES, List.of(), List.of(), "Preserve all child details and questions.");
    }

    static tools.jackson.databind.JsonNode data(String prompt) {
        int begin = prompt.indexOf("INPUT_DATA_JSON\n");
        if (begin < 0) throw new AssertionError("Missing input payload");
        String body = prompt.substring(begin + "INPUT_DATA_JSON\n".length());
        int repair = body.indexOf("\nVALIDATION_ERRORS");
        return JSON.readTree(repair < 0 ? body : body.substring(0, repair));
    }

    static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final List<String> prompts = Collections.synchronizedList(new ArrayList<>());
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AiPromptBudgetPolicy policy;
        final NodeReformulationService nodes;
        Fixture(int maxChars) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                try {
                    String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    String prompt = JSON.readTree(raw).at("/messages/0/content").asText();
                    prompts.add(prompt);
                    var input = data(prompt);
                    var statements = new LinkedHashSet<String>(); var questions = new LinkedHashSet<String>();
                    input.path("directContributions").forEach(s -> statements.add(s.path("id").asText()));
                    input.path("openDecisions").forEach(q -> questions.add(q.path("id").asText()));
                    input.path("children").forEach(c -> {
                        c.path("statementProposals").forEach(s -> statements.add(s.path("id").asText()));
                        c.path("preservedStatementIds").forEach(s -> statements.add(s.asText()));
                        c.path("questionProposals").forEach(q -> questions.add(q.path("id").asText()));
                        c.path("preservedQuestionIds").forEach(q -> questions.add(q.asText()));
                    });
                    String group = String.join(",", statements);
                    var question = Map.ofEntries(Map.entry("subject", "time"), Map.entry("dimension", "group-check"), Map.entry("scope", "BP"),
                            Map.entry("wording", "Offene Entscheidung für " + group), Map.entry("rationale", "Unspecified in original"),
                            Map.entry("affectedStatementIds", statements), Map.entry("sourceSpans", List.of()), Map.entry("nodeIds", List.of("BP")),
                            Map.entry("edgeIds", List.of("edge-link")), Map.entry("answerSchema", Map.of("kind", "TEXT", "options", List.of())),
                            Map.entry("prerequisites", List.of()), Map.entry("consequences", "Clarify affected portion"));
                    var mutableQuestion = new LinkedHashMap<String,Object>(question);
                    var schema = new LinkedHashMap<String,Object>(); schema.put("kind","TEXT");schema.put("options",List.of());schema.put("unit",null);schema.put("minimum",null);schema.put("maximum",null);
                    mutableQuestion.put("answerSchema",schema);
                    var proposedStatement = new LinkedHashMap<String,Object>();
                    proposedStatement.put("wording", "Vorgeschlagener Teiltext für " + group);
                    proposedStatement.put("provenance", "MODEL_ADDITION"); proposedStatement.put("sourceSpans", List.of());
                    proposedStatement.put("architectureLinks", List.of("BP")); proposedStatement.put("questionDependencies", List.of("new-question:0"));
                    proposedStatement.put("conditionalValidity", null);
                    var affectedIds = new LinkedHashSet<>(statements); affectedIds.add("new-statement:0");
                    mutableQuestion.put("affectedStatementIds", affectedIds);
                    var response = Map.of("summary", "Zeiterfassung: " + group, "statementProposals", List.of(proposedStatement), "preservedStatementIds", statements,
                            "questionProposals", List.of(mutableQuestion), "preservedQuestionIds", questions, "uncoveredSourceRefs", List.of(), "conflictCandidates", List.of());
                    byte[] bytes = JSON.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", Map.of("content", JSON.writeValueAsString(response)), "finish_reason", "stop"))));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                } catch (Throwable error) {
                    failure.set(error); exchange.sendResponseHeaders(500, -1);
                } finally { exchange.close(); }
            }); server.start();
            var config = new LlmProviderConfig(null);
            set(config,"llmProviderConfig","CUSTOM_OPENAI");set(config,"customLlmUrl","http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
            set(config,"customLlmModel","bounded-parent-test");set(config,"customLlmApiKey","");
            var target = new AiTargetDescriptor("test", "Test", "CUSTOM_OPENAI", "bounded-parent-test", AiTargetMode.REMOTE, AiTargetHealth.READY,
                    true, false, false, new PromptBudget(maxChars, maxChars * 4, (maxChars + 3) / 4), "test", null);
            policy = new AiPromptBudgetPolicy(null) {
                @Override public AiTargetDescriptor requireWithinBudget(String text, String provider) { return super.requireWithinBudget(text,target); }
            };
            var registry = new LlmGatewayRegistry(config,new RestTemplate(),JSON,null,null,null,policy);
            nodes = new NodeReformulationService(registry,config,JSON);
        }
        public void close() { server.stop(0); }
    }
    private static void set(Object object,String field,Object value) throws Exception { var f=object.getClass().getDeclaredField(field);f.setAccessible(true);f.set(object,value); }
    static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
