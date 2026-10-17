-- Mirrors taxonomy V39 for physically isolated restore targets.
-- Existing jobs retain unknown recorded generation settings.
alter table req_analysis_job add column analysis_reason_length varchar(16);
alter table req_analysis_job add constraint ck_req_analysis_reason_length
    check (analysis_reason_length is null or analysis_reason_length in ('SHORT', 'STANDARD', 'DETAILED'));
