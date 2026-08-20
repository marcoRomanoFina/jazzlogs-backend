package com.jazzlogs.backend.chat.chatexchange.dto;

import java.util.UUID;

import com.jazzlogs.backend.chat.CatalogItemType;

public record AlbumWinnerCard(
    UUID id,
    String name,
    String imageUrl,
    String primaryArtist,
    Integer releaseYear,
    String spotifyUrl
) implements WinnerCard {

    @Override
    public CatalogItemType type() {
        return CatalogItemType.ALBUM;
    }
}
