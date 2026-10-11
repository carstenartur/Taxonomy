-- Staging-only state; no activation, active-installation job or source credentials.
create table restore_source_owner (owner_id varchar(32) primary key, generation_id varchar(36) not null, archive_sha256 varchar(64) not null, content_root_sha256 varchar(64) not null);
create table restore_source_mapping (record_kind varchar(96) not null, source_value varchar(4096) not null, target_id bigint not null, row_sha256 varchar(64) not null, primary key(record_kind,source_value), unique(record_kind,target_id));
create table restore_source_file (entry_path varchar(4096) primary key, content_name varchar(80) not null, length bigint not null, sha256 varchar(64) not null);
create table restore_source_quarantine (record_kind varchar(96) not null, source_value varchar(4096) not null, record_json clob not null, sha256 varchar(64) not null, reason varchar(80) not null, primary key(record_kind,source_value));
