package com.jazzlogs.backend.chat;

import java.util.UUID;

// Lightweight pointer into the catalog — what ChatRecommendationMemory.winnersHistory
// stores, one per item ever recommended in the session. No style/mood/etc.:
// this is only for "don't recommend the same thing twice", not for re-rendering
// the original recommendation (that detail lives on ChatExchange.winners).
public record WinnerRef(
    CatalogItemType type,
    UUID id,
    String name,
    String primaryArtist   // null if type=ARTIST
) {
}
