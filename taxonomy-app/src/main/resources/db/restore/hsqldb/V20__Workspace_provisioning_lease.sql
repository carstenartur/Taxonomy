-- Existing in-flight workers receive one normal lease interval during upgrade.
alter table user_workspace add column provisioning_sync_base_branch varchar(255);
alter table user_workspace add column provisioning_lease_owner varchar(36);
alter table user_workspace add column provisioning_lease_until timestamp(6);
alter table user_workspace add column provisioning_attempt integer default 0 not null;
alter table user_workspace add column provisioning_attempt_at timestamp(6);

update user_workspace set provisioning_lease_until = current_timestamp + interval '15' minute
where provisioning_status = 'PROVISIONING';
