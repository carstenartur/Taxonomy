-- A branch is authority, never a default inferred from mutable workspace state.
ALTER TABLE relation_hypothesis ADD COLUMN IF NOT EXISTS branch_name varchar(255);
ALTER TABLE relation_evidence ADD COLUMN IF NOT EXISTS repository_id varchar(255);
ALTER TABLE relation_evidence ADD COLUMN IF NOT EXISTS workspace_scope_key varchar(255);
ALTER TABLE relation_evidence ADD COLUMN IF NOT EXISTS branch_name varchar(255);

CREATE TEMP TABLE hypothesis_branch_authority ON COMMIT DROP AS
WITH links AS (
    SELECT h.*, s.id AS snapshot_found, s.project_id AS snapshot_project,
           s.requirement_id AS snapshot_requirement, s.scope_key AS snapshot_scope,
           nullif(btrim(s.workspace_id), '') AS snapshot_workspace,
           nullif(btrim(s.branch_name), '') AS snapshot_branch,
           p.id AS project_found, nullif(btrim(p.repository_id), '') AS project_repository,
           nullif(btrim(p.workspace_id), '') AS project_workspace,
           nullif(btrim(p.workspace_scope), '') AS project_workspace_scope,
           nullif(btrim(p.branch_name), '') AS project_branch, p.scope_key AS project_scope,
           r.id AS requirement_found, r.project_id AS requirement_project, r.scope_key AS requirement_scope
    FROM relation_hypothesis h
    LEFT JOIN req_analysis_snapshot s ON s.id = h.analysis_snapshot_id
    LEFT JOIN arch_project p ON p.id = coalesce(s.project_id, h.project_id)
    LEFT JOIN project_requirement r ON r.id = coalesce(h.requirement_id, s.requirement_id)
), canonical AS (
    SELECT links.*, 'v2|r' || char_length(project_repository) || ':' || project_repository
        || '|s' || char_length(project_workspace_scope) || ':' || project_workspace_scope
        || '|b' || char_length(project_branch) || ':' || project_branch AS canonical_scope
    FROM links
)
SELECT id, coalesce(nullif(btrim(branch_name), ''), project_branch) AS resolved_branch,
       CASE
         WHEN analysis_snapshot_id IS NOT NULL AND snapshot_found IS NULL THEN 'missing_snapshot'
         WHEN analysis_snapshot_id IS NOT NULL AND snapshot_project IS NULL THEN 'missing_project'
         WHEN project_id IS NOT NULL AND snapshot_found IS NOT NULL AND project_id <> snapshot_project THEN 'conflicting_project_link'
         WHEN requirement_id IS NOT NULL AND snapshot_found IS NOT NULL AND requirement_id IS DISTINCT FROM snapshot_requirement THEN 'conflicting_requirement_link'
         WHEN project_id IS NULL AND snapshot_found IS NULL AND (nullif(btrim(branch_name), '') IS NULL OR requirement_id IS NOT NULL) THEN 'missing_provenance'
         WHEN (project_id IS NOT NULL OR snapshot_found IS NOT NULL) AND project_found IS NULL THEN 'missing_project'
         WHEN project_found IS NOT NULL AND (canonical_scope IS NULL OR project_scope IS DISTINCT FROM canonical_scope) THEN 'branch_scope_mismatch'
         WHEN project_found IS NOT NULL AND repository_id IS DISTINCT FROM project_repository THEN 'repository_mismatch'
         WHEN project_found IS NOT NULL AND (nullif(btrim(workspace_id), '') IS DISTINCT FROM project_workspace
              OR project_workspace_scope IS DISTINCT FROM CASE WHEN nullif(btrim(workspace_id), '') IS NULL THEN 'CENTRAL' ELSE 'WORKSPACE:' || btrim(workspace_id) END) THEN 'workspace_mismatch'
         WHEN project_found IS NOT NULL AND nullif(btrim(branch_name), '') IS NOT NULL AND btrim(branch_name) <> project_branch THEN 'branch_scope_mismatch'
         WHEN snapshot_found IS NOT NULL AND (snapshot_scope IS DISTINCT FROM canonical_scope
              OR snapshot_workspace IS DISTINCT FROM nullif(btrim(workspace_id), '')
              OR (snapshot_branch IS NOT NULL AND snapshot_branch <> project_branch)) THEN 'branch_scope_mismatch'
         WHEN coalesce(requirement_id, snapshot_requirement) IS NOT NULL AND requirement_found IS NULL THEN 'missing_requirement'
         WHEN requirement_found IS NOT NULL AND (requirement_project IS DISTINCT FROM project_found OR requirement_scope IS DISTINCT FROM canonical_scope) THEN 'conflicting_requirement_link'
       END AS failure
FROM canonical;

