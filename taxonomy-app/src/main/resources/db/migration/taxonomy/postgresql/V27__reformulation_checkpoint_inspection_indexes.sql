-- Narrow indexes for existing run-scoped inspection queries. The run ID is globally
-- unique; authorization still checks proposal and workspace scope in every query.
-- Do not include result_payload or duplicate the potentially long scope key.
CREATE INDEX idx_reform_cp_run_order ON reformulation_node_checkpoint (run_id, created_at, id);
CREATE INDEX idx_reform_cp_run_kind ON reformulation_node_checkpoint (run_id, task_kind);
