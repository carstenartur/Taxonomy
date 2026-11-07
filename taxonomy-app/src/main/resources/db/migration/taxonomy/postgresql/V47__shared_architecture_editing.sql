-- A working copy stays private until its owner explicitly enables collaboration.
ALTER TABLE user_workspace ADD COLUMN collaborative BOOLEAN NOT NULL DEFAULT FALSE;
