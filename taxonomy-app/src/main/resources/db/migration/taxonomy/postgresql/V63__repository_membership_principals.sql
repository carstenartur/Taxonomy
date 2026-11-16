-- Provenance is available only on new, verified grants. Historical names are not identity evidence.
ALTER TABLE repository_membership ADD COLUMN IF NOT EXISTS holder_principal_id VARCHAR(36);
ALTER TABLE repository_membership ADD COLUMN IF NOT EXISTS grantor_principal_id VARCHAR(36);
