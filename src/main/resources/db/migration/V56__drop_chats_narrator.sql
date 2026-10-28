-- A chat is no longer a conversation with one of eight narrators: the agent
-- speaks in a single editorial voice, JazzLogs' own, so there is nothing to
-- choose per chat. Undoes the chats half of V55.
ALTER TABLE chats DROP COLUMN narrator;
