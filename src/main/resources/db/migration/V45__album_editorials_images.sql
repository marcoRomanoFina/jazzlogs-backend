-- Four images for an album editorial's own page layout — see
-- AlbumEditorial.{principal,secondary,banner,footer}ImageUrl.
ALTER TABLE album_editorials ADD COLUMN principal_image_url character varying(255);
ALTER TABLE album_editorials ADD COLUMN secondary_image_url character varying(255);
ALTER TABLE album_editorials ADD COLUMN banner_image_url character varying(255);
ALTER TABLE album_editorials ADD COLUMN footer_image_url character varying(255);
