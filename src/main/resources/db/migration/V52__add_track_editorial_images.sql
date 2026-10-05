-- track_editorials.image_url becomes cover_image_url, joined by four more
-- upload-only image slots (principal/secondary/banner/footer) — same page
-- layout AlbumEditorial used to have before the track-only pivot.
ALTER TABLE track_editorials RENAME COLUMN image_url TO cover_image_url;
ALTER TABLE track_editorials ADD COLUMN principal_image_url character varying(255);
ALTER TABLE track_editorials ADD COLUMN secondary_image_url character varying(255);
ALTER TABLE track_editorials ADD COLUMN banner_image_url character varying(255);
ALTER TABLE track_editorials ADD COLUMN footer_image_url character varying(255);
