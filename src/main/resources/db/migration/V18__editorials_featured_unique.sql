-- EditorialService.setFeaturated does clearFeaturated() + markFeaturated(id)
-- in one transaction, but that alone doesn't stop two concurrent calls from
-- both clearing (seeing nothing featured) and then both marking a different
-- row true — nothing before this locked the invariant as a whole. A partial
-- unique index only constrains rows where featurated = true, so false rows
-- are unaffected; the second concurrent writer now gets a
-- DataIntegrityViolationException instead of silently leaving two rows
-- featured at once (see EditorialService.setFeaturated's catch).
CREATE UNIQUE INDEX IF NOT EXISTS idx_editorials_only_one_featured
    ON editorials (featurated) WHERE featurated = true;
