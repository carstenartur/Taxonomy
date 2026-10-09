-- Retain legacy NULL bindings; never invent an identity for already queued work.
alter table req_analysis_job
    add column provider_plugin_id varchar(128),
    add column provider_plugin_version varchar(128),
    add column provider_plugin_sha256 varchar(64),
    add column provider_config_revision varchar(64);

alter table req_analysis_job add constraint ck_req_analysis_provider_binding
    check ((provider_plugin_id is null and provider_plugin_version is null
            and provider_plugin_sha256 is null and provider_config_revision is null)
        or (provider_plugin_id is not null and provider_plugin_version is not null
            and provider_plugin_sha256 is not null and provider_config_revision is not null));

-- Open provider identities use the same length through admission, snapshots and usage.
alter table req_analysis_job alter column provider type varchar(128);
alter table req_analysis_snapshot alter column provider type varchar(128);
alter table reformulation_usage_attempt alter column provider type varchar(128);
alter table analysis_question_checkpoint alter column provider type varchar(128);
