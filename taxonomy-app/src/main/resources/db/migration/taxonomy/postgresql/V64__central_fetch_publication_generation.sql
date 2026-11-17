-- Keep native Git evidence scoped to this admission when a receipt ID is reused.
alter table central_fetch_operation add column publication_generation varchar(36);
