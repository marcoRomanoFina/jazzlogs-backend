package com.jazzlogs.backend.chat.chatexchange.dto;

import java.util.UUID;

import com.jazzlogs.backend.chat.CatalogItemType;

/**
 * A catalog item the agent recommended, enriched with what the UI needs to
 * render it. Unlike {@link com.jazzlogs.backend.chat.chatexchange.WinnerReference} — the
 * lightweight snapshot persisted on the exchange and in recommendation
 * memory — this is resolved fresh against the catalog on every response.
 * TRACK is the only recommendable type — Album/Artist are still resolvable
 * as search scope (see {@code ResolveJazzlogsEntityTool}), but never the
 * final winner, so this sealed interface has a single permitted
 * implementation.
 */
public sealed interface WinnerCard permits TrackWinnerCard {

    CatalogItemType type();

    UUID id();

    String name();

    String imageUrl();
}
