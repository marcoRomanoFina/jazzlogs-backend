-- Supports ChatExchangeRepository.findByChatId (GET /chats/{chatId}/exchanges):
-- WHERE chat_id = ? ORDER BY created_at. Also backs findTop3ByChatId
-- OrderByCreatedAtDesc (ChatContextBuilder). Same reasoning as V16 —
-- Postgres doesn't auto-index FK columns, and chat_exchanges had no index
-- beyond its id PK.
CREATE INDEX IF NOT EXISTS idx_chat_exchanges_chat_id_created_at
    ON chat_exchanges (chat_id, created_at);
