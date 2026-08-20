package com.jazzlogs.backend.chat.dto;

import java.util.UUID;

import com.jazzlogs.backend.chat.CatalogItemType;

public record TrackWinnerCard(
    UUID id,
    String name,
    String imageUrl,
    String primaryArtist,
    String albumName,
    Integer durationMs,
    String spotifyUrl
) implements WinnerCard {

    @Override
    public CatalogItemType type() {
        return CatalogItemType.TRACK;
    }
}
