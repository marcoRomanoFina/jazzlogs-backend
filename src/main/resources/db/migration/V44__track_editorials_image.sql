-- One image for a track editorial's own page — see TrackEditorial.imageUrl.
ALTER TABLE track_editorials ADD COLUMN image_url character varying(255);
