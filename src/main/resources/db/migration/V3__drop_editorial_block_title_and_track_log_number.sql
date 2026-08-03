-- Catches this database up with entity changes made after V0 was captured,
-- back when ddl-auto=update was still silently applying them. Verified safe
-- against the real data before writing this: 0 rows in tracks, 0 albums with
-- a null log_number, 0 editorial_blocks with type = SUBHEAD.
ALTER TABLE tracks DROP COLUMN log_number;

ALTER TABLE albums ALTER COLUMN log_number SET NOT NULL;

ALTER TABLE editorial_blocks DROP CONSTRAINT editorial_blocks_type_check;
ALTER TABLE editorial_blocks ADD CONSTRAINT editorial_blocks_type_check
    CHECK (((type)::text = ANY ((ARRAY['LEAD'::character varying, 'PARA'::character varying, 'QUOTE'::character varying])::text[])));
