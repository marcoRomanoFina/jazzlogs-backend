package com.jazzlogs.backend.saveditem;

// Deliberately separate from ListenableEntityType and LikeableEntityType — the
// domains don't line up 1:1 (PLAYLIST is saveable but not listenable; ALBUM/TRACK
// are listenable but not likeable directly). PLAYLIST is prepared here even
// though the Playlist entity doesn't exist yet — same approach as entity_id
// having no real FK in listens/likes.
public enum SaveableEntityType {
    ALBUM,
    TRACK,
    PLAYLIST
}
