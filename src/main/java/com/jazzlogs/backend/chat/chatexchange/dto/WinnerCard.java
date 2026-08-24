package com.jazzlogs.backend.chat.chatexchange.dto;

import java.util.UUID;

import com.jazzlogs.backend.chat.CatalogItemType;

/**
 * A catalog item the agent recommended, enriched with what the UI needs to
 * render it. Unlike {@link com.jazzlogs.backend.chat.chatexchange.WinnerReference} — the
 * lightweight snapshot persisted on the exchange and in recommendation
 * memory — this is resolved fresh against the catalog on every response,
 * one concrete record per {@link CatalogItemType} so each type carries only
 * the extra fields it actually has.
 */
public sealed interface WinnerCard permits AlbumWinnerCard, TrackWinnerCard, ArtistWinnerCard {

    CatalogItemType type();

    UUID id();

    String name();

    String imageUrl();
}
