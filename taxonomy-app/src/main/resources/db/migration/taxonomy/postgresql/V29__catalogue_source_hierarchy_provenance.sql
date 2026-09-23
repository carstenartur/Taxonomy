alter table taxonomy_node
    add column if not exists source_parent_reference varchar(255);
alter table taxonomy_node
    add column if not exists source_parent_code varchar(255);
alter table taxonomy_node
    add column if not exists source_order integer;
alter table taxonomy_node
    add column if not exists catalogue_origin varchar(32) not null default 'OFFICIAL_SOURCE';
