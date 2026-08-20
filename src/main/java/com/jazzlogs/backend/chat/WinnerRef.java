package com.jazzlogs.backend.chat;

import java.util.UUID;

/**
 * Lightweight pointer into the catalog — what a chat recommended, one per
 * item. Used two ways: {@link ChatExchange#getWinners()} keeps the winners
 * of a single exchange for re-rendering it, while
 * {@link ChatRecommendationMemory#getWinnersHistory()} accumulates every
 * winner across the whole session, just to avoid recommending the same
 * thing twice. No style/mood/etc. — only enough to identify and display it.
 *
 * @param type          the catalog item's type
 * @param id            the catalog item's id
 * @param name          the catalog item's display name
 * @param primaryArtist the item's primary artist name, null when {@code type} is ARTIST
 */
public record WinnerRef(
    CatalogItemType type,
    UUID id,
    String name,
    String primaryArtist
) {
}
