package com.jazzlogs.backend.playlist;

// Classification only, no behavior difference yet — the frontend decides
// what to do with it (a badge, a separate section, ...). Not one of the
// Neo4j-backed vocabulary tags (style/mood/context): this is a property of
// the playlist itself, stored in Postgres like title/tagline.
public enum PlaylistType {
    JOURNEY,
    STANDARD
}
