-- Deleting an editorials row now cascades to its subclass row
-- (album/track/artist_editorials) and its blocks — without this, deleting
-- from the wrong end (e.g. just the subclass row) orphans the base
-- editorials row and its editorial_blocks, with nothing left pointing at
-- them. The correct way to delete an editorial is DELETE FROM editorials;
-- this makes that single statement actually clean up everything.
ALTER TABLE editorial_blocks DROP CONSTRAINT fk3iwgtebvh6962ofuy8fo49edf;
ALTER TABLE editorial_blocks ADD CONSTRAINT fk3iwgtebvh6962ofuy8fo49edf
    FOREIGN KEY (editorial_id) REFERENCES editorials(id) ON DELETE CASCADE;

ALTER TABLE album_editorials DROP CONSTRAINT fkj4vf7ujbh3gvuexrdgoitig3q;
ALTER TABLE album_editorials ADD CONSTRAINT fkj4vf7ujbh3gvuexrdgoitig3q
    FOREIGN KEY (editorial_id) REFERENCES editorials(id) ON DELETE CASCADE;

ALTER TABLE track_editorials DROP CONSTRAINT fk7vgmwjp1nmmrq26b9kajktfye;
ALTER TABLE track_editorials ADD CONSTRAINT fk7vgmwjp1nmmrq26b9kajktfye
    FOREIGN KEY (editorial_id) REFERENCES editorials(id) ON DELETE CASCADE;

ALTER TABLE artist_editorials DROP CONSTRAINT fkjo7ktkn66ykuq7g6tw1fn21p;
ALTER TABLE artist_editorials ADD CONSTRAINT fkjo7ktkn66ykuq7g6tw1fn21p
    FOREIGN KEY (editorial_id) REFERENCES editorials(id) ON DELETE CASCADE;

-- Cleans up the 2 editorials rows orphaned by manually deleting their
-- album_editorials row before this cascade existed (their editorial_blocks
-- go with them automatically now).
DELETE FROM editorials WHERE id IN (
    '1ce6eeb2-bf85-4d5d-8b0c-a9f36bbd0275',
    '7634ef55-2581-4758-a972-af7720b51e7e'
);
