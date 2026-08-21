taxonomy_service='taxonomy-app/src/main/java/com/taxonomy/catalog/service/TaxonomyService.java'
if ! grep -q '^import org.springframework.core.io.ClassPathResource;$' "$taxonomy_service"; then
  sed -i '/^import org.springframework.core.io.Resource;$/i import org.springframework.core.io.ClassPathResource;' \
    "$taxonomy_service"
fi

python3 - "$taxonomy_service" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
source = path.read_text(encoding='utf-8')
parent_anchor = '''                // Fallback: parentCode might be a UUID — resolve it to the actual code
                if (parent == null && parentCode != null) {
                    String resolvedCode = uuidToCode.get(parentCode);
                    if (resolvedCode != null) {
                        parent = nodeMap.get(resolvedCode);
                        if (parent != null) {
                            node.setParentCode(resolvedCode);
                        }
                    }
                }

                // Last resort: attach to the virtual sheet root
'''
parent_replacement = '''                // Fallback: parentCode might be a UUID — resolve it to the actual code
                if (parent == null && parentCode != null) {
                    String resolvedCode = uuidToCode.get(parentCode);
                    if (resolvedCode != null) {
                        parent = nodeMap.get(resolvedCode);
                        if (parent != null) {
                            node.setParentCode(resolvedCode);
                        }
                    }
                }

                // A self-parent reference is invalid hierarchy data. Treat it like an
                // unresolved parent so the explicit root fallback can recover the node
                // without creating a cycle or a null parent_id.
                if (parent == node) {
                    log.warn("Node '{}' references itself as parent; attaching it to taxonomy root '{}'.",
                            node.getCode(), node.getTaxonomyRoot());
                    parent = null;
                }

                // Last resort: attach to the virtual sheet root
'''
if parent_anchor not in source:
    raise SystemExit('Expected parent-resolution anchor was not found')
source = source.replace(parent_anchor, parent_replacement, 1)

old = '''        nonRoots.sort(Comparator.comparingInt(TaxonomyNode::getLevel));

        int count = 0;
        for (TaxonomyNode node : nonRoots) {
            // Replace the in-memory parent reference with a lightweight managed proxy so
            // that the FK column is set correctly even after earlier PC.clear() calls.
            // getReference() returns an uninitialized proxy whose ID is set immediately;
            // no SELECT is issued because only the FK value (the ID) is needed for the INSERT.
            String parentCode = node.getParentCode();
            if (parentCode != null && codeToId.containsKey(parentCode)) {
                node.setParent(entityManager.getReference(TaxonomyNode.class,
                        codeToId.get(parentCode)));
            }
            entityManager.persist(node);
            codeToId.put(node.getCode(), node.getId());
            count++;
            if (count % BATCH_SIZE == 0) {
                entityManager.flush();
                entityManager.clear();
            }
        }
        entityManager.flush();
        entityManager.clear();
'''
new = '''        nonRoots.sort(Comparator.comparingInt(TaxonomyNode::getLevel)
                .thenComparing(TaxonomyNode::getCode));

        // The catalogue level is descriptive data and is not guaranteed to form a
        // strict persistence order. Persist only nodes whose actual parent has already
        // received an ID. This prevents a same-level child that precedes its parent in
        // the workbook from being inserted with a null parent_id.
        Map<String, TaxonomyNode> pending = new LinkedHashMap<>();
        for (TaxonomyNode node : nonRoots) {
            pending.put(node.getCode(), node);
        }

        int count = 0;
        while (!pending.isEmpty()) {
            boolean madeProgress = false;
            Iterator<Map.Entry<String, TaxonomyNode>> iterator =
                    pending.entrySet().iterator();
            while (iterator.hasNext()) {
                TaxonomyNode node = iterator.next().getValue();
                String parentCode = node.getParentCode();
                Long parentId = parentCode != null ? codeToId.get(parentCode) : null;
                if (parentId == null) {
                    continue;
                }

                node.setParent(entityManager.getReference(TaxonomyNode.class, parentId));
                entityManager.persist(node);
                codeToId.put(node.getCode(), node.getId());
                iterator.remove();
                madeProgress = true;
                count++;
                if (count % BATCH_SIZE == 0) {
                    entityManager.flush();
                    entityManager.clear();
                }
            }

            if (!madeProgress) {
                List<String> unresolvedParents = pending.values().stream()
                        .map(node -> node.getCode() + "->" + node.getParentCode())
                        .sorted()
                        .limit(20)
                        .toList();
                throw new IllegalStateException(
                        "Cannot persist taxonomy hierarchy; unresolved parent references: "
                                + unresolvedParents);
            }
        }
        entityManager.flush();
        entityManager.clear();
'''
if old not in source:
    raise SystemExit('Expected level-only persistence block was not found')
path.write_text(source.replace(old, new, 1), encoding='utf-8')
PY