DO $$
DECLARE reasons text; ids text;
BEGIN
    SELECT string_agg(failure || '=' || n, ', ' ORDER BY failure) INTO reasons
      FROM (SELECT failure, count(*) n FROM hypothesis_branch_authority WHERE failure IS NOT NULL GROUP BY failure) failures;
    IF reasons IS NOT NULL THEN
        SELECT string_agg(id::text, ', ' ORDER BY id) INTO ids FROM
          (SELECT id FROM hypothesis_branch_authority WHERE failure IS NOT NULL ORDER BY id LIMIT 20) failed;
        RAISE EXCEPTION 'Hypothesis branch authority unresolved: counts=%, row IDs (at most 20)=[%]. Data retained. Repair recorded snapshot/project provenance from backup and retry; never infer from current workspace or status.', reasons, ids;
    END IF;
    SELECT count(*)::text INTO reasons FROM relation_evidence e LEFT JOIN relation_hypothesis h ON h.id=e.hypothesis_id
      LEFT JOIN hypothesis_branch_authority a ON a.id=h.id
      WHERE h.id IS NULL OR (e.repository_id IS NOT NULL AND e.repository_id IS DISTINCT FROM h.repository_id)
      OR (e.workspace_scope_key IS NOT NULL AND e.workspace_scope_key IS DISTINCT FROM h.workspace_scope_key)
      OR (e.branch_name IS NOT NULL AND e.branch_name IS DISTINCT FROM a.resolved_branch);
    IF reasons <> '0' THEN
        SELECT string_agg(id::text, ', ' ORDER BY id) INTO ids FROM
          (SELECT e.id FROM relation_evidence e LEFT JOIN relation_hypothesis h ON h.id=e.hypothesis_id
            LEFT JOIN hypothesis_branch_authority a ON a.id=h.id
            WHERE h.id IS NULL OR (e.repository_id IS NOT NULL AND e.repository_id IS DISTINCT FROM h.repository_id)
            OR (e.workspace_scope_key IS NOT NULL AND e.workspace_scope_key IS DISTINCT FROM h.workspace_scope_key)
            OR (e.branch_name IS NOT NULL AND e.branch_name IS DISTINCT FROM a.resolved_branch)
            ORDER BY e.id LIMIT 20) failed;
        RAISE EXCEPTION 'Hypothesis evidence authority unresolved: orphan_or_conflicting_evidence=%, row IDs (at most 20)=[%]. Data retained; repair recorded parent links before restart.', reasons, ids;
    END IF;
END $$;

UPDATE relation_hypothesis h SET branch_name=a.resolved_branch FROM hypothesis_branch_authority a WHERE h.id=a.id;
UPDATE relation_evidence e SET repository_id=h.repository_id, workspace_scope_key=h.workspace_scope_key, branch_name=h.branch_name
  FROM relation_hypothesis h WHERE h.id=e.hypothesis_id;

DO $$
DECLARE duplicates bigint; item record;
BEGIN
    SELECT count(*) INTO duplicates FROM
      (SELECT 1 FROM relation_hypothesis GROUP BY repository_id, workspace_scope_key, branch_name,
       source_node_id, target_node_id, relation_type, analysis_session_scope_key HAVING count(*)>1) d;
    IF duplicates > 0 THEN RAISE EXCEPTION 'Hypothesis branch identity has % duplicate groups. Data retained; resolve conflicts before restart.', duplicates; END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='relation_hypothesis'::regclass AND conname='uq_hypothesis_parent_scope') THEN
        ALTER TABLE relation_hypothesis ADD CONSTRAINT uq_hypothesis_parent_scope UNIQUE(id, repository_id, workspace_scope_key, branch_name);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='relation_hypothesis'::regclass AND conname='uq_hypothesis_branch_session_relation') THEN
        ALTER TABLE relation_hypothesis ADD CONSTRAINT uq_hypothesis_branch_session_relation UNIQUE(repository_id, workspace_scope_key, branch_name, source_node_id, target_node_id, relation_type, analysis_session_scope_key);
    END IF;
    -- Remove only the two historic hypothesis identities; the new key is already in place.
    FOR item IN SELECT conname FROM pg_constraint WHERE conrelid='relation_hypothesis'::regclass AND contype='u'
      AND (SELECT array_agg(attname::text ORDER BY attname) FROM pg_attribute WHERE attrelid=conrelid AND attnum=ANY(conkey)) IN (
        ARRAY['analysis_session_id','relation_type','source_node_id','target_node_id','workspace_id'],
        ARRAY['analysis_session_scope_key','relation_type','repository_id','source_node_id','target_node_id','workspace_scope_key'])
    LOOP EXECUTE format('ALTER TABLE relation_hypothesis DROP CONSTRAINT %I', item.conname); END LOOP;
END $$;

ALTER TABLE relation_hypothesis ALTER COLUMN branch_name SET NOT NULL;
ALTER TABLE relation_evidence ALTER COLUMN hypothesis_id SET NOT NULL;
ALTER TABLE relation_evidence ALTER COLUMN repository_id SET NOT NULL;
ALTER TABLE relation_evidence ALTER COLUMN workspace_scope_key SET NOT NULL;
ALTER TABLE relation_evidence ALTER COLUMN branch_name SET NOT NULL;
ALTER TABLE relation_evidence ADD CONSTRAINT fk_evidence_hypothesis_scope
    FOREIGN KEY(hypothesis_id, repository_id, workspace_scope_key, branch_name)
    REFERENCES relation_hypothesis(id, repository_id, workspace_scope_key, branch_name);
CREATE INDEX IF NOT EXISTS idx_hyp_branch_scope ON relation_hypothesis(repository_id, workspace_scope_key, branch_name);
