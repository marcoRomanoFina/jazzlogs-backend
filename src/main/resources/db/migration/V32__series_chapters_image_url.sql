-- Per-chapter cover image, uploaded to MinIO via PUT /series/{id}/chapters/{chapterId}/cover
-- (SeriesService.setChapterCoverImage) — same pattern as Series/Playlist's own cover,
-- never a raw URL accepted through SeriesChapterInput.
ALTER TABLE series_chapters ADD COLUMN image_url character varying(255);
