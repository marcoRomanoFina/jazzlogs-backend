-- standout was an admin-set "notable within its own album" flag, distinct
-- from Track.featured — dropped along with its column, no replacement.
ALTER TABLE tracks DROP COLUMN is_standout;
