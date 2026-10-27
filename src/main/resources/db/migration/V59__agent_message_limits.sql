-- Caps on agent messages, the one request in JazzLogs that costs money.
-- One row per user: the 24-hour window their first message in it opened, and
-- how many messages they have sent since. A window is never closed by a job —
-- the next message after it expires simply opens a new one.
CREATE TABLE agent_usage_windows (
    user_id           uuid        PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    window_started_at timestamptz NOT NULL,
    messages_used     integer     NOT NULL
);

-- One row per UTC day: every user's messages together. A backstop, so the
-- worst case is the demo pausing until tomorrow.
CREATE TABLE agent_daily_usage (
    day           date    PRIMARY KEY,
    messages_used integer NOT NULL
);
