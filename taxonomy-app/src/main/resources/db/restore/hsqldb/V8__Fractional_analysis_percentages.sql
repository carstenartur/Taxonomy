-- Kept in the staged restore stream as well as the live pre-Hibernate migration.
alter table req_element_mapping alter column direct_score set data type double;
alter table requirement_coverage alter column score set data type double;
alter table req_solution_link alter column coverage_percent set data type double;
