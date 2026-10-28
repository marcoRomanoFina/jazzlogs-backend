-- byline moves from free text to a fixed set of characters (EditorialByline)
-- — every existing row was already just "Jazzlogs"/"JazzLogs" in some casing,
-- so this normalizes them to the enum's JAZZLOGS constant, the new default
-- for unsigned pieces going forward.
UPDATE editorials SET byline = 'JAZZLOGS';
ALTER TABLE editorials ALTER COLUMN byline SET NOT NULL;
