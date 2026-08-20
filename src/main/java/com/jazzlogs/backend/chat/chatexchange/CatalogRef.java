package com.jazzlogs.backend.chat.chatexchange;

import com.jazzlogs.backend.chat.CatalogItemType;

// Unresolved pointer into the catalog — the shape a caller of
// ChatExchangeService.persist works with before it's checked against the
// real catalog. id is a raw string, not UUID: whoever builds this list (the
// agent, echoing back whatever a tool call returned) can't be trusted to
// have sent a real id — see ChatExchangeService.resolveWinners.
public record CatalogRef(CatalogItemType type, String id) {
}
