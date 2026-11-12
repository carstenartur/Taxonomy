-- Jobs recorded before model admission retain an unknown source.
alter table req_analysis_job add column admitted_model_json clob;
