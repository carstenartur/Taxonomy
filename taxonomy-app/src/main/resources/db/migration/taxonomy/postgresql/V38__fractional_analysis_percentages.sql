-- Preserve percentage units: 85 remains 85%, 0.125 represents 0.125%.
-- Widen only scoring values, not IDs, counts, priorities or hierarchy depths.
alter table req_element_mapping alter column direct_score type double precision using direct_score::double precision;
alter table requirement_coverage alter column score type double precision using score::double precision;
alter table req_solution_link alter column coverage_percent type double precision using coverage_percent::double precision;
