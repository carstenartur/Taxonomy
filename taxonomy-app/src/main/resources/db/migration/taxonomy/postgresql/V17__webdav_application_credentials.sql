-- Revocable, WebDAV-only application credentials. Plaintext secrets are never stored.
create table webdav_application_credential (
    credential_id varchar(24) primary key,
    username varchar(255) not null,
    description varchar(160) not null,
    secret_hash varchar(100) not null,
    read_allowed boolean not null,
    write_allowed boolean not null,
    authorities varchar(500) not null,
    created_at timestamp(6) with time zone not null,
    expires_at timestamp(6) with time zone not null,
    last_used_at timestamp(6) with time zone,
    revoked_at timestamp(6) with time zone,
    version bigint not null
);

create index idx_webdav_credential_username
    on webdav_application_credential (username, created_at desc);
create index idx_webdav_credential_expiry
    on webdav_application_credential (expires_at);
