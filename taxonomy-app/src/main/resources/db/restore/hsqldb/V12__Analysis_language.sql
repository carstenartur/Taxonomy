-- Capture content language for new jobs only. Historical provenance stays unknown.
alter table req_analysis_job add column analysis_language varchar(16);
alter table req_analysis_job add constraint ck_req_analysis_language
    check (analysis_language is null or analysis_language in ('GERMAN', 'ENGLISH'));
