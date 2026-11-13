-- The original authenticated actor cannot be inferred from historical owner names.
alter table system_repository add column fork_initiator_key varchar(64);
