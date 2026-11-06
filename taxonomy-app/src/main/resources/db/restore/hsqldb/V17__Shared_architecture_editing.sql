-- Preserve the owner-managed opt-in without changing a frozen restore migration.
ALTER TABLE user_workspace ADD COLUMN collaborative BOOLEAN DEFAULT FALSE;
