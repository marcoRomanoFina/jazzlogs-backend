-- Second per-chapter image (landscape/hero variant), uploaded via
-- PUT /series/{id}/chapters/{chapterId}/landscape-cover (SeriesService.setChapterLandscapeImage) —
-- separate from image_url (V32), which is the chapter's own square-ish cover.
ALTER TABLE series_chapters ADD COLUMN landscape_image_url character varying(255);
