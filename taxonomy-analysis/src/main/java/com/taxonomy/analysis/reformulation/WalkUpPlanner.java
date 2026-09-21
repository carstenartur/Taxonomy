package com.taxonomy.analysis.reformulation;

import com.taxonomy.reformulation.Statement;
import java.util.*;

/** Deterministic DAG plan. Terminal analysis contributions are grouped at their nearest parent. */
public class WalkUpPlanner {
    public static final String DOCUMENT_ROOT="@document";
    public record Node(String id,List<String> parentIds,String description,int score,List<Statement> directContributions) {
        public Node { parentIds=List.copyOf(parentIds);directContributions=List.copyOf(directContributions); }
    }
    public record Step(String nodeId,List<String> terminalIds,List<String> childTaskIds,List<String> directNodeIds) {
        public Step { terminalIds=List.copyOf(terminalIds);childTaskIds=List.copyOf(childTaskIds);directNodeIds=List.copyOf(directNodeIds); }
    }
    public List<Step> plan(List<Node> nodes) {
        var byId=new TreeMap<String,Node>();var children=new TreeMap<String,SortedSet<String>>();
        for(var node:nodes) {
            if(node.id()==null || node.id().isBlank() || node.id().equals(DOCUMENT_ROOT) || byId.put(node.id(),node)!=null)
                throw new IllegalArgumentException("Duplicate/reserved node ID: "+node.id());
            children.put(node.id(),new TreeSet<>());
        }
        for(var node:nodes) for(String parent:node.parentIds()) {
            if(!byId.containsKey(parent)) throw new IllegalArgumentException("Unknown parent "+parent+" of "+node.id());
            children.get(parent).add(node.id());
        }
        var ordered=new ArrayList<String>();var completed=new HashSet<String>();
        for(String id:byId.keySet()) visit(id,children,new LinkedHashSet<>(),completed,ordered);
        var relevant=new HashSet<String>();
        for(String id:ordered) if(byId.get(id).score()>0 || !byId.get(id).directContributions().isEmpty() || children.get(id).stream().anyMatch(relevant::contains)) relevant.add(id);
        if(relevant.isEmpty()) return List.of(new Step(DOCUMENT_ROOT,List.of(),List.of(),List.of()));
        var tasks=new HashSet<String>();
        for(String id:ordered) if(relevant.contains(id) && (children.get(id).stream().anyMatch(relevant::contains) || byId.get(id).parentIds().isEmpty())) tasks.add(id);
        var result=new ArrayList<Step>();
        for(String id:ordered) if(tasks.contains(id)) {
            var terminals=children.get(id).stream().filter(relevant::contains).filter(c->!tasks.contains(c)).toList();
            var childTasks=children.get(id).stream().filter(tasks::contains).toList();
            var node=byId.get(id);
            result.add(new Step(id,terminals,childTasks,node.score()>0 || !node.directContributions().isEmpty()?List.of(id):List.of()));
        }
        return List.copyOf(result);
    }
    private void visit(String id,Map<String,SortedSet<String>> children,LinkedHashSet<String> path,Set<String> completed,List<String> ordered) {
        if(completed.contains(id)) return;
        if(!path.add(id)) throw new IllegalArgumentException("Hierarchy cycle: "+String.join(" -> ",path)+" -> "+id);
        for(String child:children.get(id)) visit(child,children,path,completed,ordered);
        path.remove(id);completed.add(id);ordered.add(id);
    }
}
