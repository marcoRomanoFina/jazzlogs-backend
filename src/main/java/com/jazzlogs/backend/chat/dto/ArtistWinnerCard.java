package com.jazzlogs.backend.chat.dto;

import java.util.UUID;

import com.jazzlogs.backend.chat.CatalogItemType;

public record ArtistWinnerCard(
    UUID id,
    String name,
    String imageUrl,
    String spotifyUrl
) implements WinnerCard {

    @Override
    public CatalogItemType type() {
        return CatalogItemType.ARTIST;
    }
}
