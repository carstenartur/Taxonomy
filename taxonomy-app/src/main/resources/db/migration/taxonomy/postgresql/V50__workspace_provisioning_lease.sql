-- Existing in-flight workers receive one normal lease interval during upgrade.
alter table user_workspace add column provisioning_sync_base_branch varchar(255);
alter table user_workspace add column provisioning_lease_owner varchar(36);
alter table user_workspace add column provisioning_lease_until timestamp with time zone;
alter table user_workspace add column provisioning_attempt integer default 0 not null;
alter table user_workspace add column provisioning_attempt_at timestamp with time zone;

update user_workspace set provisioning_lease_until = current_timestamp + interval '15 minutes'
where provisioning_status = 'PROVISIONING';
