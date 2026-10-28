package com.jazzlogs.backend.like;

/** Every kind of entity that can carry likes, dispatched by {@link LikeService}. */
public enum LikeableEntityType {
    EDITORIAL,
    PLAYLIST,
    NOTE,
    SERIES
}
