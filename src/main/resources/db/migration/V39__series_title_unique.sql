-- Same reasoning as uq_playlists_title (V29) — SeriesService.getOnboardingSeries
-- looks a series up by its title, which only works if titles can't collide.
ALTER TABLE series ADD CONSTRAINT uq_series_title UNIQUE (title);
