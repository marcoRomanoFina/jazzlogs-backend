-- Supports ChatRepository.findByUserId (GET /chats): WHERE user_id = ?
-- ORDER BY last_message_at DESC. Postgres doesn't auto-index FK columns,
-- and chats had no index beyond its id PK — every page of that query was
-- a sequential scan + sort over the whole table.
CREATE INDEX IF NOT EXISTS idx_chats_user_id_last_message_at
    ON chats (user_id, last_message_at DESC);
